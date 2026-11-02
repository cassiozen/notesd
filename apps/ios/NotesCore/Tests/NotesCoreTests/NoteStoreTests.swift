import Foundation
import Synchronization
import Testing

@testable import NotesCore

/// A store on a fresh database in a temporary directory, with a clock the test sets.
final class StoreFixture: Sendable {
    let dir: URL
    let db: Database
    let store: NoteStore
    let clock = TestClock()

    init() {
        dir = FileManager.default.temporaryDirectory.appending(path: "notes-store-\(UUID().uuidString)")
        try! FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        db = Database(path: dir.appending(path: "notes.db").path)
        let clock = clock
        store = NoteStore(db: db) { clock.now }
    }

    var now: Int64 {
        get { clock.now }
        set { clock.now = newValue }
    }

    var path: String { dir.appending(path: "notes.db").path }

    deinit {
        try? FileManager.default.removeItem(at: dir)
    }
}

/// A clock tests move by hand, starting at 1000.
final class TestClock: Sendable {
    private let time = Mutex<Int64>(1_000)

    var now: Int64 {
        get { time.withLock { $0 } }
        set { time.withLock { $0 = newValue } }
    }
}

/// An envelope of the right shape (§10.1); this build can't make real ones.
func fakeEnvelope() -> String {
    let bytes = (0..<44).map { _ in UInt8.random(in: 0...255) }
    let base64 = Data(bytes).base64EncodedString()
        .replacing("+", with: "-").replacing("/", with: "_").replacing("=", with: "")
    return LockedNotes.prefix + base64
}

struct NoteStoreTests {
    let f = StoreFixture()
    var store: NoteStore { f.store }

    @Test func aBlankNewNoteIsNotCreated() async throws {
        #expect(try await store.save("a", title: "", content: "  \n") == nil)
        #expect(try await store.get("a") == nil)
        #expect(try await store.outbox() == [])
    }

    @Test func createsListsAndReadsANote() async throws {
        try await store.save("a", title: "Groceries", content: "- **Milk**\n- Bread\n")
        f.now = 2_000
        try await store.save("b", title: "Later", content: "")
        let a = try #require(try await store.get("a"))
        #expect(a.title == "Groceries")
        #expect(a.createdAt == 1_000)
        #expect(a.dirty)
        #expect(a.serverRevision == nil)
        #expect(try await store.list().map(\.id) == ["b", "a"])
        #expect(try await store.list()[1].preview == "Milk\nBread")
    }

    @Test func editsBeforeAnythingIsSentCollapseIntoTheCreate() async throws {
        try await store.save("a", title: "One", content: "")
        f.now = 2_000
        try await store.save("a", title: "Two", content: "body\n")
        let ops = try await store.outbox()
        #expect(ops.count == 1)
        #expect(ops[0].type == "create")
        #expect(ops[0].payloadJson.contains("\"title\":\"Two\""))
        #expect(ops[0].payloadJson.contains("\"createdAt\":1000"))
        #expect(ops[0].payloadJson.contains("\"updatedAt\":2000"))
    }

    @Test func anEditAfterASendQueuesANewOperationAndLaterEditsCoalesceIntoIt() async throws {
        try await store.save("a", title: "One", content: "")
        try await store.markSent(try await store.outbox()[0].operationId)
        try await store.save("a", title: "Two", content: "")
        try await store.save("a", title: "Three", content: "")
        let ops = try await store.outbox()
        #expect(ops.map(\.type) == ["create", "update"])
        #expect(ops[0].payloadJson.contains("\"One\""), "a sent payload is frozen")
        #expect(ops[1].payloadJson.contains("\"Three\""))
    }

    @Test func savingUnchangedTextDoesNothing() async throws {
        try await store.save("a", title: "One", content: "x\n")
        f.now = 5_000
        try await store.save("a", title: "One", content: "x\n")
        #expect(try await store.get("a")?.updatedAt == 1_000)
    }

    @Test func deletingANeverSentNoteRemovesEveryTrace() async throws {
        try await store.save("a", title: "One", content: "")
        try await store.delete("a")
        #expect(try await store.get("a") == nil)
        #expect(try await store.outbox() == [])
        #expect(try await store.search("one") == [])
    }

    @Test func deletingASentNoteLeavesATombstoneAndADeleteOperation() async throws {
        try await store.save("a", title: "One", content: "")
        try await store.markSent(try await store.outbox()[0].operationId)
        try await store.save("a", title: "Two", content: "")
        try await store.delete("a")
        #expect(try await store.get("a")?.deletedAt != nil)
        #expect(try await store.list() == [])
        #expect(try await store.search("two") == [])
        #expect(try await store.outbox().map(\.type) == ["create", "delete"], "the unsent edit is dropped")
        #expect(try await store.save("a", title: "Three", content: "") == nil, "a deleted note can't be edited")
    }

