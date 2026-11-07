import Foundation

/// A three-way merge of note bodies (spec §6.2), as apps/web/src/editor/merge.ts defines it.
public enum BodyMerge: Sendable {
    case clean(String)
    /// Each side's version of the blocks both changed; both hold every change that merged.
    case partial(theirs: String, mine: String)
    case conflict
}

/// A port of apps/web/src/editor/merge.ts, step for step: shared/markdown-fixtures/merge.json and
/// merge-generated.json pin both to the same output. Change the reference first, then follow here.
enum BlockMerge {
    /// Above this many LCS cells (after the common prefix) a merge gives up: a copy is cheaper than the wait.
    static let maxCells = 4_000_000

    private struct TooLarge: Error {}

    /// Both sides of a merged sequence; equal where nothing conflicted.
    private struct Merged<T> {
        var theirs: [T] = []
        var mine: [T] = []
        var conflicts = 0

        mutating func add(_ part: Merged<T>) {
            theirs += part.theirs
            mine += part.mine
            conflicts += part.conflicts
        }

        static func settled(_ nodes: [T]) -> Merged<T> { Merged(theirs: nodes, mine: nodes) }

        static func conflict(mine: [T], theirs: [T]) -> Merged<T> { Merged(theirs: theirs, mine: mine, conflicts: 1) }
    }

    /// How one kind of sequence element compares and merges.
    private struct Element<T> {
        let same: (T, T) -> Bool
        let sameKind: (T, T) -> Bool
        let merge: (T, T, T) throws -> Merged<T>
    }

    static func merge(base: String, mine: String, theirs: String) -> BodyMerge {
        let merged: Merged<Block>
        do {
            merged = try mergeSeq(blocks(base), blocks(mine), blocks(theirs), blockElement)
        } catch {
            return .conflict
        }
        let theirsOut = render(merged.theirs)
        let mineOut = render(merged.mine)
        let outputs = merged.conflicts == 0 ? [theirsOut] : [theirsOut, mineOut]
        // Not surviving its own round trip, or repeating a block more than any input does, means a bug
        // or a diff3 anomaly: a copy loses nothing.
        if outputs.contains(where: { !Markdown.canonical($0).same(as: $0) }) { return .conflict }
        let most = maxCounts([base, mine, theirs])
        if outputs.contains(where: { exceeds(leafCounts($0), most) }) { return .conflict }
        if merged.conflicts == 0 { return .clean(theirsOut) }
        if theirsOut.same(as: Markdown.canonical(theirs)) && mineOut.same(as: Markdown.canonical(mine)) {
            return .conflict
        }
        return .partial(theirs: theirsOut, mine: mineOut)
    }

    private static func blocks(_ markdown: String) -> [Block] { Markdown.parse(markdown).body }

    private static func render(_ blocks: [Block]) -> String {
        blocks.isEmpty ? "" : Markdown.serialize(Document(blocks))
    }

    // MARK: - Sequences

    /// Each element of `a`'s match in `b`, or -1. Ties go to deleting from `a`: ports must agree, or merges differ.
    private static func lcs<T>(_ a: [T], _ b: [T], _ same: (T, T) -> Bool) throws -> [Int] {
        var match = [Int](repeating: -1, count: a.count)
        var p = 0
        while p < a.count && p < b.count && same(a[p], b[p]) {
            match[p] = p
            p += 1
        }
        let n = a.count - p
        let m = b.count - p
        if n * m > maxCells { throw TooLarge() }
        let w = m + 1
        var table = [Int32](repeating: 0, count: (n + 1) * w)
        if n > 0 && m > 0 {
            for i in stride(from: n - 1, through: 0, by: -1) {
                for j in stride(from: m - 1, through: 0, by: -1) {
                    table[i * w + j] =
                        same(a[p + i], b[p + j])
                        ? table[(i + 1) * w + j + 1] + 1
                        : max(table[(i + 1) * w + j], table[i * w + j + 1])
                }
            }
        }
        var i = 0
        var j = 0
        while i < n && j < m {
            if same(a[p + i], b[p + j]) {
                match[p + i] = p + j
                i += 1
                j += 1
            } else if table[(i + 1) * w + j] >= table[i * w + j + 1] {
                i += 1
            } else {
                j += 1
            }
        }
        return match
    }

