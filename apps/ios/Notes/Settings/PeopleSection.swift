import NotesCore
import SwiftUI

/// Space info's section for sharing (§7.9): who's in the space, who's invited, then everyone else on the server,
/// each invited with one tap (the first invitation shares the space). All changed in place.
struct PeopleSection: View {
    let space: Space
    /// Everyone on the server, read as Space info opened; nil until then.
    let people: Result<[Member], SharingError>?
    @Environment(AppGraph.self) private var graph
    @Environment(\.dismiss) private var dismiss
    /// What's being done ("invite 3", "cancel <id>", "confirm"): one thing at a time.
    @State private var busy: String?
    @State private var error: String?
    /// Just invited: their row arrives with a highlight, seen where it landed.
    @State private var fresh: Int64?
    @State private var confirmStop = false
    /// Asking to leave, with how many notes here have changes not yet synced.
    @State private var leaving: Int?

    var body: some View {
        let shared = space.shared
        let owner = shared?.isOwner ?? true
        // Shared once anyone else is in it or invited; until then, what inviting does.
        let inUse = (shared?.members.count ?? 0) > 1 || !(shared?.invites.isEmpty ?? true)
        let candidates = owner ? people.map { $0.map { Sharing.invitable($0, to: space, me: graph.userId) } } : nil
        Section {
            if let shared, inUse {
                ForEach(shared.members) { member in
                    MemberRow(
                        member: member, role: Sharing.role(of: member, in: shared, me: graph.userId),
                        spaceName: space.name, busy: busy != nil,
                        // The owner is listed first: that's this account, even before its id has loaded.
                        manage: owner && member.id != shared.members.first?.id ? { manage($0, member) } : nil)
                }
                ForEach(shared.invites) { invite in
                    InviteRow(
                        invite: invite, fresh: invite.userId == fresh, busy: busy == "cancel \(invite.id)",
                        disabled: busy != nil,
                        onCancel: owner ? { cancel(invite) } : nil)
                }
            } else {
                Text("Invite people to read and edit every note in \(space.name), and add their own.")
                    .font(.notesBody)
                    .foregroundStyle(Color.ink2)
                    .padding(.vertical, 4)
                switch candidates {
                case .none: ProgressView().frame(maxWidth: .infinity)
                case .some(let list): invitable(list, alone: true)
                }
            }
        } header: {
            SectionHeader("People")
        } footer: {
            if !inUse { problem }
        }
        if inUse, let candidates, (try? candidates.get())?.isEmpty != true {
            Section {
                invitable(candidates, alone: false)
            } header: {
                SectionHeader("Invite")
            }
        }
        if inUse {
            Section {
                if owner { stopSharing } else { leave }
            } footer: {
                problem
            }
        }
    }

    @ViewBuilder private func invitable(_ candidates: Result<[Member], SharingError>, alone: Bool) -> some View {
        switch candidates {
        case .failure(let failure):
            Text(failure.message).font(.notesSmall).foregroundStyle(Color.danger)
        case .success(let list) where list.isEmpty:
            // With people listed above, nobody left to invite needs no words.
            if alone {
                Text("There’s no one else on this server yet. An admin adds people from the web, Mac or Android app.")
                    .font(.notesSmall)
                    .foregroundStyle(Color.ink2)
            }
        case .success(let list):
            ForEach(list) { person in
                CandidateRow(person: person, busy: busy == "invite \(person.id)") { invite(person) }
                    .disabled(busy != nil)
            }
        }
    }

    @ViewBuilder private var problem: some View {
        if let error { Text(error).font(.notesSmall).foregroundStyle(Color.danger) }
    }

    private var stopSharing: some View {
        Button("Stop Sharing…", role: .destructive) { confirmStop = true }
            .foregroundStyle(Color.danger)
            .disabled(busy != nil)
            .confirmationDialog(
                "Stop sharing “\(space.name)”?", isPresented: $confirmStop, titleVisibility: .visible
            ) {
                Button("Stop Sharing", role: .destructive) {
                    // Private again: the page shows who to invite.
                    let space = space
                    perform("confirm") { await graph.stopSharing(space) }
                }
            } message: {
                Text("Everyone else leaves it, and invitations are cancelled. The space and its notes stay yours.")
            }
    }

