import UIKit

/// What the find bar searches: the note's text. Highlighting and scrolling are
/// the text view's own; searching and replacing are the editor's, so Replace
/// All changes exactly what the bar counted, as one undoable command.
final class FindTarget: NSObject, UITextSearching {
    private weak var view: NoteTextView?

    init(view: NoteTextView) { self.view = view }

    /// The bar's case and word options, for searching again after the text changes.
    private(set) var lastOptions: UITextSearchOptions?

    var selectedTextRange: UITextRange? { view?.selectedTextRange }

    func compare(_ foundRange: UITextRange, toRange: UITextRange, document: AnyHashable??) -> ComparisonResult {
        view?.compare(foundRange.start, to: toRange.start) ?? .orderedSame
    }

    func performTextSearch(
        queryString: String, options: UITextSearchOptions, resultAggregator: UITextSearchAggregator<AnyHashable?>
    ) {
        lastOptions = options
        if let view {
            for range in Self.matches(of: queryString, in: view.textStorage.string, options: options) {
                if let found = textRange(view, range) {
                    resultAggregator.foundRange(found, searchString: queryString, document: nil)
                }
            }
        }
        resultAggregator.finishedSearching()
    }

    func decorate(foundTextRange: UITextRange, document: AnyHashable??, usingStyle: UITextSearchFoundTextStyle) {
        view?.decorate(foundTextRange: foundTextRange, document: document, usingStyle: usingStyle)
    }

    func clearAllDecoratedFoundText() { view?.clearAllDecoratedFoundText() }

    func scrollRangeToVisible(_ range: UITextRange, inDocument: AnyHashable??) {
        view?.scrollRangeToVisible(range, inDocument: inDocument)
    }

    func willHighlight(foundTextRange: UITextRange, document: AnyHashable??) {
        view?.willHighlight(foundTextRange: foundTextRange, document: document)
    }

    var supportsTextReplacement: Bool { view?.isEditable ?? false }

    // Not left to the protocol's default, which leaves the bar's Replace button disabled.
    func shouldReplace(foundTextRange: UITextRange, document: AnyHashable??, withText: String) -> Bool {
        view?.isEditable ?? false
    }

    func replace(foundTextRange: UITextRange, document: AnyHashable??, withText: String) {
        guard let view else { return }
        let start = view.offset(from: view.beginningOfDocument, to: foundTextRange.start)
        let end = view.offset(from: view.beginningOfDocument, to: foundTextRange.end)
        view.replaceFound([NSRange(location: start, length: end - start)], with: withText)
    }

    func replaceAll(queryString: String, options: UITextSearchOptions, withText: String) {
        guard let view else { return }
        view.replaceFound(Self.matches(of: queryString, in: view.textStorage.string, options: options), with: withText)
    }

    /// Where `query` occurs in `text`, as the find bar's options ask: case, and whether it must start or be a word.
    static func matches(of query: String, in text: String, options: UITextSearchOptions) -> [NSRange] {
        let s = text as NSString
        guard !query.isEmpty else { return [] }
        func letterAt(_ i: Int) -> Bool {
            guard i >= 0 && i < s.length else { return false }
            let character = s.substring(with: s.rangeOfComposedCharacterSequence(at: i))
            return character.unicodeScalars.first.map(CharacterSet.alphanumerics.contains) ?? false
        }
        var out: [NSRange] = []
        var from = 0
        while from < s.length {
            let found = s.range(
                of: query, options: options.stringCompareOptions,
                range: NSRange(location: from, length: s.length - from))
            if found.location == NSNotFound { break }
            let starts = !letterAt(found.location - 1)
            let fits: Bool
            switch options.wordMatchMethod {
            case .startsWith: fits = starts
            case .fullWord: fits = starts && !letterAt(NSMaxRange(found))
            default: fits = true
            }
            if fits { out.append(found) }
            from = fits ? NSMaxRange(found) : found.location + 1
        }
        return out
    }

    private func textRange(_ view: UITextView, _ range: NSRange) -> UITextRange? {
        guard let start = view.position(from: view.beginningOfDocument, offset: range.location),
            let end = view.position(from: start, offset: range.length)
        else { return nil }
        return view.textRange(from: start, to: end)
    }
}