    private static func sameSeq<T>(_ a: ArraySlice<T>, _ b: ArraySlice<T>, _ same: (T, T) -> Bool) -> Bool {
        a.count == b.count && zip(a, b).allSatisfy { same($0, $1) }
    }

    /// diff3 (Khanna, Kunal & Pierce): stable runs where all three match, `mergeChunk` for what lies between.
    private static func mergeSeq<T>(_ base: [T], _ mine: [T], _ theirs: [T], _ el: Element<T>) throws -> Merged<T> {
        let toMine = try lcs(base, mine, el.same)
        let toTheirs = try lcs(base, theirs, el.same)
        var out = Merged<T>()
        var i = 0
        var a = 0
        var b = 0
        while true {
            while i < base.count && toMine[i] == a && toTheirs[i] == b {
                out.add(.settled([base[i]]))
                i += 1
                a += 1
                b += 1
            }
            if i == base.count && a == mine.count && b == theirs.count { return out }
            var k = i
            while k < base.count && (toMine[k] == -1 || toTheirs[k] == -1) { k += 1 }
            let nextA = k < base.count ? toMine[k] : mine.count
            let nextB = k < base.count ? toTheirs[k] : theirs.count
            out.add(
                try mergeChunk(
                    Array(base[i..<k]), Array(mine[a..<nextA]), Array(theirs[b..<nextB]),
                    (i..<k).map { toMine[$0] == -1 ? -1 : toMine[$0] - a },
                    (i..<k).map { toTheirs[$0] == -1 ? -1 : toTheirs[$0] - b },
                    el))
            i = k
            a = nextA
            b = nextB
        }
    }

    /// `toMine`/`toTheirs`: each base element's match in that side's chunk, or -1.
    private static func mergeChunk<T>(
        _ base: [T], _ mine: [T], _ theirs: [T], _ toMine: [Int], _ toTheirs: [Int], _ el: Element<T>
    ) throws -> Merged<T> {
        if sameSeq(mine[...], base[...], el.same) { return .settled(theirs) }
        if sameSeq(theirs[...], base[...], el.same) || sameSeq(mine[...], theirs[...], el.same) {
            return .settled(mine)
        }
        let mineInPlace = inPlace(base, mine, el)
        let theirsInPlace = inPlace(base, theirs, el)
        if mineInPlace && theirsInPlace {
            // Paired block by block: only blocks both sides changed can conflict.
            var out = Merged<T>()
            for k in base.indices { out.add(try el.merge(base[k], mine[k], theirs[k])) }
            return out
        }
        if theirsInPlace {
            return applyAround(base, mine, toMine, theirs, el) ?? .conflict(mine: mine, theirs: theirs)
        }
        if mineInPlace {
            return applyAround(base, theirs, toTheirs, mine, el) ?? .conflict(mine: mine, theirs: theirs)
        }
        return .conflict(mine: mine, theirs: theirs)
    }

    /// Edited block by block, without adding, removing or changing the kind of any.
    private static func inPlace<T>(_ base: [T], _ side: [T], _ el: Element<T>) -> Bool {
        side.count == base.count && zip(side, base).allSatisfy { el.sameKind($0, $1) }
    }

    /// `moved`'s sequence with `edited`'s version of each block it kept; nil if it removed one `edited` changed.
    private static func applyAround<T>(
        _ base: [T], _ moved: [T], _ toMoved: [Int], _ edited: [T], _ el: Element<T>
    ) -> Merged<T>? {
        var out: [T] = []
        var j = 0
        for k in base.indices {
            let at = toMoved[k]
            if at == -1 {
                if !el.same(edited[k], base[k]) { return nil }
                continue
            }
            out += moved[j..<at]
            out.append(edited[k])
            j = at + 1
        }
        out += moved[j...]
        return .settled(out)
    }

    // MARK: - Blocks

    /// §6.1's rule for one value: the side that changed it, or nil if both changed it differently.
    private struct Picked<V> { let value: V }

