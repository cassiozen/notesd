import NotesCore
import SwiftUI

/// Spaces (§7.7): create, rename and delete them. The only place to make one,
/// so people who never need spaces never see them anywhere else.
struct SpacesSection: View {
    @Environment(AppGraph.self) private var graph
    @Environment(\.dismiss) private var dismiss
    @State private var creating = false
    @State private var renaming: Space?
    @State private var newName = ""

    var body: some View {
        Section {
            ForEach(graph.spaces) { space in
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(space.name).font(.notesRowTitle).foregroundStyle(Color.ink).lineLimit(1)
                        Text(detail(space)).font(.notesSmall).foregroundStyle(Color.ink2)
                    }
                    Spacer()
                    Menu {
                        Button("Rename", systemImage: "pencil") {
                            newName = space.name
                            renaming = space
                        }
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
                    NewSpaceSheet(first: graph.spaces.count == 1 ? graph.spaces.first : nil, taken: taken) { id in
                        creating = false
                        graph.switchSpace(id)
                        // Back to the list, to show the new space.
                        dismiss()
                    }
                }
                .alert(
                    "Rename Space",
                    isPresented: Binding(get: { renaming != nil }, set: { if !$0 { renaming = nil } }),
                    presenting: renaming
                ) { space in
                    TextField("Name", text: $newName)
                    Button("Cancel", role: .cancel) {}
                    Button("Rename") { rename(space) }
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

    private func rename(_ space: Space) {
        guard let name = Spaces.normalize(newName), name.count <= Spaces.maxName,
            !taken(name, except: space.id)
        else { return }
        Task {
            try? await graph.engine.spaces.rename(space.id, to: name)
            graph.scheduler.request()
        }
    }

    private func delete(_ space: Space) {
        Task {
            if (try? await graph.engine.spaces.delete(space.id)) == true { graph.scheduler.request() }
        }
    }
}

/// A new space. The second one also names the first: "Notes" alone was fine,
/// beside "Work" it reads better as "Personal".
private struct NewSpaceSheet: View {
    @Environment(AppGraph.self) private var graph
    @Environment(\.dismiss) private var dismiss
    let first: Space?
    let taken: (String, String?) -> Bool
    let onCreated: (String) -> Void

    @State private var name = ""
    @State private var firstName = ""
    @State private var error: String?
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
            .navigationTitle("New Space")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel", role: .cancel) { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Create", action: create).disabled(busy)
                }
            }
        }
        .onAppear {
            if let first { firstName = first.name == Spaces.defaultName ? "Personal" : first.name }
            nameFocused = true
        }
    }

    private func create() {
        guard let n = Spaces.normalize(name) else {
            error = "Enter a name."
            return
        }
        let f = first.flatMap { _ in Spaces.normalize(firstName) }
        if n.count > Spaces.maxName {
            error = "At most \(Spaces.maxName) characters."
            return
        }
        if taken(n, first?.id) || f?.lowercased() == n.lowercased() {
            error = "Another space has this name."
            return
        }
        busy = true
        Task {
            if let first, let f, f != first.name { try? await graph.engine.spaces.rename(first.id, to: f) }
            guard let id = try? await graph.engine.spaces.create(n) else {
                busy = false
                return
            }
            graph.scheduler.request()
            onCreated(id)
        }
    }
}
