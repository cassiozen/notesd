import NotesCore
import SwiftUI

/// A space's one page of space-level settings (§7.8): one sheet, nothing opened over it, so what a change did shows
/// where it was made. Its icon, name, note count and glyphs; a section per trait; then Delete.
struct SpaceInfoView: View {
    let id: String
    @Environment(AppGraph.self) private var graph
    @Environment(\.dismiss) private var dismiss
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var picking = false
    /// Picked or typed and on its way to the list: shown, in the icon's colours, until the list has it.
    @State private var pendingIcon: String?
    @State private var pendingName: String?
    @State private var draft = ""
    @State private var nameSelection: TextSelection?
    @FocusState private var naming: Bool
    @State private var error: String?
    @State private var confirmDelete = false
    @State private var deleteError: String?
    /// Everyone on the server, read as the page opens: People lists who to invite without a wait.
    @State private var people: Result<[Member], SharingError>?
    @State private var left = false

    var body: some View {
        let space = graph.spaces.first { $0.id == id }
        NavigationStack {
            Form {
                if let space { content(space) }
            }
            .settingsForm(title: "Space Info")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
            // Rows arriving and leaving (someone invited, sharing stopped) slide rather than jump.
            .animation(reduceMotion ? nil : .snappy, value: space)
        }
        // The page takes its icon's colours, a pick's as soon as it's made.
        .environment(\.spaceTheme, SpaceThemes.key(pendingIcon ?? space?.icon ?? ""))
        .task(id: space.map(wantsPeople) ?? false) {
            guard space.map(wantsPeople) ?? false, people == nil else { return }
            people = await graph.people()
        }
        .onChange(of: space?.name, initial: true) { _, name in
            guard let name else { return }
            if name == pendingName { pendingName = nil }
            if !naming { draft = pendingName ?? name }
        }
        .onChange(of: space?.icon) { _, icon in
            if icon == pendingIcon { pendingIcon = nil }
        }
        // Deleted or left, here or on another device.
        .onChange(of: space == nil && !graph.spaces.isEmpty) { _, gone in if gone { leave() } }
    }

    @ViewBuilder private func content(_ space: Space) -> some View {
        let own = owns(space)
        Section {
            header(space, own: own)
                .listRowBackground(Color.clear)
                .listRowInsets(EdgeInsets(top: 4, leading: 16, bottom: 4, trailing: 16))
        } footer: {
            if let error {
                Text(error).font(.notesSmall).foregroundStyle(Color.danger)
                    .frame(maxWidth: .infinity).multilineTextAlignment(.center)
            }
        }
        if own && picking {
            Section {
                SpaceIconPicker(selection: Binding(get: { shownIcon(space) }, set: { $0.map { pick($0, space) } }))
            }
        }
        ForEach(Self.blocks(Traits.rows(space.traits, registry: registry(space)))) { block in
            switch block {
            case .own(_, let section): section(space)
            case .rows(let rows):
                Section {
                    ForEach(rows) { TraitRowLabel(row: $0) }
                }
            }
        }
        if own {
            Section {
                Button("Delete Space…", role: .destructive) { confirmDelete = true }
                    .foregroundStyle(Color.danger)
                    .disabled(whyNotDeletable(space) != nil)
                    .confirmationDialog(
                        "Delete \(space.name)?", isPresented: $confirmDelete, titleVisibility: .visible
                    ) {
                        Button("Delete", role: .destructive) { delete(space) }
                    } message: {
                        Text("It has no notes: only the space itself goes.")
                    }
            } footer: {
                if let deleteError {
                    Text(deleteError).font(.notesSmall).foregroundStyle(Color.danger)
                } else if let why = whyNotDeletable(space) {
                    Footnote(why)
                }
            }
        }
    }

