import NotesCore
import UIKit

// The editor holds the whole note in one attributed string, title line first,
// one paragraph per block. Structure lives in attributes the editor owns:
//
// - every block's characters and the "\n" that ends it carry one `BlockAttr`
//   (its container path, type and list marker). The text always ends with a
//   "\n", so every block, the last one too, has a character to carry it;
// - inline marks are `.notesBold`, `.notesItalic`, `.notesStrike` and
//   `.notesLink`. Fonts and colours are derived from them and never read back;
// - a hard break is U+2028, which TextKit keeps inside the paragraph;
// - text outside the dialect (code, tables, images…) is one `RawAttachment`
//   character holding its exact source, so it's atomic and read-only;
// - `.notesTag` marks #tags; derived, it never reaches the document.

nonisolated extension NSAttributedString.Key {
    static let notesBlock = NSAttributedString.Key("notesd.block")
    static let notesBold = NSAttributedString.Key("notesd.bold")
    static let notesItalic = NSAttributedString.Key("notesd.italic")
    static let notesStrike = NSAttributedString.Key("notesd.strike")
    static let notesLink = NSAttributedString.Key("notesd.link")
    static let notesTag = NSAttributedString.Key("notesd.tag")
}

/// What the margin of an item's first line shows.
nonisolated enum Marker: Hashable {
    case bullet(level: Int)
    case number(Int)
    case box(checked: Bool)
}

/// One block's structure. Compared by identity: the normaliser tells a block
/// that kept its attribute from one split off it (both halves of an Enter
/// carry the same object) by which block claims it first.
nonisolated final class BlockAttr: NSObject {
    let path: [Container]
    let leaf: Leaf
    let marker: Marker?
    /// How many of its innermost quotes start and end on this line: separate
    /// quotes get space between them, and their bars stop at their own lines.
    let quoteEdges: QuoteEdges
    /// The space between this line and the next.
    let gap: Gap

    init(
        path: [Container], leaf: Leaf, marker: Marker? = nil, quoteEdges: QuoteEdges = .init(),
        gap: Gap = .paragraph
    ) {
        self.path = path
        self.leaf = leaf
        self.marker = marker
        self.quoteEdges = quoteEdges
        self.gap = gap
    }

    func sameAs(path: [Container], leaf: Leaf, marker: Marker?, quoteEdges: QuoteEdges, gap: Gap) -> Bool {
        self.path == path && self.leaf == leaf && self.marker == marker && self.quoteEdges == quoteEdges
            && self.gap == gap
    }

    var item: Container.Item? {
        if case .item(let item)? = path.last { return item }
        return nil
    }

    var quoted: Bool { path.contains { if case .quote = $0 { true } else { false } } }

    /// In a checked task (or nested under one): shown dimmed.
    var dim: Bool { path.contains { if case .item(let i) = $0 { i.kind == .task && i.checked } else { false } } }

    var listDepth: Int { path.filter { if case .item = $0 { true } else { false } }.count }
}

/// The innermost `starts` quotes of a line's path begin on it; the innermost `ends` end on it.
nonisolated struct QuoteEdges: Equatable {
    var starts = 0
    var ends = 0
}

/// How far a line is from the next, as the web spaces its blocks (editor.module.css): items of
/// one list sit closest, so the list reads as one; quotes and raw blocks stand furthest apart.
nonisolated enum Gap: Equatable {
    case item
    case paragraph
    case block
}

/// A link's target. Equal by value, so adjacent runs of one link merge.
nonisolated final class LinkAttr: NSObject {
    let href: String
    let title: String?

    init(href: String, title: String?) {
        self.href = href
        self.title = title
    }

    override func isEqual(_ object: Any?) -> Bool {
        guard let other = object as? LinkAttr else { return false }
        return href == other.href && title == other.title
    }

    override var hash: Int { href.hashValue ^ (title?.hashValue ?? 0) }
}

nonisolated let attachmentChar = "\u{FFFC}"
nonisolated let lineSeparator = "\u{2028}"
nonisolated private let newline: UInt16 = 10

/// A block's range in the text: [start, end) includes its trailing "\n"; `contentEnd` doesn't.
nonisolated struct BlockRange {
    var start: Int
    var contentEnd: Int
    var end: Int { contentEnd + 1 }
    var attr: BlockAttr?

    var content: NSRange { NSRange(location: start, length: contentEnd - start) }
    var whole: NSRange { NSRange(location: start, length: end - start) }
    var isEmpty: Bool { start == contentEnd }
}

