import NotesCore
import Testing
import UIKit

@testable import Notes

/// The find bar's options, which UIKit only makes itself.
private final class Options: UITextSearchOptions {
    private let compare: String.CompareOptions
    private let words: WordMatchMethod

    init(_ compare: String.CompareOptions = .caseInsensitive, words: WordMatchMethod = .contains) {
        self.compare = compare
        self.words = words
    }

    override var stringCompareOptions: String.CompareOptions { compare }
    override var wordMatchMethod: WordMatchMethod { words }
}

/// Find and replace from the system find bar, through the editor's `FindTarget`.
@MainActor
struct FindReplaceTests {
    let view = NoteTextView()

    private var target: FindTarget { view.findTarget }

    private func load(_ markdown: String, title: String = "Title") {
        view.setDocument(Document.forNote(title: title, body: Markdown.parse(markdown)))
    }

    private var markdown: String { Markdown.serialize(view.document()) }

    private func ranges(_ query: String, _ options: Options = Options()) -> [UITextRange] {
        FindTarget.matches(of: query, in: view.text, options: options).map {
            let start = view.position(from: view.beginningOfDocument, offset: $0.location)!
            return view.textRange(from: start, to: view.position(from: start, offset: $0.length)!)!
        }
    }

    private func found(_ query: String, _ options: Options = Options()) -> [String] {
        ranges(query, options).map { view.text(in: $0) ?? "" }
    }

    @Test func searchesTitleAndBodyAsTheBarAsks() {
        load("Apples, apples; pineapple.\n", title: "Apple list")
        #expect(found("apple") == ["Apple", "Apple", "apple", "apple"])
        #expect(found("apple", Options([])) == ["apple", "apple"])
        #expect(found("apple", Options(words: .startsWith)) == ["Apple", "Apple", "apple"])
        #expect(found("apples", Options(words: .fullWord)) == ["Apples", "apples"])
        #expect(found("") == [])
    }

    @Test func replaceAllKeepsStructureAndMarks() {
        load(
            "# cat heading\n\n- a **cat** item\n\ncat\n\n- [ ] cat task\n\n> cat quote\n\n[cat](https://cat.example) #cat\n"
        )
        target.replaceAll(queryString: "cat", options: Options(), withText: "dog")
        #expect(
            markdown
                == "# dog heading\n\n- a **dog** item\n\ndog\n\n- [ ] dog task\n\n> dog quote\n\n[dog](https://cat.example) #dog\n"
        )
    }

    @Test func replaceAllIsOneUndoableEdit() {
        var changes = 0
        view.onChange = { changes += 1 }
        load("one fish two fish\n\n- red fish\n")
        target.replaceAll(queryString: "fish", options: Options(), withText: "cat")
        #expect(markdown == "one cat two cat\n\n- red cat\n")
        #expect(changes == 1, "saved like any edit")
        view.undoManager?.undo()
        #expect(markdown == "one fish two fish\n\n- red fish\n")
        view.undoManager?.redo()
        #expect(markdown == "one cat two cat\n\n- red cat\n")
    }

    @Test func replaceChangesOnlyTheFoundRange() throws {
        load("fish and fish\n")
        let second = try #require(ranges("fish").last)
        target.replace(foundTextRange: second, document: nil, withText: "chips")
        #expect(markdown == "fish and chips\n")
        view.undoManager?.undo()
        #expect(markdown == "fish and fish\n")
    }

    @Test func replacingInTheTitleKeepsItTheTitle() {
        load("body\n", title: "Old title")
        target.replaceAll(queryString: "old", options: Options(), withText: "New")
        #expect(view.document().title == "New title")
        #expect(markdown == "body\n")
    }

    @Test func replacingMayCloseUpATag() {
        load("#to do\n")
        target.replaceAll(queryString: "to do", options: Options(), withText: "todo")
        #expect(markdown == "#todo\n")
        #expect(
            view.textStorage.attribute(
                .notesTag, at: (view.text as NSString).range(of: "#todo").location, effectiveRange: nil) as? String
                == "todo")
    }

    @Test func theCursorStaysWithItsText() {
        load("aa x aa\n")
        let end = (view.text as NSString).range(of: "x").location + 1
        view.selectedRange = NSRange(location: end, length: 0)
        target.replaceAll(queryString: "aa", options: Options(), withText: "b")
        #expect(view.selectedRange.location == (view.text as NSString).range(of: "x").location + 1)
    }

    @Test func highlightsLeaveTheTextAlone() {
        load("# fish\n\n- fish\n\n> fish\n")
        let before = NSAttributedString(attributedString: view.textStorage)
        for (i, range) in ranges("fish").enumerated() {
            target.decorate(foundTextRange: range, document: nil, usingStyle: i == 0 ? .highlighted : .found)
        }
        #expect(view.textStorage.isEqual(to: before))
        target.clearAllDecoratedFoundText()
        #expect(markdown == "# fish\n\n- fish\n\n> fish\n")
    }

    @Test func aSelectionOnOneLineIsTheQuery() {
        load("first line\n\nsecond\n")
        view.selectedRange = (view.text as NSString).range(of: "line")
        view.showFind(replacing: false)
        #expect(view.findInteraction?.searchText == "line")
        view.selectedRange = NSRange(location: (view.text as NSString).range(of: "line").location, length: 8)
        view.findInteraction?.searchText = "kept"
        view.showFind(replacing: false)
        #expect(view.findInteraction?.searchText == "kept", "a selection across lines isn't a query")
    }
}