    /// Its icon, name, note count and glyphs. For its owner the name is a field styled as the title, and the
    /// icon opens the picker under it.
    private func header(_ space: Space, own: Bool) -> some View {
        VStack(spacing: 6) {
            if own {
                Button {
                    naming = false
                    withAnimation(reduceMotion ? nil : .snappy) { picking.toggle() }
                } label: {
                    SpaceIconImage(icon: shownIcon(space), size: 64)
                        .padding(6)
                        .background(picking ? Color.on : .clear, in: .rect(cornerRadius: 18, style: .continuous))
                        .overlay(alignment: .bottomTrailing) { IconBadge(open: picking) }
                        .contentShape(.rect)
                }
                .buttonStyle(PressScaleStyle(scale: 0.95))
                .accessibilityLabel("Change icon")
                .accessibilityValue(SpaceIcon.named(shownIcon(space)).name)
                .accessibilityHint(picking ? "Hides the icons." : "Shows the icons to choose from.")
                nameField(space)
            } else {
                SpaceIconImage(icon: space.icon, size: 64).padding(6)
                Text(space.name)
                    .font(.notesHeading)
                    .foregroundStyle(Color.ink)
                    .multilineTextAlignment(.center)
                    .accessibilityAddTraits(.isHeader)
            }
            HStack(spacing: 0) {
                Text(space.noteCountLabel)
                // Always there, even empty: the first glyph to turn on then arrives as later ones do.
                TraitGlyphs(traits: space.traits, arrives: true).padding(.leading, space.traits.isEmpty ? 0 : 8)
            }
            .font(.notesSmall)
            .foregroundStyle(Color.ink2)
            .accessibilityElement(children: .combine)
            // Words to read, not a control: without this the audit takes it for one too small to tap.
            .accessibilityAddTraits(.isStaticText)
        }
        .frame(maxWidth: .infinity)
    }

    /// The name, edited where it's shown: saved on Done or on leaving the field; an empty or unchanged one is
    /// put back.
    private func nameField(_ space: Space) -> some View {
        // Vertical, so a long name wraps as the title would; Return ends the edit instead of adding a line.
        TextField("Name", text: $draft, selection: $nameSelection, axis: .vertical)
            .font(.notesHeading)
            .foregroundStyle(Color.ink)
            .multilineTextAlignment(.center)
            .lineLimit(1...3)
            .submitLabel(.done)
            .textInputAutocapitalization(.words)
            .autocorrectionDisabled()
            .focused($naming)
            .padding(.horizontal, 10)
            .padding(.vertical, 2)
            .background(naming ? Color.field : .clear, in: .rect(cornerRadius: 10, style: .continuous))
            .overlay {
                if naming { RoundedRectangle(cornerRadius: 10, style: .continuous).strokeBorder(Color.hairStrong) }
            }
            .animation(.easeOut(duration: 0.15), value: naming)
            .accessibilityLabel("Name")
            .onChange(of: draft) { _, typed in
                if typed.contains(where: \.isNewline) {
                    draft = typed.filter { !$0.isNewline }
                    naming = false
                } else if typed.unicodeScalars.count > Spaces.maxName {
                    // As the server counts, by Unicode scalar: typing stops at the limit.
                    draft = String(String.UnicodeScalarView(typed.unicodeScalars.prefix(Spaces.maxName)))
                }
            }
            .onChange(of: naming) { _, focused in
                if focused {
                    // All of it, as a rename: typing replaces it; a second tap places the caret.
                    nameSelection = TextSelection(range: draft.startIndex..<draft.endIndex)
                    error = nil
                    withAnimation(reduceMotion ? nil : .snappy) { picking = false }
                } else {
                    rename(space)
                }
            }
    }

    private func rename(_ space: Space) {
        let current = pendingName ?? space.name
        switch SpaceName.check(draft, among: graph.spaces, renaming: space.id) {
        case .valid(let name) where name != current:
            draft = name
            pendingName = name
            Task {
                do {
                    try await graph.engine.spaces.update(space.id, name: name)
                    graph.scheduler.request()
                } catch {
                    pendingName = nil
                    if !naming { draft = space.name }
                    self.error = SpaceName.saveFailed
                }
            }
        case .invalid(let problem) where Spaces.normalize(draft) != nil:
            error = problem
            draft = current
        default:
            draft = current
        }
    }

    /// Saved as it's picked; the picker stays open to try another.
    private func pick(_ icon: String, _ space: Space) {
        guard icon != shownIcon(space) else { return }
        pendingIcon = icon
        error = nil
        let name = pendingName ?? space.name
        Task {
            do {
                try await graph.engine.spaces.update(space.id, name: name, icon: icon)
                graph.scheduler.request()
            } catch {
                if pendingIcon == icon { pendingIcon = nil }
                self.error = SpaceName.saveFailed
            }
        }
    }

    private func shownIcon(_ space: Space) -> String { pendingIcon ?? space.icon }

    /// The traits this app knows, with what Space info can do about sharing here (§7.9).
    private func registry(_ space: Space) -> [TraitKind] {
        Traits.registry.map { kind in
            guard kind.key == "shared" else { return kind }
            var shared = kind
            shared.section = { [people] in AnyView(PeopleSection(space: $0, people: people)) }
            shared.canTurnOn = canShare(space)
            return shared
        }
    }