    private var leave: some View {
        Button("Leave Space…", role: .destructive) {
            busy = "leave"
            Task {
                let unsynced = (try? await graph.store.pendingNotes(space: space.id)) ?? 0
                busy = nil
                leaving = unsynced
            }
        }
        .foregroundStyle(Color.danger)
        .disabled(busy != nil)
        .confirmationDialog(
            "Leave “\(space.name)”?",
            isPresented: Binding(get: { leaving != nil }, set: { if !$0 { leaving = nil } }),
            titleVisibility: .visible, presenting: leaving
        ) { _ in
            Button("Leave Space", role: .destructive) {
                let id = space.id
                perform("confirm") {
                    await graph.leave(id)
                } then: {
                    dismiss()
                }
            }
        } message: { unsynced in
            Text(Sharing.leaveWarning(space.name, unsynced: unsynced))
        }
    }

    private func invite(_ person: Member) {
        // Marked before the call: the list can bring the row before the call returns.
        fresh = person.id
        let space = space
        perform("invite \(person.id)") {
            await graph.invite(person.id, to: space)
        } failed: {
            fresh = nil
        }
    }

    private func cancel(_ invite: Invitee) {
        let id = space.id
        perform("cancel \(invite.id)") { await graph.cancelInvite(invite.id, in: id) }
    }

    private func manage(_ change: MemberRow.Change, _ member: Member) {
        let id = space.id
        switch change {
        case .remove: perform("confirm") { await graph.removeMember(member.id, from: id) }
        case .owner: perform("confirm") { await graph.transfer(id, to: member.id) }
        }
    }

    private func perform(
        _ key: String, _ call: @escaping () async -> String?, then done: @escaping () -> Void = {},
        failed: @escaping () -> Void = {}
    ) {
        busy = key
        error = nil
        Task {
            let problem = await call()
            busy = nil
            error = problem
            if problem == nil { done() } else { failed() }
        }
    }
}

/// A member: their initial, name and part in the space. For the owner, Make Owner and Remove, in a menu and
/// on a swipe, each asking first where it was asked.
private struct MemberRow: View {
    enum Change {
        case remove
        case owner
    }

    let member: Member
    let role: String
    let spaceName: String
    let busy: Bool
    /// The owner's options for this member; nil for none.
    let manage: ((Change) -> Void)?
    @State private var asking: Change?

    var body: some View {
        HStack(spacing: 12) {
            Person(person: member, role: role)
            Spacer(minLength: 0)
            if let manage {
                Menu {
                    Button("Make Owner", systemImage: "crown") { asking = .owner }
                    Button("Remove from Space", systemImage: "person.badge.minus", role: .destructive) {
                        asking = .remove
                    }
                } label: {
                    Image(systemName: "ellipsis.circle")
                        .font(.system(size: 19))
                        .foregroundStyle(Color.ink2)
                        // Flush with the row's end, as the other rows' trailing controls are.
                        .frame(minWidth: 44, minHeight: 44, alignment: .trailing)
                        .contentShape(.rect)
                }
                .disabled(busy)
                .accessibilityLabel("Options for \(member.name)")
                .confirmationDialog(
                    asking.map(title) ?? "",
                    isPresented: Binding(get: { asking != nil }, set: { if !$0 { asking = nil } }),
                    titleVisibility: .visible, presenting: asking
                ) { change in
                    Button(change == .remove ? "Remove" : "Make Owner", role: change == .remove ? .destructive : nil) {
                        manage(change)
                    }
                } message: { change in
                    Text(message(change))
                }
            }
        }
        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
            // Not the destructive role: that slides the row away before anything is confirmed.
            if manage != nil && !busy {
                Button("Remove", systemImage: "person.badge.minus") { asking = .remove }
                    .tint(Color.danger)
                Button("Make Owner", systemImage: "crown") { asking = .owner }
                    .tint(Color.ink2)
            }
        }
    }

    private func title(_ change: Change) -> String {
        switch change {
        case .remove: "Remove \(member.name) from “\(spaceName)”?"
        case .owner: "Make \(member.name) the owner of “\(spaceName)”?"
        }
    }

    private func message(_ change: Change) -> String {
        switch change {
        case .remove: "Its notes will be removed from \(member.name)’s devices."
        case .owner:
            "\(member.name) will manage who’s in it, rename it or delete it. You stay a member and can leave."
        }
    }
}

