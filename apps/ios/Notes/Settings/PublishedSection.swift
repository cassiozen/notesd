import NotesCore
import SwiftUI

/// Space info's section for publishing (§7.10): off, its owner publishes; on, its address (opened, shared, or moved,
/// asked first) and Stop Publishing. A member sees where it is. Custom CSS is the web app's.
struct PublishedSection: View {
    let space: Space
    @Environment(AppGraph.self) private var graph
    @State private var settings: PublishSettings?
    /// What's being done ("publish", "handle", "stop"): one thing at a time.
    @State private var busy: String?
    @State private var error: String?
    /// The handle as typed; the settings' while it isn't being changed.
    @State private var draft = ""
    @FocusState private var editing: Bool
    /// A new handle, waiting for a yes: links to the old address stop working.
    @State private var asking: String?
    @State private var confirmStop = false

    private var trait: SpaceTrait? { space.traits.first { $0.key == "published" } }
    private var owner: Bool { space.shared?.isOwner ?? true }

    var body: some View {
        Section {
            if let trait {
                if owner, let settings { address(settings) } else { Text(trait.summary).font(.notesBody) }
            } else {
                Text(
                    "Publish \(space.name) as a website anyone can read: each note with a title and some text is a "
                        + "post, with pages for its tags and a search. Its address comes from the space’s name, and "
                        + "you can change it after."
                )
                .font(.notesBody)
                .foregroundStyle(Color.ink2)
                .padding(.vertical, 4)
                Button {
                    let space = space
                    perform("publish") { await graph.publish(space) }
                } label: {
                    HStack {
                        Text("Publish")
                        if busy == "publish" {
                            Spacer()
                            ProgressView()
                        }
                    }
                }
                .disabled(busy != nil)
            }
        } header: {
            SectionHeader("Published")
        } footer: {
            VStack(alignment: .leading, spacing: 4) {
                if let problem = trait?.problem { Text(problem).foregroundStyle(Color.danger) }
                if let error { Text(error).foregroundStyle(Color.danger) }
            }
            .font(.notesSmall)
        }
        // Read again when the trait changes: published, moved or taken down here or on another device.
        .task(id: trait?.summary) {
            guard trait != nil, owner else { return }
            switch await graph.publishSettings(space.id) {
            case .success(let read):
                settings = read
                if !editing, let read { draft = read.handle }
            case .failure(let failure): error = failure.message
            }
        }
        if trait != nil, owner {
            Section {
                Button("Stop Publishing…", role: .destructive) { confirmStop = true }
                    .foregroundStyle(Color.danger)
                    .disabled(busy != nil)
                    .confirmationDialog(
                        "Stop publishing “\(space.name)”?", isPresented: $confirmStop, titleVisibility: .visible
                    ) {
                        Button("Stop Publishing", role: .destructive) { stop() }
                    } message: {
                        Text("The site goes offline. Its address stays this space’s, for when you publish it again.")
                    }
            }
        }
    }

    /// Where the site is: its handle edited in place, then Open and Share.
    @ViewBuilder private func address(_ settings: PublishSettings) -> some View {
        HStack(spacing: 0) {
            Text("\(Publishing.host(settings))/@")
                .foregroundStyle(Color.ink3)
                .lineLimit(1)
                .truncationMode(.head)
                // A text field takes all the width it's offered: the host keeps its own, the handle the rest.
                .layoutPriority(1)
            TextField("handle", text: $draft)
                .frame(minWidth: 96)
                .accessibilityLabel("Address")
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .keyboardType(.URL)
                .submitLabel(.done)
                .focused($editing)
                .disabled(busy != nil)
                .onChange(of: draft) { _, typed in
                    if typed != typed.lowercased() { draft = typed.lowercased() }
                }
                .onSubmit { ask(settings) }
                .onChange(of: editing) { _, now in if !now { ask(settings) } }
        }
        .font(.notesMono)
        .confirmationDialog(
            "Move the site to \(Publishing.host(settings))/@\(asking ?? "")?",
            isPresented: Binding(get: { asking != nil }, set: { if !$0 { cancelAsk(settings) } }),
            titleVisibility: .visible
        ) {
            Button("Change Address") { change() }
            Button("Cancel", role: .cancel) { cancelAsk(settings) }
        } message: {
            Text("Links to the current address will stop working.")
        }
        if let url = URL(string: settings.url) {
            Link(destination: url) { Label("Open", systemImage: "safari") }
            ShareLink(item: url) { Label("Share", systemImage: "square.and.arrow.up") }
        }
    }

    private func ask(_ settings: PublishSettings) {
        let handle = draft.trimmingCharacters(in: .whitespaces)
        if handle.isEmpty || handle == settings.handle {
            draft = settings.handle
        } else if asking == nil {
            asking = handle
        }
    }

    private func cancelAsk(_ settings: PublishSettings) {
        asking = nil
        if busy != "handle" { draft = settings.handle }
    }

    private func change() {
        guard let handle = asking else { return }
        asking = nil
        let id = space.id
        perform("handle") { await graph.changeHandle(id, to: handle) }
    }

    private func stop() {
        let id = space.id
        busy = "stop"
        error = nil
        Task {
            error = await graph.stopPublishing(id)
            busy = nil
            if error == nil { settings = nil }
        }
    }

    private func perform(_ key: String, _ call: @escaping () async -> Result<PublishSettings, SharingError>) {
        busy = key
        error = nil
        Task {
            switch await call() {
            case .success(let now):
                settings = now
                draft = now.handle
            // The handle stays as typed, to correct.
            case .failure(let failure): error = failure.message
            }
            busy = nil
        }
    }
}