nonisolated enum Blocks {
    /// Every block, in order. The text ends with "\n"; a text without one is read as if it did.
    static func of(_ text: NSAttributedString) -> [BlockRange] {
        let s = text.string as NSString
        var out: [BlockRange] = []
        var start = 0
        for i in 0..<s.length where s.character(at: i) == newline {
            out.append(BlockRange(start: start, contentEnd: i, attr: attr(text, at: i)))
            start = i + 1
        }
        if start < s.length || out.isEmpty {
            out.append(
                BlockRange(start: start, contentEnd: s.length, attr: start < s.length ? attr(text, at: start) : nil))
        }
        return out
    }

    /// The block holding `offset` (a cursor at a block's end is in it).
    static func index(in blocks: [BlockRange], at offset: Int) -> Int {
        blocks.firstIndex { offset <= $0.contentEnd } ?? max(blocks.count - 1, 0)
    }

    /// The blocks touched by [from, to].
    static func indices(in blocks: [BlockRange], from: Int, to: Int) -> [Int] {
        let first = index(in: blocks, at: from)
        let last = index(in: blocks, at: max(from, to))
        return Array(first...max(first, last))
    }

    static func attr(_ text: NSAttributedString, at i: Int) -> BlockAttr? {
        guard i < text.length else { return nil }
        return text.attribute(.notesBlock, at: i, effectiveRange: nil) as? BlockAttr
    }
}

/// `Document` ⇄ the editor's attributed string: one paragraph per block.
nonisolated enum AttributedCodec {
    /// Text and structure for `doc`. Markers and styles come from the normaliser.
    static func attributed(_ doc: Document) -> NSMutableAttributedString {
        let out = NSMutableAttributedString()
        for line in doc.toLines() {
            let block = BlockAttr(path: line.path, leaf: line.leaf)
            let start = out.length
            if case .raw(let source) = line.leaf {
                out.append(NSAttributedString(attachment: RawAttachment(source: source, isBlock: true)))
            } else {
                for node in line.content { out.append(inline(node)) }
            }
            out.append(NSAttributedString(string: "\n"))
            out.addAttribute(.notesBlock, value: block, range: NSRange(location: start, length: out.length - start))
        }
        return out
    }

    private static func inline(_ node: Inline) -> NSAttributedString {
        let piece: NSMutableAttributedString
        switch node {
        case .text(let text, _): piece = NSMutableAttributedString(string: text)
        case .hardBreak: piece = NSMutableAttributedString(string: lineSeparator)
        case .rawInline(let source, _):
            piece = NSMutableAttributedString(attachment: RawAttachment(source: source, isBlock: false))
        }
        let all = NSRange(location: 0, length: piece.length)
        for mark in node.marks { piece.addAttributes(attributes(for: mark), range: all) }
        return piece
    }

    static func attributes(for mark: Mark) -> [NSAttributedString.Key: Any] {
        switch mark {
        case .bold: [.notesBold: true]
        case .italic: [.notesItalic: true]
        case .strike: [.notesStrike: true]
        case .link(let href, let title): [.notesLink: LinkAttr(href: href, title: title)]
        }
    }

    static func key(for mark: Mark) -> NSAttributedString.Key {
        switch mark {
        case .bold: .notesBold
        case .italic: .notesItalic
        case .strike: .notesStrike
        case .link: .notesLink
        }
    }

    /// The marks on a run's attributes, in canonical order.
    static func marks(_ attributes: [NSAttributedString.Key: Any]) -> [Mark] {
        var marks: [Mark] = []
        if let link = attributes[.notesLink] as? LinkAttr { marks.append(.link(href: link.href, title: link.title)) }
        if attributes[.notesBold] != nil { marks.append(.bold) }
        if attributes[.notesItalic] != nil { marks.append(.italic) }
        if attributes[.notesStrike] != nil { marks.append(.strike) }
        return Mark.setOf(marks)
    }

    /// The document the text represents. Expects normalised text.
    static func document(_ text: NSAttributedString) -> Document {
        let lines = Blocks.of(text).map { block -> Line in
            let attr = block.attr ?? BlockAttr(path: [], leaf: .paragraph)
            switch attr.leaf {
            case .raw:
                let raw = block.isEmpty ? nil : text.attribute(.attachment, at: block.start, effectiveRange: nil)
                let source = (raw as? RawAttachment)?.source ?? ""
                return Line(path: attr.path, leaf: .raw(source: source))
            case .title:
                let title = (text.string as NSString).substring(with: block.content)
                    .replacingOccurrences(of: lineSeparator, with: " ")
                    .replacingOccurrences(of: attachmentChar, with: "")
                return Line(path: [], leaf: .title, content: [Inline.text(title)].normalized())
            default:
                return Line(path: attr.path, leaf: attr.leaf, content: inlines(text, block.content))
            }
        }
        return linesToDocument(lines)
    }

    /// The inline content of `range`: one run per stretch of equal marks, breaks and raw runs on their own.
    static func inlines(_ text: NSAttributedString, _ range: NSRange) -> [Inline] {
        var out: [Inline] = []
        let s = text.string as NSString
        text.enumerateAttributes(in: range) { attributes, run, _ in
            let marks = marks(attributes)
            if let raw = attributes[.attachment] as? RawAttachment {
                // Each attachment character is its own run.
                for _ in 0..<run.length { out.append(.rawInline(raw.source, marks: marks)) }
                return
            }
            var pending = ""
            for piece in s.substring(with: run).unicodeScalars {
                if piece == "\u{2028}" {
                    if !pending.isEmpty { out.append(.text(pending, marks: marks)) }
                    pending = ""
                    out.append(.hardBreak(marks: marks))
                } else if piece != "\u{FFFC}" {
                    pending.unicodeScalars.append(piece)
                }
            }
            if !pending.isEmpty { out.append(.text(pending, marks: marks)) }
        }
        return out.normalized()
    }
}