/// Someone invited and not in yet: an outlined initial, and Cancel for the owner.
private struct InviteRow: View {
    let invite: Invitee
    let fresh: Bool
    let busy: Bool
    let disabled: Bool
    let onCancel: (() -> Void)?
    @State private var glow: Bool

    init(invite: Invitee, fresh: Bool, busy: Bool, disabled: Bool, onCancel: (() -> Void)?) {
        self.invite = invite
        self.fresh = fresh
        self.busy = busy
        self.disabled = disabled
        self.onCancel = onCancel
        _glow = State(initialValue: fresh)
    }

    var body: some View {
        HStack(spacing: 12) {
            Person(person: invite.person, role: "Invited", pending: true)
            Spacer(minLength: 0)
            if busy {
                ProgressView()
            } else if let onCancel {
                Button("Cancel", action: onCancel)
                    .buttonStyle(.borderless)
                    .font(.notesLabel)
                    .frame(minHeight: 44)
                    .disabled(disabled)
                    .accessibilityLabel("Cancel invitation for \(invite.person.name)")
            }
        }
        .listRowBackground(
            Color(uiColor: .secondarySystemGroupedBackground).overlay(Color.on.opacity(glow ? 1 : 0))
        )
        .task(id: fresh) {
            guard fresh else { return }
            glow = true
            try? await Task.sleep(for: .milliseconds(700))
            withAnimation(.easeOut(duration: 1.2)) { glow = false }
        }
    }
}

/// Someone on the server to invite: one tap.
private struct CandidateRow: View {
    let person: Member
    let busy: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Avatar(person: person)
                Text(person.name).font(.notesRowTitle).foregroundStyle(Color.ink)
                Spacer(minLength: 0)
                if busy {
                    ProgressView()
                } else {
                    Image(systemName: "plus.circle").imageScale(.large).accessibilityHidden(true)
                }
            }
            .contentShape(.rect)
        }
        .accessibilityLabel("Invite \(person.name)")
    }
}

/// A photo or initial, a name and, under it, their part: "Owner", "You", "Invited". The username goes beside the
/// part when they chose a name, which may repeat.
private struct Person: View {
    let person: Member
    let role: String
    var pending = false

    var body: some View {
        let line = [person.displayName.isEmpty ? "" : person.username, role].filter { !$0.isEmpty }
            .joined(separator: " · ")
        HStack(spacing: 12) {
            Avatar(person: person, pending: pending)
            VStack(alignment: .leading, spacing: 1) {
                Text(person.name).font(.notesRowTitle).foregroundStyle(Color.ink)
                if !line.isEmpty { Text(line).font(.notesSmall).foregroundStyle(Color.ink2) }
            }
        }
        .padding(.vertical, 2)
        .accessibilityElement(children: .combine)
    }
}

/// Their photo, or initial; not in yet, an outline, not a fill.
private struct Avatar: View {
    let person: Member
    var pending = false
    @ScaledMetric(relativeTo: .body) private var size: CGFloat = 32
    @Environment(AppGraph.self) private var graph
    @State private var photo: UIImage?

    var body: some View {
        let profile = Profile(userId: person.id, displayName: person.displayName, avatar: person.avatar)
        ZStack {
            if let image = photo ?? graph.avatars.cached(profile) {
                Image(uiImage: image).resizable().scaledToFill().clipShape(Circle())
            } else {
                Text(person.name.prefix(1).uppercased())
                    .font(.notesLabel.weight(.semibold))
                    .foregroundStyle(Color.ink2)
            }
        }
        .frame(width: size, height: size)
        .background {
            if pending {
                Circle().strokeBorder(Color.ink3, style: StrokeStyle(lineWidth: 1, dash: [3, 2.5]))
            } else {
                Circle().fill(Color.on)
            }
        }
        .accessibilityHidden(true)
        .task(id: profile) { photo = await graph.avatars.load(profile) }
    }
}
