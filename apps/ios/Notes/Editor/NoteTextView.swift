import NotesCore
import UIKit
import UniformTypeIdentifiers

/// What the formatting bar shows for the current selection.
struct FormatState: Equatable {
    var bold = false
    var italic = false
    var strike = false
    var link = false
    var heading = 0
    var list: ListKind?
    var quote = false
    var canIndent = false
    var canOutdent = false
    var inTitle = true
    /// Text is selected: the bar shows marks only.
    var selecting = false
    /// The tag the cursor is in, if any.
    var tag: TagAtCursor?
}

/// A tag around the cursor, from its # to its end. `query` is what's typed after
/// the # when the cursor is at the end of an unclosed tag: what to complete.
struct TagAtCursor: Equatable {
    var name: String
    var range: NSRange
    var query: String?
}

/// The note editor: one UITextView (TextKit 2) holding the whole note, title
/// line first, so selection, the keyboard, autocorrect, dictation, the edit
/// menu and VoiceOver are the platform's. Structure lives in attributes (see
/// EditorText.swift); after every change the `Normalizer` re-derives it.
///
/// Text is never replaced while the user types. `setDocument` is for loading a
/// note or applying a remote change, and keeps the selection by offset.
final class NoteTextView: UITextView, UITextViewDelegate, NSTextLayoutManagerDelegate {
    var onChange: (() -> Void)?
    var onFormatState: ((FormatState) -> Void)?
    var onLinkEdit: ((_ text: String, _ href: String?) -> Void)?
    /// A picture was pasted: its bytes, for the note to store and insert.
    var onPasteImage: ((Data) -> Void)?
    /// How far the view is from the window's left and right edges, for the bar above the keyboard
    /// (which spans the window) to sit under the note, not the list beside it.
    var onMargins: ((_ leading: CGFloat, _ trailing: CGFloat) -> Void)?
    private var margins: (leading: CGFloat, trailing: CGFloat)?

    private var style = EditorStyle()
    private var normalizing = false
    /// Where the change being made lands, for restyling only what it touched.
    private var pendingEdit: NSRange?
    /// Marks toggled with a collapsed selection, applied to what's typed next.
    private var pending: (location: Int, on: Set<Mark>, off: Set<Mark>)?
    private lazy var taskTaps = TaskTaps(view: self)

