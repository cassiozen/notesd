import NotesCore
import SwiftUI

/// A shared space's people (§7.9), from its Space info's Shared row: members, owner first, and invitations. The
/// owner invites, cancels, removes, hands the space on and stops sharing; a member leaves.
struct SharingView: View {
    let id: String
    @Environment(AppGraph.self) private var graph
    @Environment(\.dismiss) private var dismiss
    @State private var inviting = false
    @State private var busy = false
    @State private var error: String?
    @State private var confirm: Confirm?

    private enum Confirm: Identifiable {
        case remove(Member)
        case owner(Member)
        case stop
        case leave(unsynced: Int)

        var id: String {
            switch self {
            case .remove(let m): "remove \(m.id)"
            case .owner(let m): "owner \(m.id)"
            case .stop: "stop"
            case .leave: "leave"
            }
        }
    }

    var body: some View {
        let space = graph.spaces.first { $0.id == id }
        Form {
            if let space, let shared = space.shared { content(space, shared) }
        }
        .settingsForm(title: "Shared")
        .navigationBarTitleDisplayMode(.inline)
        .disabled(busy)
        .sheet(isPresented: $inviting) {
            if let space { PeoplePicker(space: space) { inviting = false } }
        }
        .confirmationDialog(
            confirm.map { title($0, space?.name ?? "") } ?? "",
            isPresented: Binding(get: { confirm != nil }, set: { if !$0 { confirm = nil } }),
            titleVisibility: .visible, presenting: confirm
        ) { c in
            Button(action(c), role: isDestructive(c) ? .destructive : nil) { run(c) }
        } message: { c in
            Text(message(c, space))
        }
    }

    @ViewBuilder private func content(_ space: Space, _ shared: SharedSpace) -> some View {
        Section("People") {
            ForEach(shared.members) { member in memberRow(member, shared) }
        }
        if shared.isOwner && !shared.invites.isEmpty {
            Section("Invited") {
                ForEach(shared.invites) { invite in
                    HStack {
                        Text(invite.username).foregroundStyle(Color.ink)
                        Spacer()
                        Button("Cancel") { perform { await graph.cancelInvite(invite.id, in: id) } }
                            .accessibilityLabel("Cancel invitation for \(invite.username)")
                    }
                }
            }
        }
        Section {
            if shared.isOwner {
                Button("Invite Someone…", systemImage: "person.badge.plus") { inviting = true }
                if shared.members.count > 1 || !shared.invites.isEmpty {
                    Button("Stop Sharing", role: .destructive) { confirm = .stop }
                }
            } else {
                Button("Leave Space", role: .destructive) {
                    Task {
                        let unsynced = (try? await graph.store.pendingNotes(space: id)) ?? 0
                        confirm = .leave(unsynced: unsynced)
                    }
                }
            }
        } footer: {
            if let error {
                Text(error).font(.notesSmall).foregroundStyle(Color.danger)
            } else if shared.isOwner {
                Text("Everyone here can read and edit every note in \(space.name).")
                    .font(.notesSmall).foregroundStyle(Color.ink2)
            }
        }
    }

    private func memberRow(_ member: Member, _ shared: SharedSpace) -> some View {
        let isOwner = member.id == shared.members.first?.id
        let isMe = member.id == graph.userId
        let labels = [isOwner ? "Owner" : nil, isMe ? "You" : nil].compactMap { $0 }
        return HStack {
            Text(member.username).foregroundStyle(Color.ink)
            Spacer()
            if !labels.isEmpty {
                Text(labels.joined(separator: " · ")).font(.notesSmall).foregroundStyle(Color.ink2)
            }
            if shared.isOwner && !isMe {
                Menu {
                    Button("Make Owner", systemImage: "crown") { confirm = .owner(member) }
                    Button("Remove from Space", systemImage: "person.badge.minus", role: .destructive) {
                        confirm = .remove(member)
                    }
                } label: {
                    Image(systemName: "ellipsis.circle").frame(minWidth: 44, minHeight: 44)
                }
                .accessibilityLabel("Options for \(member.username)")
            }
        }
        .accessibilityElement(children: .combine)
    }

