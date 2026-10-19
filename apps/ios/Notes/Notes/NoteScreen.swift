import NotesCore
import PhotosUI
import SwiftUI

/// One note: the editor with its bar, the conflicted-copy banner, and the
/// locked placeholder. Saves on a pause, when the screen goes, and when the
/// app leaves the foreground.
struct NoteScreen: View {
    @Environment(AppGraph.self) private var graph
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.dismiss) private var dismiss
    @Binding var path: [Route]

    let id: String
    let isNew: Bool
    @State private var model: EditorModel
    @State private var controller = EditorController()
    @State private var confirmDelete = false
    @State private var emailing = false
    @State private var picked: PhotosPickerItem?
    /// Lock note was chosen, and the session needs a password created or entered first.
    @State private var lockSheet: LockPrompt?
    /// The lock password was typed in the sheet: once it's gone, Face ID unlock is offered.
    @State private var typedPassword = false
    /// Locked with this screen's Lock now: the placeholder doesn't ask for Face ID straight back.
    @State private var lockedHere = false

    init(
        store: NoteStore, images: ImageStore? = nil, locks: LockSession? = nil, id: String, startTag: String?,
        isNew: Bool, initial: Note? = nil, spaceId: String = Spaces.defaultId, path: Binding<[Route]>
    ) {
        self.id = id
        self.isNew = isNew
        _path = path
        _model = State(
            initialValue: EditorModel(
                store: store, images: images, locks: locks, id: id, startTag: startTag, initial: initial, fresh: isNew,
                spaceId: spaceId))
    }

    var body: some View {
        VStack(spacing: 0) {
            if let original = model.original {
                Button {
                    path.append(.note(id: original.id))
                } label: {
                    HStack {
                        Text("Conflicted copy of “\(original.title.isEmpty ? "Untitled" : original.title)”")
                            .font(.notesSmall)
                        Spacer()
                        Image(systemName: "arrow.up.right")
                    }
                    .foregroundStyle(Color.warnInk)
                    .padding(12)
                    .background(Color.warnBg, in: .rect(cornerRadius: 12))
                }
                .buttonStyle(.plain)
                .padding(.horizontal, 16)
                .padding(.top, 8)
            }
            if model.gone {
                Text("This note was deleted on another device. Anything you hadn’t synced is kept as a recovered copy.")
                    .font(.notesSmall)
                    .foregroundStyle(Color.errInk)
                    .padding(12)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(Color.errBg, in: .rect(cornerRadius: 12))
                    .padding(.horizontal, 16)
                    .padding(.top, 8)
            }
            if let problem = model.saveProblem {
                problemBanner(problem) { model.saveProblem = nil }
            }
            if let problem = model.imageProblem {
                problemBanner(problem) { model.imageProblem = nil }
            }
            if model.needsUnlock || model.unreadable {
                LockedNoteView(
                    title: model.title, unreadable: model.unreadable, locks: graph.locks,
                    promptBiometrics: !lockedHere)
            } else if model.document != nil {
                EditorView(model: model, controller: controller, tags: model.tags, autofocus: isNew)
                    .ignoresSafeArea(.container, edges: .bottom)
            } else {
                Spacer()
            }
        }
        .background(Color.notesBg)
        .overlay {
            if model.locked && scenePhase != .active { PrivacyCover() }
        }
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if model.locked && !model.needsUnlock {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Lock now", systemImage: "lock") {
                        lockedHere = true
                        saveNow()
                        Task { await graph.locks.lock() }
                    }
                }
                ToolbarSpacer(.fixed, placement: .topBarTrailing)
            }
            if !model.needsUnlock && !model.unreadable && model.document != nil {
                ToolbarItemGroup(placement: .topBarTrailing) {
                    Button("Undo", systemImage: "arrow.uturn.backward") { controller.undo() }
                        .disabled(!controller.canUndo)
                    Button("Redo", systemImage: "arrow.uturn.forward") { controller.redo() }
                        .disabled(!controller.canRedo)
                }
                ToolbarSpacer(.fixed, placement: .topBarTrailing)
            }
            ToolbarItem(placement: .topBarTrailing) {
                Menu {
                    if !model.needsUnlock, let doc = controller.document() {
                        ShareLink(item: share(doc)) { Label("Share", systemImage: "square.and.arrow.up") }
                    }
                    if canEmail {
                        Button("Email…", systemImage: "envelope") {
                            saveNow()
                            emailing = true
                        }
                    }
                    if canChangeLock {
                        if model.locked {
                            Button("Remove lock", systemImage: "lock.open") { setLocked(false) }
                        } else {
                            Button("Lock note", systemImage: "lock") { setLocked(true) }
                        }
                    }
                    if graph.spaces.count > 1 && model.status != .blank {
                        Menu("Move to Space", systemImage: "arrow.right.square") {
                            ForEach(graph.spaces.filter { $0.id != model.spaceId }) { space in
                                Button(space.name) { move(to: space.id) }
                            }
                        }
                    }
                    Button("Delete", systemImage: "trash", role: .destructive) { confirmDelete = true }
                } label: {
                    Label("More", systemImage: "ellipsis")
                }
            }
        }
        .confirmationDialog("Delete this note?", isPresented: $confirmDelete, titleVisibility: .visible) {
            Button("Delete", role: .destructive) {
                Task {
                    await model.delete()
                    dismiss()
                }
            }
        }
        .sheet(isPresented: $emailing) {
            EmailSheet(from: graph.email.settings?.from) { to in
                saveNow()
                await model.flushed()
                return await graph.mailer.send(id, to: to)
            }
        }
        .task { await model.run() }
        .task { await graph.email.refresh() }
        .sheet(item: $lockSheet) {
            if typedPassword { graph.biometrics.offer() }
            typedPassword = false
        } content: { prompt in
            LockSheet(locks: graph.locks, exists: prompt.exists) { typed in
                typedPassword = typed
                setLocked(true)
            }
        }
        .photosPicker(isPresented: pickerShown, selection: $picked, matching: .images, preferredItemEncoding: .current)
        .onChange(of: picked) { _, item in
            guard let item else { return }
            picked = nil
            Task {
                guard let data = try? await item.loadTransferable(type: Data.self) else {
                    model.imageProblem = "That picture couldn’t be read."
                    return
                }
                await addImage(data)
            }
        }
        .onAppear {
            controller.onPasteImage = { data in Task { await addImage(data) } }
            model.currentDocument = { [controller] in controller.document() }
        }
        .sheet(isPresented: linkSheetShown) {
            LinkSheet(controller: controller)
        }
        .onDisappear { saveNow() }
        .onChange(of: model.needsUnlock) { _, locked in if !locked { lockedHere = false } }
        .onChange(of: scenePhase) { _, phase in
            if phase != .active { saveNow() }
        }
    }

    private func problemBanner(_ problem: String, onDismiss: @escaping () -> Void) -> some View {
        Button(action: onDismiss) {
            HStack(alignment: .top) {
                Text(problem).font(.notesSmall).frame(maxWidth: .infinity, alignment: .leading)
                Image(systemName: "xmark").accessibilityLabel("Dismiss")
            }
            .foregroundStyle(Color.errInk)
            .padding(12)
            .background(Color.errBg, in: .rect(cornerRadius: 12))
        }
        .buttonStyle(.plain)
        .padding(.horizontal, 16)
        .padding(.top, 8)
    }

    /// The server sends email, so it needs a connection and settings, and it refuses locked notes.
    private var canEmail: Bool {
        guard case .signedIn = graph.account, graph.email.settings?.configured == true else { return false }
        return !model.locked && model.status != .blank
    }

    /// Locking needs a lock password, which needs the server unless one was made already.
    private var canChangeLock: Bool {
        guard !model.needsUnlock, !model.unreadable, model.document != nil else { return false }
        if model.locked { return true }
        if case .signedIn = graph.account { return true }
        return graph.lockState != .none
    }

    /// Locks or unlocks the note. The pictures go with the lock (§10.1): stored
    /// again encrypted, or plain, before the note is. Applied only once all
    /// have moved: a failure partway leaves the note as it was.
    private func setLocked(_ on: Bool) {
        // The session itself, not the observed state: right after the sheet unlocks, that lags.
        let now = graph.locks.state.value
        if on && now != .unlocked {
            lockSheet = LockPrompt(exists: now != .none)
            return
        }
        Task {
            var pending = controller.view?.imageIds() ?? []
            var done = Set<String>()
            var moved: [String: String] = [:]
            while !pending.isEmpty {
                guard let next = await model.moveImages(pending, locked: on) else { return }
                moved.merge(next) { $1 }
                done.formUnion(pending)
                // Pictures added while those moved go with the lock too.
                pending = (controller.view?.imageIds() ?? []).subtracting(done).subtracting(moved.values)
            }
            controller.run { $0.replaceImages(moved) }
            guard let doc = controller.document() else { return }
            model.setLocked(doc, on, superseded: Set(moved.keys))
        }
    }

    private var pickerShown: Binding<Bool> {
        Binding(get: { controller.pickingImage }, set: { controller.pickingImage = $0 })
    }

    private func addImage(_ data: Data) async {
        guard let markdown = await model.addImage(data) else { return }
        controller.run { $0.insertImage(markdown) }
    }

    private var linkSheetShown: Binding<Bool> {
        Binding(get: { controller.linkSheet != nil }, set: { if !$0 { controller.linkSheet = nil } })
    }

    /// Moves the note to another space (§7.7) and leaves it: it's no longer in the list behind.
    private func move(to space: String) {
        saveNow()
        Task {
            guard await model.move(to: space) else { return }
            graph.notice = "Moved to \(graph.spaceName(space))"
            dismiss()
        }
    }

    private func saveNow() {
        if let doc = controller.document() { model.save(doc) }
    }

    /// The note as Markdown, its title as the heading.
    private func share(_ doc: NotesCore.Document) -> String {
        let title = (doc.title ?? "").trimmed
        let body = Markdown.serialize(doc)
        return title.isEmpty ? body : "# \(title)\n\n\(body)"
    }
}

/// Add, change or remove the link on the selection.
private struct LinkSheet: View {
    let controller: EditorController
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL
    @State private var href = ""
    @State private var label = ""

    var body: some View {
        NavigationStack {
            Form {
                TextField("Address", text: $href)
                    .keyboardType(.URL)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .font(.notesMono)
                if controller.linkSheet?.text.isEmpty ?? true, controller.linkSheet?.href == nil {
                    TextField("Text to show (optional)", text: $label)
                }
                if let existing = controller.linkSheet?.href {
                    Section {
                        Button("Open link", systemImage: "safari") {
                            if let url = URL(string: existing) { openURL(url) }
                        }
                        Button("Remove link", systemImage: "link.badge.minus", role: .destructive) {
                            controller.run { $0.removeLink() }
                            dismiss()
                        }
                    }
                }
            }
            .navigationTitle(controller.linkSheet?.href == nil ? "Add link" : "Edit link")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") {
                        controller.run { $0.setLink(href, label: label) }
                        dismiss()
                    }
                    .disabled(NoteTextView.normalizeHref(href) == nil)
                }
            }
            .onAppear { href = controller.linkSheet?.href ?? "" }
        }
        .presentationDetents([.medium])
    }
}
