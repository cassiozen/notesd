import NotesCore
import SwiftUI

/// A space's one page of space-level settings (§7.8): its icon, name and note count; its traits; then its name and
/// icon to change, and Delete.
struct SpaceInfoView: View {
    let id: String
    @Environment(AppGraph.self) private var graph
    @Environment(Navigator.self) private var nav
    @Environment(\.dismiss) private var dismiss
    @State private var editing = false
    @State private var left = false
    @State private var deleteError: String?

    var body: some View {
        let space = graph.spaces.first { $0.id == id }
        Form {
            if let space { content(space) }
        }
        .settingsForm(title: "Space Info")
        .navigationBarTitleDisplayMode(.inline)
        .sheet(isPresented: $editing) {
            if let space { SpaceSheet(editing: space) { _ in editing = false } }
        }
        // Deleted, here or on another device.
        .onChange(of: space == nil && !graph.spaces.isEmpty) { _, gone in if gone { leave() } }
    }

    @ViewBuilder private func content(_ space: Space) -> some View {
        Section {
            VStack(spacing: 6) {
                SpaceIconImage(icon: space.icon, size: 64)
                Text(space.name).font(.notesHeading).foregroundStyle(Color.ink).multilineTextAlignment(.center)
                Text(space.noteCountLabel)
                    .font(.notesSmall).foregroundStyle(Color.ink2)
            }
            .frame(maxWidth: .infinity)
            .listRowBackground(Color.clear)
            .accessibilityElement(children: .combine)
        }
        let rows = Traits.rows(space.traits)
        if !rows.isEmpty {
            Section {
                ForEach(rows) { row in traitRow(row, space) }
            } footer: {
                let unknown = rows.compactMap { if case .on(let t, .none) = $0 { Traits.label(t) } else { nil } }
                if !unknown.isEmpty {
                    Text("Update the app to change \(ListFormatter.localizedString(byJoining: unknown)).")
                        .font(.notesSmall).foregroundStyle(Color.ink2)
                }
            }
        }
        Section {
            Button("Name and Icon") { editing = true }
            Button("Delete Space", role: .destructive) { delete(space) }
                .disabled(whyNotDeletable(space) != nil)
        } footer: {
            if let deleteError {
                Text(deleteError).font(.notesSmall).foregroundStyle(Color.danger)
            } else if let why = whyNotDeletable(space) {
                Text(why).font(.notesSmall).foregroundStyle(Color.ink2)
            }
        }
    }

    @ViewBuilder private func traitRow(_ row: TraitRow, _ space: Space) -> some View {
        switch row {
        case .on(let trait, let kind):
            let label = TraitRowLabel(
                symbol: Traits.symbol(trait.key), label: Traits.label(trait), detail: trait.problem ?? trait.summary,
                problem: trait.problem != nil)
            if let settings = kind?.settings {
                NavigationLink {
                    settings(space)
                } label: {
                    label
                }
            } else {
                label
            }
        case .off(let kind):
            HStack {
                TraitRowLabel(symbol: kind.symbol, label: kind.label, detail: "Off", off: true)
                Spacer()
                Button("Turn On") { kind.turnOn?(space) }
                    .buttonStyle(.bordered)
                    .accessibilityLabel("Turn On \(kind.label)")
            }
        }
    }

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

    /// Back where it was opened from: Settings › Spaces, or the list when the sidebar opened it.
    private func leave() {
        guard !left else { return }
        left = true
        if nav.path.first == .spaceInfo(id) { nav.closeAll() } else { dismiss() }
    }
}

/// A trait's glyph, label and one line about it: its summary, or its problem in the danger colour.
private struct TraitRowLabel: View {
    let symbol: String
    let label: String
    let detail: String
    var problem = false
    /// Off, but it could be on: the glyph dims; the words stay readable.
    var off = false

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: symbol)
                .font(.system(size: 15, weight: .medium))
                .foregroundStyle(problem ? Color.danger : Color.ink2)
                .frame(width: 30, height: 30)
                .background(Color.on, in: .rect(cornerRadius: 8, style: .continuous))
                .opacity(off ? 0.45 : 1)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(label).font(.notesRowTitle).foregroundStyle(off ? Color.ink2 : Color.ink)
                Text(detail).font(.notesSmall).foregroundStyle(problem ? Color.danger : Color.ink2)
            }
        }
        .padding(.vertical, 2)
        .accessibilityElement(children: .combine)
    }
}