    private func title(_ c: Confirm, _ name: String) -> String {
        switch c {
        case .remove(let m): "Remove \(m.username) from “\(name)”?"
        case .owner(let m): "Make \(m.username) the owner of “\(name)”?"
        case .stop: "Stop sharing “\(name)”?"
        case .leave: "Leave “\(name)”?"
        }
    }

    private func action(_ c: Confirm) -> String {
        switch c {
        case .remove: "Remove"
        case .owner: "Make Owner"
        case .stop: "Stop Sharing"
        case .leave: "Leave Space"
        }
    }

    private func isDestructive(_ c: Confirm) -> Bool {
        if case .owner = c { false } else { true }
    }

    private func message(_ c: Confirm, _ space: Space?) -> String {
        switch c {
        case .remove(let m): "Its notes will be removed from \(m.username)’s devices."
        case .owner(let m):
            "\(m.username) will manage who’s in it, rename it or delete it. You stay a member and can leave."
        case .stop:
            "Everyone else leaves it, and invitations are cancelled. The space and its notes stay yours."
        case .leave(let unsynced): Sharing.leaveWarning(space?.name ?? "", unsynced: unsynced)
        }
    }

    private func run(_ c: Confirm) {
        switch c {
        case .remove(let m): perform { await graph.removeMember(m.id, from: id) }
        case .owner(let m): perform { await graph.transfer(id, to: m.id) }
        case .stop:
            guard let space = graph.spaces.first(where: { $0.id == id }) else { return }
            // Private again: Space info shows Shared off, with Turn On.
            perform {
                await graph.stopSharing(space)
            } then: {
                dismiss()
            }
        case .leave:
            perform {
                await graph.leave(id)
            } then: {
                dismiss()
            }
        }
    }

    private func perform(_ call: @escaping () async -> String?, then done: @escaping () -> Void = {}) {
        busy = true
        error = nil
        Task {
            error = await call()
            busy = false
            if error == nil { done() }
        }
    }
}

/// Everyone on the server who isn't in the space yet; picking one invites them. The first invitation shares
/// the space.
struct PeoplePicker: View {
    let space: Space
    let onDone: () -> Void
    @Environment(AppGraph.self) private var graph
    @State private var people: [Member]?
    @State private var error: String?
    @State private var busy = false

    var body: some View {
        NavigationStack {
            Form {
                if let people {
                    if people.isEmpty {
                        Text("Everyone with an account on this server is already here.")
                            .font(.notesSmall).foregroundStyle(Color.ink2)
                    }
                    Section {
                        ForEach(people) { person in
                            Button {
                                invite(person)
                            } label: {
                                Label(person.username, systemImage: "person.crop.circle")
                                    .foregroundStyle(Color.ink)
                            }
                        }
                    } footer: {
                        if let error {
                            Text(error).font(.notesSmall).foregroundStyle(Color.danger)
                        } else if space.shared == nil {
                            Text("Everyone you invite can read and edit every note in \(space.name).")
                                .font(.notesSmall).foregroundStyle(Color.ink2)
                        }
                    }
                } else if let error {
                    Text(error).font(.notesSmall).foregroundStyle(Color.danger)
                } else {
                    ProgressView().frame(maxWidth: .infinity)
                }
            }
            .font(.notesBody)
            .tint(Color(Palette.link))
            .scrollContentBackground(.hidden)
            .background(Color.notesBg)
            .disabled(busy)
            .navigationTitle("Share “\(space.name)”")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel", role: .cancel, action: onDone)
                }
            }
        }
        .task {
            switch await graph.invitable(to: space) {
            case .success(let list): people = list
            case .failure(let e): error = e.message
            }
        }
    }

    private func invite(_ person: Member) {
        busy = true
        error = nil
        Task {
            if let problem = await graph.invite(person.id, to: space) {
                error = problem
                busy = false
            } else {
                onDone()
            }
        }
    }
}
