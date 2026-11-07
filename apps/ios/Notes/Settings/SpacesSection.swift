import NotesCore
import SwiftUI

/// Spaces (§7.7): each opens its Space info (§7.8), where it's renamed or deleted; and New Space. The sidebar's
/// switcher can make one too.
struct SpacesSection: View {
    @Environment(AppGraph.self) private var graph
    /// A new space was made and switched to.
    let onCreated: () -> Void
    @State private var creating = false

    var body: some View {
        Section {
            ForEach(graph.spaces) { space in
                NavigationLink(value: Route.spaceInfo(space.id)) {
                    SpaceLabel(space: space, iconSize: 28, detail: space.noteCountLabel)
                }
            }
            Button("New Space") { creating = true }
                .sheet(isPresented: $creating) {
                    SpaceSheet { id in
                        creating = false
                        graph.switchSpace(id)
                        onCreated()
                    }
                }
        } footer: {
            Text(
                "Keep separate contexts, like Work and Personal, apart. Each space has its own notes and tags, "
                    + "and new notes go to the one you’re in."
            )
            .font(.notesSmall).foregroundStyle(Color.ink2)
        }
    }
}

/// A space's name and icon: a new one, or `editing`.
struct SpaceSheet: View {
    @Environment(AppGraph.self) private var graph
    @Environment(\.dismiss) private var dismiss
    @Environment(\.spaceTheme) private var shownTheme
    var editing: Space?
    let onDone: (String) -> Void

    @State private var name = ""
    @State private var icon: String?
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
                    // Above the grid, which runs past the screen's bottom: there it would go unseen.
                    if let iconError { Text(iconError).font(.notesSmall).foregroundStyle(Color.errInk) }
                    SpaceIconPicker(selection: $icon)
                        .onChange(of: icon) { iconError = nil }
                } header: {
                    Text("Icon")
                }
            }
            .font(.notesBody)
            .scrollContentBackground(.hidden)
            .background(Color.notesBg)
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
        // The icon picked brings its colours: the sheet shows them before the space has them.
        .environment(\.spaceTheme, icon.map(SpaceThemes.key) ?? shownTheme)
        .onAppear {
            if let editing {
                name = editing.name
                icon = editing.icon
            } else {
                nameFocused = true
            }
        }
    }

    private func save() {
        guard let n = Spaces.normalize(name) else {
            error = "Enter a name."
            return
        }
        // Counted as the server counts: by Unicode scalar.
        if n.unicodeScalars.count > Spaces.maxName {
            error = "At most \(Spaces.maxName) characters."
            return
        }
        if graph.spaces.contains(where: { $0.id != editing?.id && $0.name.lowercased() == n.lowercased() }) {
            error = "Another space has this name."
            return
        }
        guard let icon else {
            iconError = "Choose an icon."
            return
        }
        busy = true
        Task {
            do {
                let id: String
                if let editing {
                    try await graph.engine.spaces.update(editing.id, name: n, icon: icon)
                    id = editing.id
                } else {
                    id = try await graph.engine.spaces.create(n, icon: icon)
                }
                graph.scheduler.request()
                onDone(id)
            } catch {
                // The sheet stays, with what was typed, to try again.
                self.error = "The space couldn’t be saved on this iPhone. Try again."
                busy = false
            }
        }
    }
}