    private static func field<V>(_ base: V, _ mine: V, _ theirs: V, _ same: (V, V) -> Bool) -> Picked<V>? {
        if same(mine, base) { return Picked(value: theirs) }
        if same(theirs, base) || same(mine, theirs) { return Picked(value: mine) }
        return nil
    }

    private static var blockElement: Element<Block> {
        Element(same: exact, sameKind: { $0.kind == $1.kind }, merge: mergeBlock)
    }

    private static var itemElement: Element<ListItem> {
        Element(same: { exact($0.blocks, $1.blocks) }, sameKind: { _, _ in true }, merge: mergeItem)
    }

    private static var taskElement: Element<TaskItem> {
        Element(
            same: { $0.checked == $1.checked && exact($0.blocks, $1.blocks) }, sameKind: { _, _ in true },
            merge: mergeTask)
    }

    /// Same-kind blocks at one position, changed on at least one side.
    private static func mergeBlock(_ base: Block, _ mine: Block, _ theirs: Block) throws -> Merged<Block> {
        if exact(mine, base) { return .settled([theirs]) }
        if exact(theirs, base) || exact(mine, theirs) { return .settled([mine]) }
        let whole = Merged<Block>.conflict(mine: [mine], theirs: [theirs])
        switch (base, mine, theirs) {
        case (.heading(let bl, let bc), .heading(let ml, let mc), .heading(let tl, let tc)):
            guard let level = field(bl, ml, tl, ==), let content = field(bc, mc, tc, exact) else { return whole }
            return .settled([.heading(level: level.value, content.value)])
        case (.orderedList(let bs, let bi), .orderedList(let ms, let mi), .orderedList(let ts, let ti)):
            guard let start = field(bs, ms, ts, ==) else { return whole }
            return container(try mergeSeq(bi, mi, ti, itemElement)) { .orderedList(start: start.value, $0) }
        case (.bulletList(let bi), .bulletList(let mi), .bulletList(let ti)):
            return container(try mergeSeq(bi, mi, ti, itemElement)) { .bulletList($0) }
        case (.taskList(let bi), .taskList(let mi), .taskList(let ti)):
            return container(try mergeSeq(bi, mi, ti, taskElement)) { .taskList($0) }
        case (.blockquote(let bb), .blockquote(let mb), .blockquote(let tb)):
            return container(try mergeSeq(bb, mb, tb, blockElement)) { .blockquote($0) }
        default:
            // Paragraphs and raw blocks are atomic: both changed is a conflict.
            return whole
        }
    }

    private static func mergeItem(_ base: ListItem, _ mine: ListItem, _ theirs: ListItem) throws -> Merged<ListItem> {
        if itemElement.same(mine, base) { return .settled([theirs]) }
        if itemElement.same(theirs, base) || itemElement.same(mine, theirs) { return .settled([mine]) }
        let inner = try mergeSeq(base.blocks, mine.blocks, theirs.blocks, blockElement)
        return item(mine, theirs, inner) { ListItem($0) }
    }

    private static func mergeTask(_ base: TaskItem, _ mine: TaskItem, _ theirs: TaskItem) throws -> Merged<TaskItem> {
        if taskElement.same(mine, base) { return .settled([theirs]) }
        if taskElement.same(theirs, base) || taskElement.same(mine, theirs) { return .settled([mine]) }
        guard let checked = field(base.checked, mine.checked, theirs.checked, ==) else {
            return .conflict(mine: [mine], theirs: [theirs])
        }
        let inner = try mergeSeq(base.blocks, mine.blocks, theirs.blocks, blockElement)
        return item(mine, theirs, inner) { TaskItem(checked: checked.value, $0) }
    }

    /// A container whose children merged as a sequence; each side rebuilt around its own.
    private static func container<C, T>(_ inner: Merged<C>, _ build: ([C]) -> T) -> Merged<T> {
        // Emptied (both sides removed different children): the container goes too.
        Merged(
            theirs: inner.theirs.isEmpty ? [] : [build(inner.theirs)],
            mine: inner.mine.isEmpty ? [] : [build(inner.mine)],
            conflicts: inner.conflicts)
    }