    @Test func searchMatchesPrefixesIgnoresDiacriticsAndRanksTitlesFirst() async throws {
        try await store.save("body", title: "Shopping", content: "Visit the café on Main\n")
        try await store.save("title", title: "Cafe list", content: "Places\n")
        try await store.save("other", title: "Unrelated", content: "Nothing here\n")
        #expect(try await store.search("cafe").map(\.id) == ["title", "body"])
        #expect(try await store.search("caf").map(\.id) == ["title", "body"])
        #expect(try await store.search("café main").map(\.id) == ["body"])
        let hits = try await store.search("main")
        #expect(hits.count == 1)
        #expect(hits.first?.excerpt == "Visit the café on \(Search.markOpen)Main\(Search.markClose)")
    }

    @Test func searchIndexesPlainTextNotMarkdownSyntax() async throws {
        try await store.save("a", title: "T", content: "[link text](https://example.com/secret)\n")
        #expect(try await store.search("link").count == 1)
        #expect(try await store.search("secret").count == 0)
    }

    @Test func searchFollowsEditsImmediately() async throws {
        try await store.save("a", title: "T", content: "apple\n")
        try await store.save("a", title: "T", content: "banana\n")
        #expect(try await store.search("apple").count == 0)
        #expect(try await store.search("banana").count == 1)
    }

    @Test func oddInputNeverBreaksSearch() async throws {
        try await store.save("a", title: "T", content: "x\n")
        for q in ["\"", "*", "NEAR(", "a OR", "-", "^", ""] { _ = try await store.search(q) }
    }

    @Test func theSystemSQLiteHasFTS5() async throws {
        let fts5 = try await f.db.read { try $0.int64("SELECT sqlite_compileoption_used('ENABLE_FTS5')") }
        #expect(fts5 == 1)
    }

    @Test func reindexRebuildsTheIndexFromTheNotes() async throws {
        try await store.save("a", title: "Alpha", content: "")
        try await store.save("b", title: "Beta", content: "")
        try await f.db.write { try $0.exec("DELETE FROM notes_fts") }
        #expect(try await store.search("alpha").count == 0)
        #expect(try await store.reindex() == 2)
        #expect(try await store.search("alpha").count == 1)
        #expect(try await store.list().first?.preview == "")
    }

    @Test func wipeRemovesEverything() async throws {
        try await store.save("a", title: "Alpha", content: "")
        try await store.wipe()
        #expect(try await store.list() == [])
        #expect(try await store.outbox() == [])
    }

    @Test func writesNotifyObservers() async throws {
        let changes = store.changes()
        try await store.save("a", title: "Alpha", content: "")
        var iterator = changes.makeAsyncIterator()
        #expect(await iterator.next() != nil)
    }

    @Test func localEditsAreAnnounced() async throws {
        let edits = store.localEdits()
        try await store.save("a", title: "Alpha", content: "")
        var iterator = edits.makeAsyncIterator()
        #expect(await iterator.next() != nil)
    }

    @Test func dataAndSchemaSurviveReopening() async throws {
        try await store.save("a", title: "Alpha", content: "")
        await f.db.close()
        let again = Database(path: f.path)
        #expect(try await NoteStore(db: again).get("a")?.title == "Alpha")
        #expect(try await again.read { try $0.int64("PRAGMA user_version") } == Int64(Migrations.files.count))
        await again.close()
    }

    // The editor saves text it started from `basedOn`; sync may have changed the note since.

    private func syncChanged(_ id: String, _ title: String, _ content: String) async throws {
        try await f.db.write { try $0.exec("UPDATE notes SET title = ?, content = ? WHERE id = ?", title, content, id) }
    }

    @Test func anEditorSaveMergesWithASyncChangeToAnotherField() async throws {
        try await store.save("a", title: "Title", content: "body\n")
        try await syncChanged("a", "Title", "synced body\n")
        try await store.save("a", title: "Typed title", content: "body\n", basedOn: ("Title", "body\n"))
        let a = try #require(try await store.get("a"))
        #expect(a.title == "Typed title")
        #expect(a.content == "synced body\n")
        #expect(try await store.list().count == 1)
    }

    @Test func anEditorSaveOverASyncChangeToTheSameFieldKeepsBoth() async throws {
        try await store.save("a", title: "Title", content: "body\n")
        try await syncChanged("a", "Title", "synced body\n")
        try await store.save("a", title: "Title", content: "typed body\n", basedOn: ("Title", "body\n"))
        #expect(try await store.get("a")?.content == "typed body\n")
        let copy = try #require(try await store.list().first { $0.id != "a" })
        let stored = try #require(try await store.get(copy.id))
        #expect(stored.title == "Title (Conflicted copy)")
        #expect(stored.content == "synced body\n")
        #expect(stored.conflictOf == "a")
    }

