import Foundation
import Testing

@testable import NotesCore

struct LinesTests {
    @Test func fixturesSurviveFlattening() {
        let failures = (Fixtures.roundtrip + Fixtures.plaintext).compactMap { c -> String? in
            let doc = Document.forNote(title: "Title", body: Markdown.parse(c.markdown))
            let back = linesToDocument(doc.toLines())
            return back == doc ? nil : "\(c.name): \(doc) became \(back)"
        }
        #expect(failures == [])
    }

    @Test func randomDocumentsSurviveFlattening() {
        let failures = (UInt64(1)...2000).compactMap { seed -> String? in
            var gen = DocGen(seed: seed)
            let doc = gen.document()
            let back = linesToDocument(doc.toLines())
            return back == doc ? nil : "seed \(seed): \(doc) became \(back)"
        }
        #expect(Array(failures.prefix(3)) == [])
    }

    @Test func adjacentListsMultiParagraphItemsAndQuotedListsKeepTheirShape() {
        let md = "- One\n\n* Two\n\n- a\n\n  b\n\n> - q\n> - r\n"
        let lines = Markdown.parse(md).toLines()
        #expect(Set(lines.compactMap { $0.item?.listId }).count == 4)
        #expect(Markdown.serialize(linesToDocument(lines)) == md)
    }
}

struct PlainTextTests {
    @Test func plainTextMatchesTheSharedFixtures() {
        let failures = Fixtures.plaintext.compactMap { c -> String? in
            let out = plainText(c.markdown)
            return out.same(as: c.text ?? "") ? nil : "\(c.name): expected \(c.text ?? "") got \(out)"
        }
        #expect(failures == [])
    }

    @Test func previewIsOneLinePerBlockAndCutAt160CodePoints() {
        #expect(preview("# Title\n\nBody   text\n") == "Title\nBody text")
        let cut = preview(String(repeating: "🎉", count: 200))
        #expect(cut.unicodeScalars.count == 161)
        #expect(cut.hasSuffix("…"))
    }

    @Test func uuid7IsWellFormedAndSortsByTime() throws {
        let a = Uuid7.generate(nowMillis: 1_700_000_000_000)
        let b = Uuid7.generate(nowMillis: 1_700_000_000_001)
        let shape = try Regex("^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
        #expect(a.wholeMatch(of: shape) != nil)
        #expect(a < b)
    }

    @Test func lockedEnvelopesAreRecognisedExactly() {
        #expect(LockedNotes.isLocked("notesd:locked:v1:AAAA"))
        #expect(LockedNotes.isLocked("notesd:locked:v2:AAAA"))
        #expect(!LockedNotes.isLocked(" notesd:locked:v1:AAAA"))
    }
}

struct TagsTests {
    @Test func tagsMatchTheSharedFixtures() {
        let failures = Fixtures.tags.compactMap { c -> String? in
            let got = tags(title: c.title ?? "", markdown: c.markdown)
            let keysOk = got.allSatisfy { $0.key == $0.name.lowercased() }
            return got.map(\.name) == c.tags && keysOk ? nil : "\(c.name): expected \(c.tags ?? []) got \(got)"
        }
        #expect(failures == [])
    }

    @Test func tagKeysNormaliseNamesAsTyped() {
        #expect(tagKey("#Home/Repairs") == "home/repairs")
        #expect(tagKey("#school / biology#") == "school/biology")
        #expect(tagKey("#") == "")
    }

    @Test func matchesCoverTheHashMarksInUTF16Offsets() {
        let line = "🎉 #a and #b c# end"
        let ns = line as NSString
        let found = scanTags(line).map { ns.substring(with: NSRange(location: $0.start, length: $0.end - $0.start)) }
        #expect(found == ["#a", "#b c#"])
    }

    @Test func tagTextIsClosedWhenItNeedsToBe() {
        #expect(tagText("home/repairs") == "#home/repairs")
        #expect(tagText("summer trip") == "#summer trip#")
        #expect(tagText("2024") == "#2024#")
        #expect(tagText("v1.2") == "#v1.2#")
        for name in ["home/repairs", "summer trip", "2024", "v1.2"] {
            #expect(tags(title: "", markdown: "\(tagText(name)) after").map(\.name) == [name])
        }
    }
}
