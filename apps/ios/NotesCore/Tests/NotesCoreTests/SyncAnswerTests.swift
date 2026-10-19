import Foundation
import Synchronization
import Testing

@testable import NotesCore

private let jsonHeaders = ["Content-Type": "application/json"]
private let noKeys = (200, #"{"keys":[]}"#, jsonHeaders)
private let defaultSpace = (
    200, #"{"spaces":[{"id":"default","name":"Notes","createdAt":0,"noteCount":0}]}"#, jsonHeaders
)
private let emptyPage = (200, #"{"changes":[],"nextCursor":0,"hasMore":false,"generation":"g"}"#, jsonHeaders)

private func page(_ changes: String, cursor: Int64) -> (Int, String, [String: String]) {
    (200, #"{"changes":[\#(changes)],"nextCursor":\#(cursor),"hasMore":false,"generation":"g"}"#, jsonHeaders)
}

/// Sync against answers a real notesd wouldn't give, or gives rarely.
struct SyncAnswerTests {
    let dir: URL
    let db: Database
    let notes: NoteStore
    let creds = MemoryCredentials()

    init() throws {
        dir = FileManager.default.temporaryDirectory.appending(path: "notes-answers-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        db = Database(path: dir.appending(path: "notes.db").path)
        notes = NoteStore(db: db)
        creds.current = Tokens(access: "a", accessExpiresAt: Int64.max, refresh: "r")
    }

    /// An engine whose server answers sync from `pages` in order, note writes with `mutation`, the keyring empty
    /// and the spaces only the default.
    private func engine(
        pages: [(Int, String, [String: String])] = [], mutation: (Int, String, [String: String])? = nil
    ) -> SyncEngine {
        let pages = Mutex(pages)
        let stub = StubTransport { request in
            switch request.path {
            case "/api/v1/sync": pages.withLock { $0.isEmpty ? emptyPage : $0.removeFirst() }
            case "/api/v1/locks": noKeys
            case "/api/v1/spaces": defaultSpace
            default: mutation ?? (500, "", [:])
            }
        }
        let api = ApiClient(baseURL: URL(string: "http://127.0.0.1:1/")!, credentials: creds, transport: stub)
        return SyncEngine(db: db, api: { api })
    }

    @Test func aNoteMissingItsContentStopsThePullWithoutMovingTheCursor() async throws {
        let engine = engine(pages: [page(#"{"id":"n","title":"T","revision":1,"changeSeq":1}"#, cursor: 1)])
        #expect(await engine.sync().outcome == .failed)
        #expect(try await engine.store.state().cursor == 0)
        #expect(try await notes.get("n") == nil)
    }

    @Test func aTombstoneStillDecodes() async throws {
        let engine = engine(pages: [
            page(#"{"id":"n","title":"","content":"","revision":2,"changeSeq":1,"deletedAt":5}"#, cursor: 1)
        ])
        #expect(await engine.sync().outcome == .done)
        #expect(try await engine.store.state().cursor == 1)
    }

    @Test func aDeletedLockedNoteForgetsItsImages() async throws {
        let body = LockedNotes.prefix + "AAAA"
        let engine = engine(pages: [
            page(
                #"{"id":"n","title":"T","content":"\#(body)","revision":1,"changeSeq":1,"imageIds":["img"]}"#,
                cursor: 1),
            page(#"{"id":"n","title":"","content":"","revision":2,"changeSeq":2,"deletedAt":5}"#, cursor: 2),
        ])
        let remembered = {
            try await db.read { try $0.int64("SELECT count(*) FROM locked_images WHERE note_id = 'n'") }
        }
        #expect(await engine.sync().outcome == .done)
        #expect(try await remembered() == 1)
        #expect(await engine.sync().outcome == .done)
        #expect(try await remembered() == 0)
    }

    @Test func aRejectedChangeIsStillReportedAfterTheCycle() async throws {
        try await notes.save("n", title: "T", content: "body")
        let engine = engine(
            pages: [emptyPage], mutation: (400, #"{"error":"invalid","message":"Title too long"}"#, jsonHeaders))
        #expect(await engine.sync().outcome == .done)
        #expect(try await engine.store.state().lastError == "Title too long")
        #expect(try await engine.store.nextOperation() == nil)
        #expect(try await notes.get("n")?.dirty == true)
    }

    @Test func anUnreadableConflictKeepsTheChangeForTheNextCycle() async throws {
        try await notes.save("n", title: "T", content: "body")
        let engine = engine(
            mutation: (409, #"{"error":"stale_revision","message":"stale","current":{"id":"n"}}"#, jsonHeaders))
        #expect(await engine.sync().outcome == .failed)
        #expect(try await engine.store.nextOperation()?.noteId == "n")
    }
}