    /// `container` for a list item, which must start with a paragraph; anything else conflicts whole.
    private static func item<T>(_ mine: T, _ theirs: T, _ inner: Merged<Block>, _ build: ([Block]) -> T) -> Merged<T> {
        func startsRight(_ blocks: [Block]) -> Bool {
            guard let first = blocks.first else { return true }
            return first.kind == .paragraph
        }
        if !startsRight(inner.theirs) || !startsRight(inner.mine) { return .conflict(mine: [mine], theirs: [theirs]) }
        return container(inner, build)
    }

    // MARK: - Exact equality

    // Swift's String == is canonical equivalence; the web and Kotlin compare code units. Merges must agree.

    private static func exact(_ a: [Block], _ b: [Block]) -> Bool {
        a.count == b.count && zip(a, b).allSatisfy { exact($0, $1) }
    }

    private static func exact(_ a: Block, _ b: Block) -> Bool {
        switch (a, b) {
        case (.title(let x), .title(let y)), (.rawBlock(let x), .rawBlock(let y)): x.same(as: y)
        case (.paragraph(let x), .paragraph(let y)): exact(x, y)
        case (.heading(let l, let x), .heading(let m, let y)): l == m && exact(x, y)
        case (.bulletList(let x), .bulletList(let y)): exact(x, y)
        case (.orderedList(let s, let x), .orderedList(let t, let y)): s == t && exact(x, y)
        case (.taskList(let x), .taskList(let y)):
            x.count == y.count && zip(x, y).allSatisfy { $0.checked == $1.checked && exact($0.blocks, $1.blocks) }
        case (.blockquote(let x), .blockquote(let y)): exact(x, y)
        default: false
        }
    }

    private static func exact(_ a: [ListItem], _ b: [ListItem]) -> Bool {
        a.count == b.count && zip(a, b).allSatisfy { exact($0.blocks, $1.blocks) }
    }

    private static func exact(_ a: [Inline], _ b: [Inline]) -> Bool {
        a.count == b.count && zip(a, b).allSatisfy { exact($0, $1) }
    }

    private static func exact(_ a: Inline, _ b: Inline) -> Bool {
        switch (a, b) {
        case (.text(let x, let m), .text(let y, let n)), (.rawInline(let x, let m), .rawInline(let y, let n)):
            x.same(as: y) && exact(m, n)
        case (.hardBreak(let m), .hardBreak(let n)): exact(m, n)
        default: false
        }
    }

    private static func exact(_ a: [Mark], _ b: [Mark]) -> Bool {
        a.count == b.count
            && zip(a, b).allSatisfy {
                switch ($0, $1) {
                case (.link(let h, let t), .link(let i, let u)):
                    h.same(as: i) && (t == nil ? u == nil : u.map { t!.same(as: $0) } ?? false)
                default: $0 == $1
                }
            }
    }

    // MARK: - Duplication check

    /// A leaf block compared by its scalars, so counting agrees with `exact`.
    private struct LeafKey: Hashable {
        let scalars: [Unicode.Scalar]

        init(_ block: Block) { scalars = Array(String(reflecting: block).unicodeScalars) }
    }

    /// How often each leaf block (paragraph, heading, raw block) occurs.
    private static func leafCounts(_ markdown: String) -> [LeafKey: Int] {
        var counts: [LeafKey: Int] = [:]
        func visit(_ blocks: [Block]) {
            for b in blocks {
                switch b {
                case .paragraph, .heading, .rawBlock, .title: counts[LeafKey(b), default: 0] += 1
                case .bulletList(let items), .orderedList(_, let items): for item in items { visit(item.blocks) }
                case .taskList(let items): for item in items { visit(item.blocks) }
                case .blockquote(let inner): visit(inner)
                }
            }
        }
        visit(Markdown.parse(markdown).body)
        return counts
    }

    private static func maxCounts(_ inputs: [String]) -> [LeafKey: Int] {
        var most: [LeafKey: Int] = [:]
        for input in inputs {
            for (key, n) in leafCounts(input) { most[key] = max(most[key] ?? 0, n) }
        }
        return most
    }

    // A block no input has is new (a heading with a merged level), not a duplicate.
    private static func exceeds(_ counts: [LeafKey: Int], _ most: [LeafKey: Int]) -> Bool {
        counts.contains { key, n in most[key].map { n > $0 } ?? false }
    }
}