/// Adding, removing and reading inline marks over ranges of the text.
nonisolated enum Marks {
    /// [from, to) cut to the text of markable blocks (not the title, not raw blocks, no separators).
    static func pieces(_ text: NSAttributedString, from: Int, to: Int) -> [NSRange] {
        Blocks.of(text).dropFirst().compactMap { b in
            if case .raw = b.attr?.leaf { return nil }
            let a = max(from, b.start)
            let e = min(to, b.contentEnd)
            return a < e ? NSRange(location: a, length: e - a) : nil
        }
    }

    /// Whether every markable character in [from, to) has `mark` (any link, for links).
    static func covers(_ text: NSAttributedString, _ mark: Mark, from: Int, to: Int) -> Bool {
        let pieces = pieces(text, from: from, to: to)
        if pieces.isEmpty { return false }
        let key = AttributedCodec.key(for: mark)
        return pieces.allSatisfy { piece in
            var all = true
            text.enumerateAttribute(key, in: piece) { value, _, stop in
                if value == nil {
                    all = false
                    stop.pointee = true
                }
            }
            return all
        }
    }

    /// Marks active for text typed at `offset`: bold, italic and strike continue at their end; links don't.
    static func at(_ text: NSAttributedString, _ offset: Int) -> Set<Mark> {
        var out = Set<Mark>()
        if offset > 0 && offset - 1 < text.length {
            let before = text.attributes(at: offset - 1, effectiveRange: nil)
            for mark in AttributedCodec.marks(before) {
                if case .link = mark {
                    // Inside a link only: typing at its end doesn't grow it.
                    if offset < text.length,
                        let after = text.attribute(.notesLink, at: offset, effectiveRange: nil)
                            as? LinkAttr, after.isEqual(before[.notesLink])
                    {
                        out.insert(mark)
                    }
                } else {
                    out.insert(mark)
                }
            }
        }
        // Not past a block's start: a new line doesn't inherit the end of the line above.
        if offset > 0, (text.string as NSString).character(at: offset - 1) == newline { return [] }
        return out
    }

    static func add(_ text: NSMutableAttributedString, _ mark: Mark, from: Int, to: Int) {
        for piece in pieces(text, from: from, to: to) {
            if case .link = mark { text.removeAttribute(.notesLink, range: piece) }
            text.addAttributes(AttributedCodec.attributes(for: mark), range: piece)
        }
    }

    static func remove(_ text: NSMutableAttributedString, _ mark: Mark, from: Int, to: Int) {
        let key = AttributedCodec.key(for: mark)
        for piece in pieces(text, from: from, to: to) { text.removeAttribute(key, range: piece) }
    }

    /// The link around `offset` and its full range, for the link sheet.
    static func link(_ text: NSAttributedString, at offset: Int) -> (LinkAttr, NSRange)? {
        for i in [offset, offset - 1] where i >= 0 && i < text.length {
            var range = NSRange()
            if let link = text.attribute(
                .notesLink, at: i, longestEffectiveRange: &range, in: NSRange(location: 0, length: text.length))
                as? LinkAttr
            {
                return (link, range)
            }
        }
        return nil
    }
}