    init() {
        super.init(frame: .zero, textContainer: nil)
        textLayoutManager?.delegate = self
        delegate = self
        backgroundColor = .clear
        textContainer.lineFragmentPadding = 0
        textContainerInset = UIEdgeInsets(top: 12, left: 20, bottom: 40, right: 20)
        tintColor = Palette.tint
        autocapitalizationType = .sentences
        autocorrectionType = .default
        smartDashesType = .no
        smartQuotesType = .no
        alwaysBounceVertical = true
        keyboardDismissMode = .interactive
        // The editor sizes its own fonts from the text size, and restyles when it changes.
        registerForTraitChanges([UITraitPreferredContentSizeCategory.self]) { (view: NoteTextView, _) in
            view.style = EditorStyle(traits: view.traitCollection)
            view.normalize(restyleAll: true)
        }
        let tap = UITapGestureRecognizer(target: self, action: #selector(tapped(_:)))
        tap.delegate = taskTaps
        addGestureRecognizer(tap)
        NotificationCenter.default.addObserver(
            self, selector: #selector(imageReady), name: .notesImageReady, object: nil)
        setDocument(Document.forNote(title: "", body: .empty))
    }

    /// A picture loaded: lay out again, so its attachment draws it instead of the placeholder.
    @objc private func imageReady() {
        guard let layout = textLayoutManager else { return }
        layout.invalidateLayout(for: layout.documentRange)
        layout.textViewportLayoutController.layoutViewport()
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("not coded") }

    /// The widest the text runs: on a wide pane (an unfolded iPhone Duo, landscape) it's centred, not stretched.
    static let measure: CGFloat = 680

    override func layoutSubviews() {
        let side = max(20, ((bounds.width - Self.measure) / 2).rounded())
        if textContainerInset.left != side {
            textContainerInset.left = side
            textContainerInset.right = side
        }
        super.layoutSubviews()
        if let window {
            let frame = convert(bounds, to: window)
            let now = (leading: frame.minX.rounded(), trailing: (window.bounds.width - frame.maxX).rounded())
            if margins.map({ $0 != now }) ?? true {
                margins = now
                onMargins?(now.leading, now.trailing)
            }
        }
    }

    // MARK: content

    /// The note as a document: title line first. Cheap enough per save, not per keystroke.
    func document() -> Document { AttributedCodec.document(textStorage) }

    /// Loads `doc`. With `keepSelection`, the cursor stays at the same offset (a remote change).
    func setDocument(_ doc: Document, keepSelection: Bool = false) {
        let selection = selectedRange
        let text = AttributedCodec.attributed(doc)
        var sel = NSRange(location: 0, length: 0)
        Normalizer.normalize(text, selection: &sel)
        let blocks = Blocks.of(text)
        style.apply(text, blocks: blocks, indices: IndexSet(blocks.indices))
        applyTags(text)
        normalizing = true
        textStorage.setAttributedString(text)
        normalizing = false
        if keepSelection {
            selectedRange = clamp(selection)
        } else {
            selectedRange = NSRange(location: 0, length: 0)
            undoManager?.removeAllActions()
        }
        pending = nil
        updateTypingAttributes()
        publishState()
    }

    /// The text without the final "\n": the cursor never goes past it.
    private var limit: Int { max(textStorage.length - 1, 0) }

    private func clamp(_ r: NSRange) -> NSRange {
        let start = min(r.location, limit)
        return NSRange(location: start, length: min(NSMaxRange(r), limit) - start)
    }

    // MARK: TextKit

    func textLayoutManager(
        _ textLayoutManager: NSTextLayoutManager, textLayoutFragmentFor location: any NSTextLocation,
        in textElement: NSTextElement
    ) -> NSTextLayoutFragment {
        BlockLayoutFragment(textElement: textElement, range: textElement.elementRange)
    }

    // MARK: changes

    func textView(_ textView: UITextView, shouldChangeTextIn range: NSRange, replacementText text: String) -> Bool {
        if normalizing { return true }
        if text == "\n" && range.length == 0, enterOnEmpty(at: range.location) { return false }
        if text.isEmpty && range.length == 1, (textStorage.string as NSString).character(at: range.location) == 10 {
            if let handled = separatorDeletion(at: range.location) { return handled }
        }
        pendingEdit = NSRange(location: range.location, length: (text as NSString).length)
        return true
    }

    func textViewDidChange(_ textView: UITextView) {
        // Wait for a composition (Japanese, Chinese, dictation…) to finish.
        guard markedTextRange == nil else { return }
        normalize()
        onChange?()
        publishState()
    }

    func textViewDidChangeSelection(_ textView: UITextView) {
        if normalizing { return }
        let clamped = clamp(selectedRange)
        if clamped != selectedRange {
            selectedRange = clamped
            return
        }
        if let p = pending, selectedRange != NSRange(location: p.location, length: 0) { pending = nil }
        updateTypingAttributes()
        publishState()
    }

    /// Re-derives structure and style after a change.
    private func normalize(restyleAll: Bool = false) {
        normalizing = true
        defer { normalizing = false }
        var sel = selectedRange
        let storage = textStorage
        storage.beginEditing()
        let changed = Normalizer.normalize(storage, selection: &sel)
        let blocks = Blocks.of(storage)
        var restyle = changed
        if restyleAll || pendingEdit == nil {
            restyle = IndexSet(blocks.indices)
        } else if let edit = pendingEdit {
            let to = min(NSMaxRange(edit), max(storage.length - 1, 0))
            for k in Blocks.indices(in: blocks, from: min(edit.location, to), to: to) { restyle.insert(k) }
        }
        style.apply(storage, blocks: blocks, indices: restyle)
        applyTags(storage)
        storage.endEditing()
        pendingEdit = nil
        if sel != selectedRange { selectedRange = clamp(sel) }
        updateTypingAttributes()
    }

    /// `.notesTag` where the shared extractor finds tags; unchanged ones stay put.
    private func applyTags(_ text: NSMutableAttributedString) {
        let found = Normalizer.tags(text)
        var existing: [NSRange: String] = [:]
        text.enumerateAttribute(.notesTag, in: NSRange(location: 0, length: text.length)) { value, range, _ in
            if let name = value as? String { existing[range] = name }
        }
        var wanted: [NSRange: String] = [:]
        for (range, name) in found { wanted[range] = name }
        for (range, name) in existing where wanted[range] != name { text.removeAttribute(.notesTag, range: range) }
        for (range, name) in wanted where existing[range] != name {
            text.addAttribute(.notesTag, value: name, range: range)
        }
    }

    /// Typing continues the block the cursor is in, with the marks before it.
    private func updateTypingAttributes() {
        let at = min(selectedRange.location, limit)
        guard at < textStorage.length, let attr = Blocks.attr(textStorage, at: at) else { return }
        var marks = Marks.at(textStorage, at)
        if let p = pending, p.location == at { marks = marks.union(p.on).subtracting(p.off) }
        var run: [NSAttributedString.Key: Any] = [:]
        for mark in marks { run.merge(AttributedCodec.attributes(for: mark)) { $1 } }
        // No marks in the title.
        if attr.leaf == .title { run = [:] }
        var typing = style.attributes(attr, run: run)
        typing.merge(run) { $1 }
        typing[.notesBlock] = attr
        typingAttributes = typing
    }

    // MARK: input rules

    // The keyboard and programmatic input both come through here; the delegate
    // check below covers what doesn't (replacements by autocorrect, dictation).
    override func insertText(_ text: String) {
        let at = selectedRange
        if text == "\n" && at.length == 0 && enterOnEmpty(at: at.location) { return }
        pendingEdit = NSRange(location: at.location, length: (text as NSString).length)
        super.insertText(text)
    }

    override func deleteBackward() {
        let at = selectedRange
        if at.length == 0 && at.location > 0 && (textStorage.string as NSString).character(at: at.location - 1) == 10
            && separatorDeletion(at: at.location - 1) != nil
        {
            return
        }
        pendingEdit = NSRange(location: max(at.location - 1, 0), length: 0)
        super.deleteBackward()
    }

    /// Enter on an empty list item or quote line leaves it instead of adding a line.
    private func enterOnEmpty(at offset: Int) -> Bool {
        var edits = BlockEdits(textStorage)
        let k = edits.index(at: offset)
        let block = edits.blocks[k]
        guard k > 0, block.isEmpty, offset == block.start else { return false }
        guard edits.isItemStart(k) || (edits.paths[k].last.map { !isItem($0) } ?? false) else { return false }
        command { text in
            edits.outdent(k)
            edits.commit(text)
        }
        return true
    }

    /// Backspace at the start of a list item or quote removes the marker first, and
    /// a raw block never merges with the text beside it. Nil: let the deletion happen.
    private func separatorDeletion(at offset: Int) -> Bool? {
        var edits = BlockEdits(textStorage)
        guard let k = edits.blocks.firstIndex(where: { $0.start == offset + 1 }), k > 0 else { return nil }
        if isRaw(edits.leaves[k]) || isRaw(edits.leaves[k - 1]) {
            // Delete the raw block itself when the cursor sits right after it on an empty line.
            return false
        }
        let backspace = selectedRange == NSRange(location: offset + 1, length: 0)
        guard backspace, edits.isItemStart(k) || edits.isQuoteStart(k) else { return nil }
        command { text in
            edits.outdent(k)
            edits.commit(text)
        }
        return false
    }

    // MARK: commands

    func toggleBold() { toggleMark(.bold) }
    func toggleItalic() { toggleMark(.italic) }
    func toggleStrike() { toggleMark(.strike) }

    func toggleHeading(_ level: Int) { blockCommand { $0.toggleHeading($1, level: level) } }
    func toggleList(_ kind: ListKind) { blockCommand { $0.toggleList($1, kind: kind) } }
    func toggleQuote() { blockCommand { $0.toggleQuote($1) } }
    func indent() { blockCommand { edits, sel in for k in sel { edits.indent(k) } } }
    func outdent() { blockCommand { edits, sel in for k in sel { edits.outdent(k) } } }

    /// Links the selection, or inserts `label` (default: the address) as a link at the cursor.
    func setLink(_ href: String, label: String? = nil) {
        guard let target = Self.normalizeHref(href) else { return }
        commandSelecting { text in
            var range = self.selectedRange
            if range.length == 0, let (_, linked) = Marks.link(text, at: range.location) { range = linked }
            if range.length == 0 {
                let shown = label.flatMap { $0.trimmingCharacters(in: .whitespaces).isEmpty ? nil : $0 } ?? target
                text.insert(NSAttributedString(string: shown, attributes: self.typingAttributes), at: range.location)
                range.length = (shown as NSString).length
            }
            Marks.remove(text, .link(href: ""), from: range.location, to: NSMaxRange(range))
            Marks.add(text, .link(href: target), from: range.location, to: NSMaxRange(range))
            return NSRange(location: NSMaxRange(range), length: 0)
        }
    }

    func removeLink() {
        guard let (_, range) = Marks.link(textStorage, at: selectedRange.location) else { return }
        command { text in Marks.remove(text, .link(href: ""), from: range.location, to: NSMaxRange(range)) }
    }

    /// Replaces the tag being typed at `range` with `name`, closed if it needs it, and a space.
    func insertTag(_ range: NSRange, name: String) {
        commandSelecting { text in
            let written = tagText(name)
            let s = text.string as NSString
            let spaced = NSMaxRange(range) < s.length && s.character(at: NSMaxRange(range)) == 32
            let replacement = NSAttributedString(
                string: spaced ? written : written + " ", attributes: self.typingAttributes)
            text.replaceCharacters(in: range, with: replacement)
            return NSRange(location: range.location + (written as NSString).length + 1, length: 0)
        }
    }

    func insertHardBreak() {
        insertText(lineSeparator)
    }

    /// The selected text and any link on it, for the link sheet.
    /// Adds a picture (its Markdown, `ImageRef.markdown`) on a line of its own:
    /// the empty line the cursor is on, otherwise a new line after the cursor's.
    func insertImage(_ markdown: String) {
        commandSelecting { text in
            let blocks = Blocks.of(text)
            guard !blocks.isEmpty else { return nil }
            let block = blocks[Blocks.index(in: blocks, at: self.selectedRange.location)]
            let run = NSMutableAttributedString()
            let at: Int
            if block.isEmpty && block.attr?.leaf != .title {
                at = block.start
            } else {
                at = block.contentEnd
                run.append(NSAttributedString(string: "\n"))
            }
            run.append(NSAttributedString(attachment: RawAttachment(source: markdown, isBlock: false)))
            if let attr = block.attr {
                run.addAttribute(.notesBlock, value: attr, range: NSRange(location: 0, length: run.length))
            }
            text.insert(run, at: at)
            return NSRange(location: at + run.length, length: 0)
        }
    }

    /// The pictures this note shows, by id: its picture runs, as `replaceImages` rewrites them. Not
    /// the Markdown's pattern, which a link to `notesd:image/…` also matches: a lock change would
    /// then try to move an image the note doesn't show, and stop if it isn't on this iPhone.
    func imageIds() -> Set<String> {
        var ids = Set<String>()
        textStorage.enumerateAttribute(.attachment, in: NSRange(location: 0, length: textStorage.length)) {
            value, _, _ in
            if let id = (value as? RawAttachment)?.picture?.id { ids.insert(id) }
        }
        return ids
    }

    /// After a lock change moved the pictures (§10.1): points their runs at the new ids, old id → new id.
    func replaceImages(_ moved: [String: String]) {
        guard !moved.isEmpty else { return }
        command { text in
            var found: [(NSRange, String)] = []
            text.enumerateAttribute(.attachment, in: NSRange(location: 0, length: text.length)) { value, range, _ in
                guard let raw = value as? RawAttachment, let id = raw.picture?.id, let next = moved[id] else { return }
                found.append((range, raw.source.replacing(id, with: next)))
            }
            for (range, source) in found {
                text.addAttribute(.attachment, value: RawAttachment(source: source, isBlock: false), range: range)
            }
        }
    }

    func linkContext() -> (text: String, href: String?) {
        let link = Marks.link(textStorage, at: selectedRange.location)
        return ((textStorage.string as NSString).substring(with: selectedRange), link?.0.href)
    }

    private func toggleMark(_ mark: Mark) {
        let range = selectedRange
        if range.length == 0 {
            var p: (location: Int, on: Set<Mark>, off: Set<Mark>) =
                pending?.location == range.location ? pending! : (range.location, [], [])
            let active = Marks.at(textStorage, range.location).union(p.on).subtracting(p.off)
            if active.contains(mark) {
                p.on.remove(mark)
                p.off.insert(mark)
            } else {
                p.off.remove(mark)
                p.on.insert(mark)
            }
            pending = p
            updateTypingAttributes()
            publishState()
            return
        }
        command { text in
            if Marks.covers(text, mark, from: range.location, to: NSMaxRange(range)) {
                Marks.remove(text, mark, from: range.location, to: NSMaxRange(range))
            } else {
                Marks.add(text, mark, from: range.location, to: NSMaxRange(range))
            }
        }
    }

    private func blockCommand(_ change: @escaping (inout BlockEdits, [Int]) -> Void) {
        command { text in
            var edits = BlockEdits(text)
            change(&edits, edits.selected(from: self.selectedRange.location, to: NSMaxRange(self.selectedRange)))
            edits.commit(text)
        }
    }

    /// One undoable change made by the editor itself: formatting, a checkbox, a list rule.
    private func command(_ change: (NSMutableAttributedString) -> Void) {
        commandSelecting { text in
            change(text)
            return nil
        }
    }

    /// `command` whose change says where the selection goes.
    private func commandSelecting(_ change: (NSMutableAttributedString) -> NSRange?) {
        let before = Snapshot(text: NSAttributedString(attributedString: textStorage), selection: selectedRange)
        normalizing = true
        textStorage.beginEditing()
        let selection = change(textStorage)
        textStorage.endEditing()
        normalizing = false
        if let selection { selectedRange = clamp(selection) }
        normalize(restyleAll: true)
        registerUndo(before)
        onChange?()
        publishState()
    }

    // MARK: undo

    private struct Snapshot {
        var text: NSAttributedString
        var selection: NSRange
    }

    /// The platform undoes typing; the editor's own changes restore a snapshot, so
    /// structure comes back with the text.
    private func registerUndo(_ snapshot: Snapshot) {
        guard let undoManager else { return }
        undoManager.registerUndo(withTarget: self) { view in
            let current = Snapshot(
                text: NSAttributedString(attributedString: view.textStorage), selection: view.selectedRange)
            view.restore(snapshot)
            view.registerUndo(current)
        }
    }

    private func restore(_ snapshot: Snapshot) {
        normalizing = true
        textStorage.setAttributedString(snapshot.text)
        normalizing = false
        selectedRange = clamp(snapshot.selection)
        pending = nil
        normalize(restyleAll: true)
        onChange?()
        publishState()
    }

    // MARK: state

    private func publishState() {
        guard let onFormatState else { return }
        let start = min(selectedRange.location, limit)
        let end = min(NSMaxRange(selectedRange), limit)
        let edits = BlockEdits(textStorage)
        guard !edits.blocks.isEmpty else { return }
        let k = edits.index(at: start)
        let path = edits.paths[k]
        let marks: Set<Mark>
        if start == end {
            var at = Marks.at(textStorage, start)
            if let p = pending, p.location == start { at = at.union(p.on).subtracting(p.off) }
            marks = at
        } else {
            marks = Set(
                [Mark.bold, .italic, .strike, .link(href: "")].filter {
                    Marks.covers(textStorage, $0, from: start, to: end)
                })
        }
        let sel = edits.selected(from: start, to: end)
        var heading = 0
        if case .heading(let level) = edits.leaves[k] { heading = level }
        var list: ListKind?
        if case .item(let item)? = path.last { list = item.kind }
        onFormatState(
            FormatState(
                bold: marks.contains(.bold), italic: marks.contains(.italic), strike: marks.contains(.strike),
                link: marks.contains { if case .link = $0 { true } else { false } }, heading: heading, list: list,
                quote: path.contains { !isItem($0) }, canIndent: sel.contains(where: edits.canIndent),
                canOutdent: sel.contains(where: edits.canOutdent), inTitle: k == 0, selecting: start != end,
                tag: start == end ? tagAt(start) : nil))
    }

    private func tagAt(_ cursor: Int) -> TagAtCursor? {
        guard cursor > 0 else { return nil }
        var range = NSRange()
        guard
            let name = textStorage.attribute(
                .notesTag, at: cursor - 1, longestEffectiveRange: &range,
                in: NSRange(location: 0, length: textStorage.length)) as? String
        else { return nil }
        let s = textStorage.string as NSString
        let atEnd = cursor == NSMaxRange(range) && s.character(at: cursor - 1) != 35
        let query = atEnd ? s.substring(with: NSRange(location: range.location + 1, length: range.length - 1)) : nil
        return TagAtCursor(name: name, range: range, query: query)
    }

    // MARK: keys, menus, pasteboard

    override var keyCommands: [UIKeyCommand]? {
        [
            UIKeyCommand(title: "Bold", action: #selector(boldKey), input: "b", modifierFlags: .command),
            UIKeyCommand(title: "Italic", action: #selector(italicKey), input: "i", modifierFlags: .command),
            UIKeyCommand(
                title: "Strikethrough", action: #selector(strikeKey), input: "x", modifierFlags: [.command, .shift]),
            UIKeyCommand(title: "Indent", action: #selector(indentKey), input: "\t"),
            UIKeyCommand(title: "Outdent", action: #selector(outdentKey), input: "\t", modifierFlags: .shift),
            UIKeyCommand(title: "Line Break", action: #selector(hardBreakKey), input: "\r", modifierFlags: .shift),
        ]
    }

    @objc private func boldKey() { toggleBold() }
    @objc private func italicKey() { toggleItalic() }
    @objc private func strikeKey() { toggleStrike() }
    @objc private func indentKey() { indent() }
    @objc private func outdentKey() { outdent() }
    @objc private func hardBreakKey() { insertHardBreak() }

    /// Pasted text is text: Markdown or rich text from the clipboard isn't parsed into formatting.
    override func paste(_ sender: Any?) {
        let board = UIPasteboard.general
        if !board.hasStrings, board.hasImages, let onPasteImage, let data = Self.imageData(board) {
            return onPasteImage(data)
        }
        guard let string = board.string else { return super.paste(sender) }
        replace(selectedTextRange ?? UITextRange(), withText: string)
    }

    override func canPerformAction(_ action: Selector, withSender sender: Any?) -> Bool {
        if action == #selector(paste(_:)), onPasteImage != nil, UIPasteboard.general.hasImages { return true }
        return super.canPerformAction(action, withSender: sender)
    }

    /// The pasted picture's bytes as copied (a GIF stays animated), or re-encoded if only an image is there.
    private static func imageData(_ board: UIPasteboard) -> Data? {
        for type in [UTType.gif, .png, .jpeg, .heic, .webP] {
            if let data = board.data(forPasteboardType: type.identifier) { return data }
        }
        return board.image?.pngData()
    }

    /// Copies plain text: hard breaks as new lines, raw runs as the Markdown they hold.
    override func copy(_ sender: Any?) {
        guard selectedRange.length > 0 else { return }
        UIPasteboard.general.string = plainText(in: selectedRange)
    }

    override func cut(_ sender: Any?) {
        guard selectedRange.length > 0 else { return }
        copy(sender)
        replace(selectedTextRange ?? UITextRange(), withText: "")
    }

    private func plainText(in range: NSRange) -> String {
        var out = ""
        textStorage.enumerateAttribute(.attachment, in: range) { value, run, _ in
            if let raw = value as? RawAttachment {
                out += String(repeating: raw.source, count: run.length)
            } else {
                out += (textStorage.string as NSString).substring(with: run).replacingOccurrences(
                    of: lineSeparator, with: "\n")
            }
        }
        return out
    }

    override func editMenu(for textRange: UITextRange, suggestedActions: [UIMenuElement]) -> UIMenu? {
        let format = UIMenu(
            title: "Format",
            children: [
                UIAction(title: "Bold", image: UIImage(systemName: "bold")) { [weak self] _ in self?.toggleBold() },
                UIAction(title: "Italic", image: UIImage(systemName: "italic")) { [weak self] _ in self?.toggleItalic()
                },
                UIAction(title: "Strikethrough", image: UIImage(systemName: "strikethrough")) { [weak self] _ in
                    self?.toggleStrike()
                },
                UIAction(title: "Link…", image: UIImage(systemName: "link")) { [weak self] _ in
                    guard let self else { return }
                    let context = self.linkContext()
                    self.onLinkEdit?(context.text, context.href)
                },
            ])
        return UIMenu(children: suggestedActions + [format])
    }

    // MARK: taps

    /// The task box tap's delegate. Not the text view: it's already its own scroll and selection gestures'
    /// delegate, and answering for the tap there would answer for those too (no scrolling off a task box).
    private final class TaskTaps: NSObject, UIGestureRecognizerDelegate {
        weak var view: NoteTextView?

        init(view: NoteTextView) { self.view = view }

        func gestureRecognizer(_ g: UIGestureRecognizer, shouldReceive touch: UITouch) -> Bool {
            guard let view else { return false }
            return view.taskBox(at: touch.location(in: view)) != nil
        }
    }

    @objc private func tapped(_ g: UITapGestureRecognizer) {
        guard let offset = taskBox(at: g.location(in: self)) else { return }
        UISelectionFeedbackGenerator().selectionChanged()
        command { text in
            var edits = BlockEdits(text)
            edits.toggleChecked(edits.index(at: offset))
            edits.commit(text)
        }
    }

    /// The start of the task item whose box is under `point`, if any. Targets are at least 44pt.
    fileprivate func taskBox(at point: CGPoint) -> Int? {
        guard let manager = textLayoutManager, let content = manager.textContentManager else { return nil }
        let p = CGPoint(x: point.x - textContainerInset.left, y: point.y - textContainerInset.top)
        guard let fragment = manager.textLayoutFragment(for: p),
            let paragraph = fragment.textElement as? NSTextParagraph, paragraph.attributedString.length > 0,
            let attr = paragraph.attributedString.attribute(.notesBlock, at: 0, effectiveRange: nil) as? BlockAttr,
            case .box? = attr.marker, let line = fragment.textLineFragments.first
        else { return nil }
        let frame = fragment.layoutFragmentFrame
        let centerX = style.margin(attr) - style.itemIndent + style.itemIndent * 0.42
        let lineTop = frame.minY + line.typographicBounds.minY
        let centerY = lineTop + line.typographicBounds.height / 2
        guard abs(p.x - centerX) <= 22, abs(p.y - centerY) <= 22 else { return nil }
        return (content as? NSTextContentStorage)?.offset(
            from: content.documentRange.location, to: fragment.rangeInElement.location)
    }

    static func normalizeHref(_ href: String) -> String? {
        let trimmed = href.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty { return nil }
        if trimmed.firstMatch(of: /^(?i)(https?:|mailto:)/) != nil { return trimmed }
        if trimmed.firstMatch(of: /^(?i)[a-z][a-z0-9+.\-]*:/) != nil { return nil }
        return trimmed.contains("@") && !trimmed.contains("/") ? "mailto:\(trimmed)" : "https://\(trimmed)"
    }
}
