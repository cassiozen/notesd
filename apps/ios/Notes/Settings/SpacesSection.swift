import NotesCore
import SwiftUI

/// Spaces (§7.7): each opens its Space info (§7.8), where it's renamed or deleted; and New Space. The sidebar's
/// switcher can make one too.
struct SpacesSection: View {
    @Environment(AppGraph.self) private var graph
    @Environment(Navigator.self) private var nav
    /// A new space was made and switched to.
    let onCreated: () -> Void
    @State private var creating = false

    var body: some View {
        Section {
            ForEach(graph.spaces) { space in
                Button {
                    nav.openSpaceInfo(space.id)
                } label: {
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

/// What a space can be named: the server's rules (`Spaces.normalize`, `Spaces.maxName`) and, kept here, no two
/// spaces with the same name.
enum SpaceName: Equatable {
    case valid(String)
    case invalid(String)

    /// `typed`, as the space would be named, among `spaces`; `id` is the space being renamed.
    static func check(_ typed: String, among spaces: [Space], renaming id: String? = nil) -> SpaceName {
        guard let name = Spaces.normalize(typed) else { return .invalid("Enter a name.") }
        // Counted as the server counts: by Unicode scalar.
        if name.unicodeScalars.count > Spaces.maxName { return .invalid("At most \(Spaces.maxName) characters.") }
        if spaces.contains(where: { $0.id != id && $0.name.lowercased() == name.lowercased() }) {
            return .invalid("Another space has this name.")
        }
        return .valid(name)
    }

    static let saveFailed = "The space couldn’t be saved on this iPhone. Try again."
}

/// A new space's name and icon.
struct SpaceSheet: View {
    @Environment(AppGraph.self) private var graph
    @Environment(\.dismiss) private var dismiss
    @Environment(\.spaceTheme) private var shownTheme
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
            .navigationTitle("New Space")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel", role: .cancel) { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Create", action: save).disabled(busy)
                }
            }
        }
        // The icon picked brings its colours: the sheet shows them before the space has them.
        .environment(\.spaceTheme, icon.map(SpaceThemes.key) ?? shownTheme)
        .onAppear { nameFocused = true }
    }

    private func save() {
        let n: String
        switch SpaceName.check(name, among: graph.spaces) {
        case .valid(let valid): n = valid
        case .invalid(let problem):
            error = problem
            return
        }
        guard let icon else {
            iconError = "Choose an icon."
            return
        }
        busy = true
        Task {
            do {
                let id = try await graph.engine.spaces.create(n, icon: icon)
                graph.scheduler.request()
                onDone(id)
            } catch {
                // The sheet stays, with what was typed, to try again.
                self.error = SpaceName.saveFailed
                busy = false
            }
        }
    }
}
