import NotesCore
import SwiftUI
import UIKit

/// Commands from SwiftUI (the formatting bar, sheets) to the text view.
@MainActor
@Observable
final class EditorController {
    fileprivate(set) weak var view: NoteTextView?
    var format = FormatState()
    /// The link sheet's starting text and address, while it's open.
    var linkSheet: (text: String, href: String?)?
    /// The photo picker is open, to add a picture.
    var pickingImage = false
    /// The text view's distance from the window's edges, which the formatting bar keeps to.
    var barLeading: CGFloat = 0
    var barTrailing: CGFloat = 0
    /// Pasted picture bytes, for the screen to store and insert.
    var onPasteImage: ((Data) -> Void)?
    private(set) var canUndo = false
    private(set) var canRedo = false

    func undo() {
        view?.undoManager?.undo()
        updateUndo()
    }

    func redo() {
        view?.undoManager?.redo()
        updateUndo()
    }

    /// After anything that changes what can be undone.
    func updateUndo() {
        let undo = view?.undoManager?.canUndo ?? false
        let redo = view?.undoManager?.canRedo ?? false
        if undo != canUndo { canUndo = undo }
        if redo != canRedo { canRedo = redo }
    }

    func run(_ command: (NoteTextView) -> Void) {
        if let view { command(view) }
    }

    func document() -> NotesCore.Document? { view?.document() }

    func focus() { view?.becomeFirstResponder() }

    func editLink() {
        guard let view else { return }
        let context = view.linkContext()
        linkSheet = context
    }
}

/// The note editor in SwiftUI. The text view owns the text while it's open;
/// the model gets documents on save, and pushes remote changes in.
struct EditorView: UIViewRepresentable {
    let model: EditorModel
    let controller: EditorController
    let tags: [TagCount]
    var autofocus = false

    func makeUIView(context: Context) -> NoteTextView {
        let view = NoteTextView()
        controller.view = view
        if let doc = model.document { view.setDocument(doc) }
        context.coordinator.seenIncoming = model.incoming?.version ?? 0
        view.onChange = { [weak model, weak view, weak controller] in
            guard let model, let view else { return }
            controller?.updateUndo()
            model.edited()
            context.coordinator.scheduleSave(model, view)
        }
        view.onFormatState = { [weak controller] state in controller?.format = state }
        view.onLinkEdit = { [weak controller] text, href in controller?.linkSheet = (text, href) }
        view.onPasteImage = { [weak controller] data in controller?.onPasteImage?(data) }
        view.onMargins = { [weak controller] leading, trailing in
            controller?.barLeading = leading
            controller?.barTrailing = trailing
        }
        let bar = UIHostingController(rootView: FormattingBar(controller: controller, tags: tags))
        bar.view.backgroundColor = .clear
        bar.sizingOptions = [.intrinsicContentSize]
        bar.view.frame = CGRect(x: 0, y: 0, width: 320, height: 52)
        bar.view.autoresizingMask = [.flexibleWidth]
        context.coordinator.bar = bar
        view.inputAccessoryView = bar.view
        if autofocus { DispatchQueue.main.async { view.becomeFirstResponder() } }
        context.coordinator.watchUndo(controller)
        return view
    }

    func updateUIView(_ view: NoteTextView, context: Context) {
        context.coordinator.bar?.rootView = FormattingBar(controller: controller, tags: tags)
        if let incoming = model.incoming, incoming.version != context.coordinator.seenIncoming {
            context.coordinator.seenIncoming = incoming.version
            view.setDocument(incoming.document, keepSelection: true)
            controller.updateUndo()
        }
    }

    func makeCoordinator() -> Coordinator { Coordinator() }

    @MainActor
    final class Coordinator {
        var seenIncoming = 0
        var bar: UIHostingController<FormattingBar>?
        private var pending: Task<Void, Never>?
        nonisolated(unsafe) private var observers: [NSObjectProtocol] = []

        deinit {
            for observer in observers { NotificationCenter.default.removeObserver(observer) }
        }

        /// Keeps the bar's Undo and Redo in step with the text view's undo stack.
        func watchUndo(_ controller: EditorController) {
            let names: [Notification.Name] = [
                .NSUndoManagerDidCloseUndoGroup, .NSUndoManagerDidUndoChange, .NSUndoManagerDidRedoChange,
                .NSUndoManagerWillCloseUndoGroup,
            ]
            for name in names {
                observers.append(
                    NotificationCenter.default.addObserver(forName: name, object: nil, queue: .main) {
                        [weak controller] _ in
                        MainActor.assumeIsolated { controller?.updateUndo() }
                    })
            }
        }

        /// Saves 0.7 s after the last change.
        func scheduleSave(_ model: EditorModel, _ view: NoteTextView) {
            pending?.cancel()
            pending = Task { [weak model, weak view] in
                try? await Task.sleep(for: .milliseconds(700))
                guard !Task.isCancelled, let model, let view else { return }
                model.save(view.document())
            }
        }
    }
}
