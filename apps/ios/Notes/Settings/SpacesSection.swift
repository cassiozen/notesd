import NotesCore
import SwiftUI

/// Spaces (§7.7): create, edit and delete them. The only place to make one,
/// so people who never need spaces never see them anywhere else.
struct SpacesSection: View {
    @Environment(AppGraph.self) private var graph
    @Environment(\.dismiss) private var dismiss
    @State private var creating = false
    @State private var editing: Space?

    var body: some View {
        Section {
            ForEach(graph.spaces) { space in
                HStack(spacing: 12) {
                    SpaceIconImage(icon: space.icon, size: 28)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(space.name).font(.notesRowTitle).foregroundStyle(Color.ink).lineLimit(1)
                        Text(detail(space)).font(.notesSmall).foregroundStyle(Color.ink2)
                    }
                    Spacer()
                    Menu {
                        Button("Edit", systemImage: "pencil") { editing = space }
                        if space.id != Spaces.defaultId {
                            Button("Delete", systemImage: "trash", role: .destructive) { delete(space) }
                                .disabled(space.noteCount > 0)
                        }
                    } label: {
                        Image(systemName: "ellipsis.circle").frame(width: 44, height: 44).contentShape(.rect)
                    }
                    .accessibilityLabel("Edit \(space.name)")
                }
                .accessibilityElement(children: .combine)
            }
            Button("New Space") { creating = true }
                .sheet(isPresented: $creating) {
                    SpaceSheet(first: graph.spaces.count == 1 ? graph.spaces.first : nil, taken: taken) { id in
                        creating = false
                        graph.switchSpace(id)
                        // Back to the list, to show the new space.
                        dismiss()
                    }
                }
                .sheet(item: $editing) { space in
                    SpaceSheet(editing: space, taken: taken) { _ in editing = nil }
                }
        } header: {
            SectionHeader("Spaces")
        } footer: {
            Text(
                "Keep separate contexts, like Work and Personal, apart. Each space has its own notes and tags, "
                    + "and new notes go to the one you’re in."
            )
            .font(.notesSmall).foregroundStyle(Color.ink2)
        }
    }

    private func detail(_ space: Space) -> String {
        let count = space.noteCount == 1 ? "1 note" : "\(space.noteCount) notes"
        if space.id == Spaces.defaultId { return count + " · Can be renamed, not deleted" }
        if space.noteCount > 0 { return count + " · Move or delete its notes to delete it" }
        return count
    }

    private func taken(_ name: String, except: String?) -> Bool {
        graph.spaces.contains { $0.id != except && $0.name.lowercased() == name.lowercased() }
    }

    private func delete(_ space: Space) {
        Task {
            if (try? await graph.engine.spaces.delete(space.id)) == true { graph.scheduler.request() }
        }
    }
}

/// A space's name and icon: a new one, or `editing`. The second space also
/// names the first: "Notes" alone was fine, beside "Work" it reads better as
/// "Personal".
private struct SpaceSheet: View {
    @Environment(AppGraph.self) private var graph
    @Environment(\.dismiss) private var dismiss
    var editing: Space?
    var first: Space?
    let taken: (String, String?) -> Bool
    let onDone: (String) -> Void

    @State private var name = ""
    @State private var icon: String?
    @State private var firstName = ""
    @State private var error: String?
    @State private var iconError: String?
    @State private var busy = false
    @FocusState private var nameFocused: Bool

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Name", text: $name, prompt: Text(verbatim: "Work"))
                        .focused($nameFocused)
                        .accessibilityLabel("Name")
                        .onChange(of: name) { error = nil }
                    if let error { Text(error).font(.notesSmall).foregroundStyle(Color.errInk) }
                }
                Section {
                    SpaceIconPicker(selection: $icon)
                        .onChange(of: icon) { iconError = nil }
                    if let iconError { Text(iconError).font(.notesSmall).foregroundStyle(Color.errInk) }
                } header: {
                    Text("Icon")
                }
                if let first {
                    Section {
                        TextField("Rename “\(first.name)” to", text: $firstName)
                            .accessibilityLabel("Rename “\(first.name)” to")
                    } header: {
                        Text("Rename “\(first.name)” to")
                    } footer: {
                        Text("The space your notes are in now.").font(.notesSmall)
                    }
                }
            }
            .font(.notesBody)
            .navigationTitle(editing == nil ? "New Space" : "Edit Space")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel", role: .cancel) { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(editing == nil ? "Create" : "Save", action: save).disabled(busy)
                }
            }
        }
        .onAppear {
            if let editing {
                name = editing.name
                icon = editing.icon.isEmpty ? SpaceIcon.defaultKey : editing.icon
            } else {
                nameFocused = true
            }
            if let first { firstName = first.name == Spaces.defaultName ? "Personal" : first.name }
        }
    }

    private func save() {
        guard let n = Spaces.normalize(name) else {
            error = "Enter a name."
            return
        }
        let f = first.flatMap { _ in Spaces.normalize(firstName) }
        if n.count > Spaces.maxName {
            error = "At most \(Spaces.maxName) characters."
            return
        }
        if taken(n, editing?.id ?? first?.id) || f?.lowercased() == n.lowercased() {
            error = "Another space has this name."
            return
        }
        guard let icon else {
            iconError = "Choose an icon."
            return
        }
        busy = true
        Task {
            if let editing {
                try? await graph.engine.spaces.update(editing.id, name: n, icon: icon)
                graph.scheduler.request()
                onDone(editing.id)
                return
            }
            if let first, let f, f != first.name { try? await graph.engine.spaces.update(first.id, name: f) }
            guard let id = try? await graph.engine.spaces.create(n, icon: icon) else {
                busy = false
                return
            }
            graph.scheduler.request()
            onDone(id)
        }
    }
}