    /// A trait's own section, or a run of traits without one, grouped as rows.
    enum Block: Identifiable {
        case own(String, @MainActor (Space) -> AnyView)
        case rows([TraitRow])

        var id: String {
            switch self {
            case .own(let key, _): key
            case .rows(let rows): rows.map(\.id).joined(separator: " ")
            }
        }
    }

    static func blocks(_ rows: [TraitRow]) -> [Block] {
        var blocks: [Block] = []
        for row in rows {
            let section: (@MainActor (Space) -> AnyView)? =
                switch row {
                case .on(_, let kind): kind?.section
                case .off(let kind): kind.section
                }
            if let section {
                blocks.append(.own(row.id, section))
            } else if case .rows(let run) = blocks.last {
                blocks[blocks.count - 1] = .rows(run + [row])
            } else {
                blocks.append(.rows([row]))
            }
        }
        return blocks
    }

    /// The owner shares; never the default space, and only through a server.
    private func canShare(_ space: Space) -> Bool {
        space.id != Spaces.defaultId && owns(space) && graph.account.isSignedIn
    }

    /// Whether People will list who to invite: for the owner of a space shared or that can be.
    private func wantsPeople(_ space: Space) -> Bool {
        space.shared?.isOwner ?? canShare(space)
    }

    /// Only a shared space's owner renames it, changes its icon or deletes it.
    private func owns(_ space: Space) -> Bool { space.shared?.isOwner ?? true }

    private func whyNotDeletable(_ space: Space) -> String? {
        if space.id == Spaces.defaultId { return "This space can be renamed but not deleted." }
        if space.noteCount > 0 { return "Move or delete its notes to delete this space." }
        return nil
    }

    private func delete(_ space: Space) {
        deleteError = nil
        Task {
            do {
                // False: notes arrived from another device since the count was shown.
                guard try await graph.engine.spaces.delete(space.id) else {
                    deleteError = "Notes arrived in this space from another device. Move or delete them first."
                    return
                }
                graph.scheduler.request()
                leave()
            } catch {
                deleteError = "The space couldn't be deleted on this iPhone. Try again."
            }
        }
    }

    private func leave() {
        guard !left else { return }
        left = true
        dismiss()
    }
}

/// On the owner's icon: it can be changed; open, it closes the picker.
private struct IconBadge: View {
    let open: Bool

    var body: some View {
        Image(systemName: open ? "chevron.up" : "pencil")
            .font(.system(size: 10, weight: .bold))
            .foregroundStyle(Color.ink2)
            .frame(width: 22, height: 22)
            .background(Color.notesBg, in: .circle)
            .overlay(Circle().strokeBorder(Color.hairStrong))
            .offset(x: 2, y: 2)
            .contentTransition(.symbolEffect(.replace))
            .accessibilityHidden(true)
    }
}

/// A trait without a section of its own: its glyph, label and summary, or its problem in the danger colour.
private struct TraitRowLabel: View {
    let row: TraitRow

    var body: some View {
        let (symbol, label, detail, problem, off, unknown) =
            switch row {
            case .on(let trait, let kind):
                (
                    Traits.symbol(trait.key), Traits.label(trait), trait.problem ?? trait.summary,
                    trait.problem != nil, false, kind == nil
                )
            case .off(let kind): (kind.symbol, kind.label, "Off", false, true, false)
            }
        return HStack(spacing: 12) {
            Image(systemName: symbol)
                .font(.system(size: 15, weight: .medium))
                .foregroundStyle(problem ? Color.danger : Color.ink2)
                .frame(width: 30, height: 30)
                .background(Color.on, in: .rect(cornerRadius: 8, style: .continuous))
                // Off, but it could be on: the glyph dims; the words stay readable.
                .opacity(off ? 0.45 : 1)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(label).font(.notesRowTitle).foregroundStyle(off ? Color.ink2 : Color.ink)
                if !detail.isEmpty {
                    Text(detail).font(.notesSmall).foregroundStyle(problem ? Color.danger : Color.ink2)
                }
                if unknown {
                    Text("Update the app to change this.").font(.notesSmall).foregroundStyle(Color.ink2)
                }
            }
        }
        .padding(.vertical, 2)
        .accessibilityElement(children: .combine)
    }
}