    @Test func tagsFollowEditsDeletesAndNesting() async throws {
        try await store.save("a", title: "Roof #Home", content: "Call about #home/repairs/roof\n")
        f.now = 2_000
        try await store.save("b", title: "", content: "#home/garden and #work\n")
        try await store.save("c", title: "Plain", content: "no tags, # heading-ish, C#\n")
        try await store.save("d", title: "", content: "#homework\n")

        #expect(
            try await store.tags() == [
                TagCount(key: "home", name: "home", count: 2),
                TagCount(key: "home/garden", name: "home/garden", count: 1),
                TagCount(key: "home/repairs", name: "home/repairs", count: 1),
                TagCount(key: "home/repairs/roof", name: "home/repairs/roof", count: 1),
                TagCount(key: "homework", name: "homework", count: 1),
                TagCount(key: "work", name: "work", count: 1),
            ])
        // A parent matches its nested tags, not a tag that merely starts with it.
        #expect(Set(try await store.list(.tag(key: "home", name: "home")).map(\.id)) == ["a", "b"])
        #expect(try await store.list(.tag(key: "home/repairs", name: "home/repairs")).map(\.id) == ["a"])
        #expect(try await store.list(.untagged).map(\.id) == ["c"])
        #expect(try await store.search("home", filter: .tag(key: "work", name: "work")).map(\.id) == ["b"])

        f.now = 3_000
        try await store.save("b", title: "", content: "#WORK only\n")
        try await store.delete("a")
        #expect(
            try await store.tags() == [
                TagCount(key: "homework", name: "homework", count: 1), TagCount(key: "work", name: "WORK", count: 1),
            ])
        #expect(try await store.list(.untagged).map(\.id) == ["c"])
    }

    @Test func aRequestedRebuildFillsInTagsOnOpen() async throws {
        try await store.save("a", title: "", content: "#kept\n")
        try await f.db.write { sql in
            try sql.exec("DELETE FROM note_tags")
            try sql.exec("INSERT INTO rebuild_index (id) VALUES (1)")
        }
        await f.db.close()
        let reopened = Database(path: f.path)
        #expect(try await NoteStore(db: reopened).tags() == [TagCount(key: "kept", name: "kept", count: 1)])
        await reopened.close()
    }

    @Test func aLockedBodyIsNeitherIndexedTaggedNorPreviewed() async throws {
        try await store.save("a", title: "Bank #private", content: fakeEnvelope())
        let summary = try #require(try await store.list().first)
        #expect(summary.locked)
        #expect(summary.preview == "")
        #expect(try await store.search("notesd").isEmpty)
        #expect(try await store.tags().map(\.key) == ["private"])
        #expect(try await store.search("bank").first?.locked == true)
        try await store.save("b", title: "Open", content: "plain\n")
        #expect(try await store.list(.locked).map(\.id) == ["a"])
        #expect(try await store.lockedCount() == 1)

        // Unlocking is an ordinary edit: the body is indexed again.
        try await store.save("a", title: "Bank #private", content: "PIN 1234 #money\n")
        #expect(try await store.search("1234").map(\.id) == ["a"])
        #expect(try await store.tags().map(\.key) == ["money", "private"])
        #expect(try await store.list(.locked) == [])
        #expect(try await store.lockedCount() == 0)
    }

    @Test func aBodyLockedByANewerVersionCountsAsLocked() async throws {
        try await store.save("a", title: "Bank", content: "notesd:locked:v2:secretwords")
        #expect(try await store.list(.locked).map(\.id) == ["a"])
        #expect(try await store.list().first?.locked == true)
        #expect(try await store.search("secretwords").isEmpty)
        #expect(try await store.get("a")?.locked == true)
    }

    @Test func envelopesCompareAsStoredNeverAsMarkdown() {
        let a = fakeEnvelope()
        let b = fakeEnvelope()
        #expect(Merge.sameMarkdown(a, a))
        #expect(!Merge.sameMarkdown(a, b))
        #expect(Merge.field(base: a, mine: b, theirs: fakeEnvelope(), same: Merge.sameMarkdown) == nil)
    }
}

// Same cases as the server's TestBuildQuery (internal/search/search_test.go).
struct SearchQueryTests {
    @Test func buildsQuotedPrefixTerms() {
        let cases = [
            "": "",
            "   ": "",
            "milk": "\"milk\"*",
            "Oat milk": "\"Oat\"* \"milk\"*",
            "\"unbalanced": "\"unbalanced\"*",
            "a OR b NEAR(c)": "\"a\"* \"OR\"* \"b\"* \"NEAR\"* \"c\"*",
            "title:secret -x ^y *": "\"title\"* \"secret\"* \"x\"* \"y\"*",
            "café naïve": "\"café\"* \"naïve\"*",
            "日本語": "\"日本語\"*",
            "--- ***": "",
        ]
        for (input, want) in cases { #expect(Search.buildQuery(input) == want, "\(input)") }
    }

    @Test func capsTheNumberOfTerms() {
        let query = Search.buildQuery(String(repeating: "w ", count: 100))
        #expect(query.split(separator: " ").count == Search.maxTerms)
    }
}
