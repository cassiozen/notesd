import NotesCore
import UIKit

/// Re-derives the structure attributes from the text after every change, so
/// editing can never leave them inconsistent (a port of Android's Normalizer.kt):
///
/// - Blocks are the text between "\n"s; the text always ends with one.
/// - Each block gets exactly one `BlockAttr`. The first block to carry an
///   attribute keeps its type; a block split off by Enter (it carries the same
///   attribute as the block above) takes the type the line above continues into:
///   next list item, same quote, a paragraph after a heading or the title.
///   Blocks joined by deleting a separator take the first one's type.
/// - The first block is the title; nothing else is.
/// - A raw block's attachment stays alone on its line.
/// - List numbers, bullets and boxes are assigned here, not at draw time.
/// - Marks never touch a separator, the title or a raw block.
///
/// Returns the blocks whose structure changed, which the caller restyles.
nonisolated struct Normalizer {
    private struct Info {
        var path: [Container]
        var leaf: Leaf
    }

    /// Fixes `text` in place. `selection` moves with any characters inserted.
    @discardableResult
    static func normalize(_ text: NSMutableAttributedString, selection: inout NSRange) -> IndexSet {
        ensureFinalNewline(text)
        splitRawBlocks(text, selection: &selection)
        let ranges = Blocks.of(text)
        let infos = assign(text, ranges)
        let markers = markers(infos)
        let edges = quoteEdges(infos)
        let gaps = gaps(infos, edges, pictures: ranges.map { hasPicture(text, $0) })
        var changed = IndexSet()
        for (k, r) in ranges.enumerated() {
            let info = infos[k]
            let current = r.attr
            if let current,
                current.sameAs(
                    path: info.path, leaf: info.leaf, marker: markers[k], quoteEdges: edges[k], gap: gaps[k]),
                attributeCovers(text, current, r.whole)
            {
                continue
            }
            text.addAttribute(
                .notesBlock,
                value: BlockAttr(
                    path: info.path, leaf: info.leaf, marker: markers[k], quoteEdges: edges[k], gap: gaps[k]),
                range: r.whole)
            changed.insert(k)
        }
        clipMarks(text, Blocks.of(text))
        return changed
    }

    /// The last block needs a "\n" to carry its attribute.
    private static func ensureFinalNewline(_ text: NSMutableAttributedString) {
        let s = text.string as NSString
        if s.length > 0 && s.character(at: s.length - 1) == 10 { return }
        let last = s.length > 0 ? text.attributes(at: s.length - 1, effectiveRange: nil) : [:]
        text.append(NSAttributedString(string: "\n", attributes: last.filter { $0.key == .notesBlock }))
    }

    /// Keeps a raw block alone on its line: text that ended up beside it gets its own block.
    private static func splitRawBlocks(_ text: NSMutableAttributedString, selection: inout NSRange) {
        var at: [Int] = []
        text.enumerateAttribute(.attachment, in: NSRange(location: 0, length: text.length)) { value, range, _ in
            if (value as? RawAttachment)?.isBlock == true {
                for i in range.location..<NSMaxRange(range) { at.append(i) }
            }
        }
        for i in at.reversed() {
            let s = text.string as NSString
            let attrs = text.attributes(at: i, effectiveRange: nil).filter { $0.key == .notesBlock }
            if i + 1 < s.length && s.character(at: i + 1) != 10 {
                text.insert(NSAttributedString(string: "\n", attributes: attrs), at: i + 1)
                if selection.location > i { selection.location += 1 }
            }
            if i > 0 && s.character(at: i - 1) != 10 {
                text.insert(NSAttributedString(string: "\n", attributes: attrs), at: i)
                if selection.location > i { selection.location += 1 }
            }
        }
    }

    private static func attributeCovers(_ text: NSAttributedString, _ attr: BlockAttr, _ range: NSRange) -> Bool {
        var effective = NSRange()
        let value = text.attribute(.notesBlock, at: range.location, longestEffectiveRange: &effective, in: range)
        return (value as? BlockAttr) === attr && effective == range
    }

    private static func assign(_ text: NSAttributedString, _ ranges: [BlockRange]) -> [Info] {
        var claimed = Set<ObjectIdentifier>()
        var owned = [Bool](repeating: false, count: ranges.count)
        var infos: [Info] = []
        for (k, r) in ranges.enumerated() {
            // The block's own attribute: the one its first character carries, unless a block above claimed it.
            var owner: BlockAttr?
            if let attr = Blocks.attr(text, at: r.start), claimed.insert(ObjectIdentifier(attr)).inserted {
                owner = attr
            }
            owned[k] = owner != nil
            if let owner {
                infos.append(Info(path: owner.path, leaf: owner.leaf))
            } else if k > 0 {
                // Split off the block above (Enter, a paste).
                infos.append(derive(infos[k - 1]))
            } else {
                infos.append(Info(path: [], leaf: .paragraph))
            }
        }

        // Enter at the start of a non-empty block: the new empty line goes above,
        // and the text keeps its type (a heading stays a heading, an item its box).
        for k in 0..<max(ranges.count - 1, 0) where owned[k] && !owned[k + 1] {
            if infos[k].leaf == .title { continue }
            if ranges[k].isEmpty && !ranges[k + 1].isEmpty {
                infos[k + 1] = infos[k]
                infos[k] = derive(infos[k])
            }
        }

        for k in infos.indices {
            let info = infos[k]
            if k == 0 {
                infos[k] = Info(path: [], leaf: .title)
            } else if info.leaf == .title {
                infos[k] = Info(path: [], leaf: .paragraph)
            } else if isRaw(info.leaf) != holdsRawBlock(text, ranges[k]) {
                // A raw block's line is exactly its attachment; anything else is a paragraph.
                infos[k] =
                    holdsRawBlock(text, ranges[k])
                    ? Info(path: info.path, leaf: .raw(source: rawSource(text, ranges[k])))
                    : Info(path: info.path, leaf: .paragraph)
            }
        }
        return infos
    }

    private static func holdsRawBlock(_ text: NSAttributedString, _ r: BlockRange) -> Bool {
        r.contentEnd - r.start == 1
            && (text.attribute(.attachment, at: r.start, effectiveRange: nil) as? RawAttachment)?.isBlock == true
    }

    private static func rawSource(_ text: NSAttributedString, _ r: BlockRange) -> String {
        (text.attribute(.attachment, at: r.start, effectiveRange: nil) as? RawAttachment)?.source ?? ""
    }

    /// The type a new line continues into from the line it was split off.
    private static func derive(_ info: Info) -> Info {
        switch info.leaf {
        case .title: return Info(path: [], leaf: .paragraph)
        case .heading, .raw: return Info(path: info.path, leaf: .paragraph)
        case .paragraph:
            guard case .item(var item)? = info.path.last else { return info }
            item.id = Ids.next()
            item.checked = false
            return Info(path: Array(info.path.dropLast()) + [.item(item)], leaf: .paragraph)
        }
    }

    private static func markers(_ infos: [Info]) -> [Marker?] {
        var next: [Int: Int] = [:]
        return infos.enumerated().map { k, info in
            guard case .item(let item)? = info.path.last else { return nil }
            if k > 0, infos[k - 1].path.contains(where: { isItem($0) && $0.id == item.id }) { return nil }
            switch item.kind {
            case .bullet: return .bullet(level: info.path.filter(isItem).count - 1)
            case .task: return .box(checked: item.checked)
            case .ordered:
                let n = next[item.listId] ?? item.start
                next[item.listId] = n + 1
                return .number(n)
            }
        }
    }

    /// Which quotes each line starts and ends: those its neighbour isn't in.
    private static func quoteEdges(_ infos: [Info]) -> [QuoteEdges] {
        infos.indices.map { k in
            let quotes = infos[k].path.filter { !isItem($0) }
            let above = k > 0 ? infos[k - 1].path : []
            let below = k + 1 < infos.count ? infos[k + 1].path : []
            return QuoteEdges(
                starts: quotes.count { !above.contains($0) }, ends: quotes.count { !below.contains($0) })
        }
    }

    /// The space after each line: wide around quotes, raw blocks and pictures, narrow inside a list.
    private static func gaps(_ infos: [Info], _ edges: [QuoteEdges], pictures: [Bool]) -> [Gap] {
        infos.indices.map { k in
            guard k + 1 < infos.count else { return .paragraph }
            let next = infos[k + 1]
            if isRaw(infos[k].leaf) || isRaw(next.leaf) || pictures[k] || pictures[k + 1] || edges[k].ends > 0
                || edges[k + 1].starts > 0
            {
                return .block
            }
            return sameList(infos[k].path, next.path) ? .item : .paragraph
        }
    }

    private static func hasPicture(_ text: NSAttributedString, _ block: BlockRange) -> Bool {
        var found = false
        text.enumerateAttribute(.attachment, in: block.content) { value, _, stop in
            if (value as? RawAttachment)?.picture != nil {
                found = true
                stop.pointee = true
            }
        }
        return found
    }

    /// Both lines are in one list, at whatever depth: they share its outermost item's list.
    private static func sameList(_ a: [Container], _ b: [Container]) -> Bool {
        guard let d = a.firstIndex(where: isItem), d < b.count, a[..<d] == b[..<d],
            case .item(let x) = a[d], case .item(let y) = b[d]
        else { return false }
        return x.listId == y.listId
    }

    private static let markKeys: [NSAttributedString.Key] = [.notesBold, .notesItalic, .notesStrike, .notesLink]

    private static func clipMarks(_ text: NSMutableAttributedString, _ ranges: [BlockRange]) {
        for (k, r) in ranges.enumerated() {
            let unmarkable = k == 0 || isRaw(r.attr?.leaf ?? .paragraph)
            let range = unmarkable ? r.whole : NSRange(location: r.contentEnd, length: 1)
            guard NSMaxRange(range) <= text.length else { continue }
            for key in markKeys { text.removeAttribute(key, range: range) }
        }
    }

    /// The #tags in the text, as the shared extractor finds them in Markdown:
    /// link text and raw runs can't hold them, and every block is a line.
    static func tags(_ text: NSAttributedString) -> [(NSRange, String)] {
        var visible = text.string.utf16.map { $0 }
        let object = UInt16(0xFFFC)
        text.enumerateAttribute(.notesLink, in: NSRange(location: 0, length: text.length)) { value, range, _ in
            guard value != nil else { return }
            for i in range.location..<NSMaxRange(range) { visible[i] = object }
        }
        var out: [(NSRange, String)] = []
        var lineStart = 0
        let lines = String(decoding: visible, as: UTF16.self).split(separator: "\n", omittingEmptySubsequences: false)
        for line in lines {
            // A hard break ends a line for tags too.
            var pieceStart = lineStart
            for piece in line.split(separator: "\u{2028}", omittingEmptySubsequences: false) {
                for m in scanTags(String(piece)) {
                    out.append((NSRange(location: pieceStart + m.start, length: m.end - m.start), m.name))
                }
                pieceStart += piece.utf16.count + 1
            }
            lineStart += line.utf16.count + 1
        }
        return out
    }
}
