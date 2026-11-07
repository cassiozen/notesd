/// Search query building and excerpt markers, ported from the server (internal/search/search.go).
public enum Search {
    static let titleWeight = 10.0
    static let contentWeight = 1.0
    static let maxTerms = 16

    /// Private-use code points around each match in an excerpt; note text can't contain a marker by accident.
    public static let markOpen = "\u{E000}"
    public static let markClose = "\u{E001}"

    /// Free-form input → an FTS5 MATCH expression: each word becomes a quoted
    /// prefix term, ANDed, so FTS5 syntax in the input is inert and no input
    /// can produce a query error. "" when there is nothing to search for.
    public static func buildQuery(_ input: String) -> String {
        var words: [String] = []
        var word = String.UnicodeScalarView()
        for c in input.unicodeScalars {
            if isWordChar(c) {
                word.append(c)
            } else if !word.isEmpty {
                words.append(String(word))
                word = String.UnicodeScalarView()
            }
        }
        if !word.isEmpty { words.append(String(word)) }
        // Words hold only letters, digits and combining marks, never a quote; quoting makes OR and NEAR literal.
        return words.prefix(maxTerms).map { "\"\($0)\"*" }.joined(separator: " ")
    }

    private static func isWordChar(_ c: Unicode.Scalar) -> Bool {
        switch c.properties.generalCategory {
        case .uppercaseLetter, .lowercaseLetter, .titlecaseLetter, .modifierLetter, .otherLetter, .decimalNumber,
            .nonspacingMark:
            true
        default:
            false
        }
    }
}

/// §6.1 per-field merge against the last synced version: a field changed on
/// one side only takes that side; nil when both changed it differently.
enum Merge {
    static func field(base: String?, mine: String, theirs: String, same: (String, String) -> Bool) -> String? {
        if let base, same(mine, base) { return theirs }
        if same(theirs, mine) || (base.map { same(theirs, $0) } ?? false) { return mine }
        return nil
    }

    static func sameText(_ a: String, _ b: String) -> Bool { a.same(as: b) }

    /// Another client's Markdown style ("* a", "__b__") isn't an edit: compare the canonical form.
    /// A locked body (§10.1) is compared as stored: its envelope only changes when its text does, and
    /// sync merges without the key, so changed on both sides means a conflicted copy (still locked).
    static func sameMarkdown(_ a: String, _ b: String) -> Bool {
        if a.same(as: b) { return true }
        if LockedNotes.isLocked(a) || LockedNotes.isLocked(b) { return false }
        return Markdown.canonical(a).same(as: Markdown.canonical(b))
    }

    /// §6.2: a body both sides changed, merged block by block. Locked bodies always conflict: sync
    /// merges without the key, and a conflicted copy is the answer that can't lose text.
    static func body(base: String?, mine: String, theirs: String) -> BodyMerge {
        guard let base, ![base, mine, theirs].contains(where: LockedNotes.isLocked) else { return .conflict }
        return BlockMerge.merge(base: base, mine: mine, theirs: theirs)
    }
}
