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

private func body(_ request: URLRequest) -> [String: String] {
    let json = (try? JSONSerialization.jsonObject(with: request.httpBody ?? Data())) as? [String: Any] ?? [:]
    return json.compactMapValues { $0 as? String }
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
        engine(pages: pages) { _ in mutation ?? (500, "", [:]) }
    }

    /// The same, with every other request answered by `answer`, which `stub` records.
    private func engine(
        pages: [(Int, String, [String: String])] = [], stub: StubTransport? = nil,
        answer: @escaping @Sendable (URLRequest) -> (Int, String, [String: String])
    ) -> SyncEngine {
        let pages = Mutex(pages)
        let transport =
            stub
            ?? StubTransport { request in
                switch request.path {
                case "/api/v1/sync": pages.withLock { $0.isEmpty ? emptyPage : $0.removeFirst() }
                case "/api/v1/locks": noKeys
                case "/api/v1/spaces": defaultSpace
                default: answer(request)
                }
            }
        let api = ApiClient(baseURL: URL(string: "http://127.0.0.1:1/")!, credentials: creds, transport: transport)
        return SyncEngine(db: db, api: { api })
    }

    private func attempts(_ noteId: String) async throws -> (attempts: Int64, parked: Bool) {
        try await db.read { sql in
            try sql.one("SELECT attempts, parked FROM sync_outbox WHERE note_id = ?", noteId) {
                ($0.int64(0), $0.int64(1) == 1)
            } ?? (-1, false)
        }
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
            pages: [emptyPage],
            mutation: (400, #"{"error":"invalid_request","message":"Title too long"}"#, jsonHeaders))
        #expect(await engine.sync().outcome == .done)
        #expect(try await engine.store.state().lastError == "Title too long")
        #expect(try await engine.store.nextOperation() == nil)
        #expect(try await notes.get("n")?.dirty == true)
        // Not on the server: still counted as not synced.
        #expect(try await notes.pendingNotes() == 1)
    }

    @Test func anUnknownErrorCodeKeepsTheChange() async throws {
        try await notes.save("n", title: "T", content: "body")
        let engine = engine(
            pages: [emptyPage], mutation: (400, #"{"error":"invalid","message":"Title too long"}"#, jsonHeaders))
        #expect(await engine.sync().outcome == .failed)
        #expect(try await engine.store.state().lastError == SyncMessages.unexpected(400))
        #expect(engine.status.value == .failed(SyncMessages.unexpected(400)))
        #expect(try await engine.store.nextOperation()?.noteId == "n")
        #expect(try await attempts("n") == (1, false))
    }

    @Test func aPortalPageKeepsTheChangeAndParksItAfterThreeTries() async throws {
        try await notes.save("a", title: "A", content: "first")
        try await notes.save("b", title: "B", content: "second")
        let portal = Mutex(true)
        let stub = StubTransport { request in
            switch request.path {
            case "/api/v1/sync": return emptyPage
            case "/api/v1/locks": return noKeys
            case "/api/v1/spaces": return defaultSpace
            default:
                let body = String(decoding: request.httpBody ?? Data(), as: UTF8.self)
                if portal.withLock({ $0 }) && body.contains("first") {
                    return (403, "<html>Sign in to the Wi-Fi</html>", ["Content-Type": "text/html"])
                }
                let id = request.path.hasSuffix("/notes") ? (body.contains("first") ? "a" : "b") : ""
                return (
                    200, #"{"id":"\#(id)","status":"created","revision":1,"changeSeq":1,"updatedAt":1}"#, jsonHeaders
                )
            }
        }
        let engine = engine(stub: stub) { _ in (500, "", [:]) }
        for attempt in 1...3 {
            #expect(await engine.sync().outcome == .failed)
            #expect(try await attempts("a") == (Int64(attempt), attempt == 3))
            #expect(try await notes.get("b")?.dirty == true, "the one behind waits while the first is tried")
        }
        #expect(engine.parked.value == 1)
        // Parked, it's skipped: the note behind it goes, which gives the parked one another try.
        #expect(await engine.sync().outcome == .done)
        #expect(try await notes.get("b")?.dirty == false)
        #expect(try await attempts("a") == (2, false))
        #expect(engine.parked.value == 0)
        // One more unexpected answer parks it again; it stays on this device, counted as not synced.
        #expect(await engine.sync().outcome == .failed)
        #expect(try await attempts("a") == (3, true))
        #expect(try await notes.get("a")?.content == "first")
        #expect(try await notes.pendingNotes() == 1)
        // The user's retry, once the portal is out of the way.
        portal.withLock { $0 = false }
        #expect(await engine.retryParked().outcome == .done)
        #expect(try await notes.get("a")?.dirty == false)
        #expect(engine.parked.value == 0)
    }

    @Test func aParkedChangeIsTriedAgainWhenTheAppStarts() async throws {
        try await notes.save("n", title: "T", content: "body")
        let portal = (302, "", ["Location": "https://portal.example/"])
        let first = engine(mutation: portal)
        for _ in 1...3 { await first.sync() }
        #expect(try await attempts("n") == (3, true))
        #expect(await first.sync().outcome == .done, "parked: nothing to send")
        let restarted = engine(mutation: portal)
        #expect(await restarted.sync().outcome == .failed)
        #expect(try await attempts("n") == (3, true))
    }

    @Test func serverErrorsAndOutagesDontCountTowardsParking() async throws {
        try await notes.save("n", title: "T", content: "body")
        #expect(await engine(mutation: (503, "<html>down</html>", [:])).sync().outcome == .failed)
        #expect(await engine { _ in (429, "", ["Retry-After": "1"]) }.sync().outcome == .offline)
        #expect(try await attempts("n") == (0, false))
    }

    @Test func aReusedOperationIdIsSentOnceMoreUnderANewOne() async throws {
        try await notes.save("n", title: "T", content: "body")
        let stub = StubTransport(queue: [
            (422, #"{"error":"operation_id_reused","message":"reused"}"#, jsonHeaders),
            (200, #"{"id":"n","status":"created","revision":1,"changeSeq":1,"updatedAt":1}"#, jsonHeaders),
            emptyPage, defaultSpace, noKeys,
        ])
        let engine = engine(stub: stub) { _ in (500, "", [:]) }
        #expect(await engine.sync().outcome == .done)
        let sent = stub.requests.prefix(2).map { body($0)["operationId"] }
        #expect(sent[0] != nil && sent[1] != nil && sent[0] != sent[1])
        #expect(try await notes.get("n")?.dirty == false)
    }

    @Test func aReusedOperationIdTwiceIsUnexpected() async throws {
        try await notes.save("n", title: "T", content: "body")
        let engine = engine(mutation: (422, #"{"error":"operation_id_reused","message":"reused"}"#, jsonHeaders))
        #expect(await engine.sync().outcome == .failed)
        #expect(try await attempts("n") == (1, false))
    }

    @Test func aDeleteTheServerDoesntHaveIsDone() async throws {
        try await notes.save("n", title: "T", content: "body")
        let created = (200, #"{"id":"n","status":"created","revision":1,"changeSeq":1,"updatedAt":1}"#, jsonHeaders)
        #expect(await engine(mutation: created).sync().outcome == .done)
        try await notes.delete("n")
        let engine = engine(mutation: (404, #"{"error":"not_found","message":"no such note"}"#, jsonHeaders))
        #expect(await engine.sync().outcome == .done)
        #expect(try await engine.store.nextOperation() == nil)
        #expect(try await notes.pendingNotes() == 0)
    }

    @Test func upgradeRequiredStopsSyncAndKeepsEverything() async throws {
        try await notes.save("n", title: "T", content: "body")
        let engine = engine { _ in
            (426, #"{"error":"upgrade_required","message":"update","minVersion":"1.2.0"}"#, jsonHeaders)
        }
        #expect(await engine.sync().outcome == .upgradeRequired)
        #expect(engine.status.value == .upgradeRequired(minVersion: "1.2.0"))
        #expect(try await engine.store.nextOperation()?.noteId == "n")
        #expect(try await attempts("n") == (0, false))
    }

    @Test func afterUpgradeRequiredNoCycleContactsTheServer() async throws {
        try await notes.save("n", title: "T", content: "body")
        let stub = StubTransport { _ in
            (426, #"{"error":"upgrade_required","message":"update","minVersion":"1.2.0"}"#, jsonHeaders)
        }
        let engine = engine(stub: stub) { _ in (500, "", [:]) }
        #expect(await engine.sync().outcome == .upgradeRequired)
        let sent = stub.requests.count
        #expect(await engine.sync().outcome == .upgradeRequired)
        #expect(await engine.retryParked().outcome == .upgradeRequired)
        #expect(stub.requests.count == sent)
        #expect(engine.status.value == .upgradeRequired(minVersion: "1.2.0"))
        // Another server or account: tried again.
        engine.clearUpgradeRequired()
        #expect(await engine.sync().outcome == .upgradeRequired)
        #expect(stub.requests.count > sent)
    }

    @Test func aConflictWithoutTheCurrentNoteKeepsTheChange() async throws {
        try await notes.save("n", title: "T", content: "body")
        let engine = engine(mutation: (409, #"{"error":"stale_revision","message":"stale"}"#, jsonHeaders))
        #expect(await engine.sync().outcome == .failed)
        #expect(try await engine.store.nextOperation()?.noteId == "n")
        #expect(try await attempts("n") == (1, false))
        for _ in 2...3 { #expect(await engine.sync().outcome == .failed) }
        #expect(try await attempts("n") == (3, true))
        #expect(engine.parked.value == 1)
        #expect(try await notes.get("n")?.dirty == true)
    }

    @Test func anEditAnOlderAppDroppedIsQueuedAgainAtStart() async throws {
        try await notes.save("n", title: "T", content: "body")
        try await db.write { try $0.exec("DELETE FROM sync_outbox") }
        #expect(try await notes.pendingNotes() == 1)
        let stub = StubTransport { request in
            switch request.path {
            case "/api/v1/sync": emptyPage
            case "/api/v1/locks": noKeys
            case "/api/v1/spaces": defaultSpace
            default: (200, #"{"id":"n","status":"created","revision":1,"changeSeq":1,"updatedAt":1}"#, jsonHeaders)
            }
        }
        #expect(await engine(stub: stub) { _ in (500, "", [:]) }.sync().outcome == .done)
        let sent = try #require(stub.requests.first { $0.path == "/api/v1/notes" })
        #expect(body(sent)["content"] == "body")
        #expect(try await notes.get("n")?.dirty == false)
    }

    @Test func notesFiledInAParkedSpaceWaitWithIt() async throws {
        let id = try await SpaceStore(db: db).create("Work")
        try await notes.save("in", title: "In", content: "x", spaceId: id)
        try await notes.save("out", title: "Out", content: "y")
        let stub = StubTransport { request in
            switch request.path {
            case "/api/v1/sync": emptyPage
            case "/api/v1/locks": noKeys
            case "/api/v1/spaces": defaultSpace
            case "/api/v1/spaces/\(id)": (200, "<html>ok</html>", [:])
            default: (200, #"{"id":"x","status":"created","revision":1,"changeSeq":1,"updatedAt":1}"#, jsonHeaders)
            }
        }
        let engine = engine(stub: stub) { _ in (500, "", [:]) }
        for _ in 1...3 { #expect(await engine.sync().outcome == .failed) }
        // The space is parked: the note in it waits too, so the server can't file it in the default.
        #expect(await engine.sync().outcome == .done)
        #expect(try await notes.get("out")?.dirty == false)
        #expect(try await notes.get("in")?.dirty == true)
        #expect(!stub.requests.contains { body($0)["title"] == "In" })
        // Unparked by that, the space fails once more and parks again, its note with it.
        #expect(await engine.sync().outcome == .failed)
        #expect(engine.parked.value == 2)
    }

    @Test func parkedCopyCountsChanges() {
        #expect(SyncMessages.parked(1) == "1 change couldn’t sync. It’s kept on this device.")
        #expect(SyncMessages.parked(3) == "3 changes couldn’t sync. They’re kept on this device.")
    }

    @Test func aSpaceChangedWhileItsRequestWasOutIsntCountedForIt() async throws {
        let spaces = SpaceStore(db: db)
        let id = try await spaces.create("Work")
        let sent = try #require(try await spaces.pending().first)
        try await spaces.update(id, name: "Play")
        try await spaces.attempted(sent)
        let row = try await db.read { sql in
            try sql.one("SELECT attempts FROM spaces WHERE id = ?", id) { $0.int64(0) }
        }
        #expect(row == 0)
    }

    @Test func aNoteMentioningAParkedImagesIdIsntHeld() async throws {
        let image = String(repeating: "ab", count: 32)
        try await db.write { sql in
            try sql.exec(
                "INSERT INTO pending_images (id, content_type, created_at, parked) VALUES (?, 'image/png', 0, 1)", image
            )
        }
        try await notes.save("n", title: "Hash", content: "the id is \(image)")
        #expect(try await SyncStore(db: db).nextOperation()?.noteId == "n")
        try await notes.save("p", title: "Pic", content: ImageRef(id: image, width: 1, height: 1).markdown())
        #expect(try await SyncStore(db: db).parkedCount() == 1)
    }

    @Test func aSpaceThatKeepsGettingUnexpectedAnswersIsParked() async throws {
        let id = try await SpaceStore(db: db).create("Work")
        let engine = engine { request in
            request.path == "/api/v1/spaces/\(id)" ? (200, "<html>ok</html>", [:]) : (500, "", [:])
        }
        for _ in 1...3 { #expect(await engine.sync().outcome == .failed) }
        #expect(engine.parked.value == 1)
        #expect(try await engine.spaces.pending().isEmpty)
    }

    @Test func anUnreadableConflictKeepsTheChangeForTheNextCycle() async throws {
        try await notes.save("n", title: "T", content: "body")
        let engine = engine(
            mutation: (409, #"{"error":"stale_revision","message":"stale","current":{"id":"n"}}"#, jsonHeaders))
        #expect(await engine.sync().outcome == .failed)
        #expect(try await engine.store.nextOperation()?.noteId == "n")
    }
}
