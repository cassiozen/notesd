import Foundation
import Testing

@testable import NotesCore

/// §12.1 scenarios against a real notesd, each device with its own database.
@Suite(.serialized, .timeLimit(.minutes(2)))
final class SyncTests {
    let server: TestServer
    private var clients: [TestClient] = []

    init() async throws {
        server = try await TestServer.start()
    }

    private func client(pageSize: Int = 100) async throws -> TestClient {
        let c = TestClient(server, pageSize: pageSize)
        clients.append(c)
        return try await c.signIn()
    }

    /// What the server has, as a brand-new device sees it after one sync.
    private func serverNotes() async throws -> Set<NoteView> {
        let fresh = try await client()
        #expect(await fresh.sync() == .done)
        return try await fresh.live()
    }

    private func syncAll(_ cs: TestClient...) async {
        for _ in 0..<2 {
            for c in cs { #expect(await c.sync() == .done) }
        }
    }

    @Test func differentNotesOnTwoDevicesBothArriveEverywhere() async throws {
        let a = try await client()
        let b = try await client()
        try await a.save("a1", "From A", "alpha\n")
        try await b.save("b1", "From B", "beta\n")
        await syncAll(a, b)
        let expected: Set = [
            NoteView(id: "a1", title: "From A", content: "alpha\n"),
            NoteView(id: "b1", title: "From B", content: "beta\n"),
        ]
        #expect(try await a.live() == expected)
        #expect(try await b.live() == expected)
        #expect(try await serverNotes() == expected)
        #expect(try await a.notes.pendingNotes() == 0)
    }

    @Test func tagsArriveWithTheTextAndLeaveWithIt() async throws {
        let a = try await client()
        let b = try await client()
        try await a.save("n", "Trip", "Book #travel/flights\n")
        await syncAll(a, b)
        #expect(try await b.notes.tags().map(\.key) == ["travel", "travel/flights"])
        #expect(try await b.notes.list(.tag(key: "travel", name: "travel")).map(\.id) == ["n"])

        try await a.save("n", "Trip", "Booked\n")
        await syncAll(a, b)
        #expect(try await b.notes.tags() == [])
        #expect(try await b.notes.list(.untagged).map(\.id) == ["n"])
    }

    @Test func differentFieldsMergeWithoutACopy() async throws {
        let a = try await client()
        let b = try await client()
        try await a.save("n", "Title", "body\n")
        await syncAll(a, b)
        try await a.save("n", "New title", "body\n")
        try await b.save("n", "Title", "new body\n")
        await syncAll(a, b)
        let expected: Set = [NoteView(id: "n", title: "New title", content: "new body\n")]
        #expect(try await a.live() == expected)
        #expect(try await b.live() == expected)
        #expect(try await serverNotes() == expected)
    }

    @Test func theSameFieldChangedOnBothSidesKeepsBothAsAConflictedCopy() async throws {
        let a = try await client()
        let b = try await client()
        try await a.save("n", "Plan", "base\n")
        await syncAll(a, b)
        try await a.save("n", "Plan", "from a\n")
        try await b.save("n", "Plan", "from b\n")
        #expect(await a.sync() == .done)
        #expect(await b.sync() == .done)
        await syncAll(a, b)
        let all = try await serverNotes()
        #expect(all.count == 2)
        #expect(all.first { $0.id == "n" } == NoteView(id: "n", title: "Plan", content: "from a\n"))
        let copy = try #require(all.first { $0.id != "n" })
        #expect(copy == NoteView(id: copy.id, title: "Plan (Conflicted copy)", content: "from b\n", conflictOf: "n"))
        #expect(try await a.live() == all)
        #expect(try await b.live() == all)
        #expect(b.events.count == 1)
        if case .conflictedCopy = b.events.first {} else { Issue.record("expected a conflicted copy: \(b.events)") }
    }

    @Test func anotherClientsMarkdownStyleIsNotAnEdit() async throws {
        let a = try await client()
        let b = try await client()
        try await a.save("n", "T", "* a\n")
        await syncAll(a, b)
        try await b.save("n", "T2", "- a\n")  // what B's editor saves for the same list
        try await a.save("n", "T", "* a\n* b\n")
        #expect(await a.sync() == .done)
        await syncAll(b, a)
        #expect(try await serverNotes() == [NoteView(id: "n", title: "T2", content: "* a\n* b\n")])
    }

    @Test func aLostResponseIsReplayedNotAppliedTwice() async throws {
        let a = try await client()
        try await a.save("n", "T", "one\n")
        a.loseNextResponse { $0.method == "POST" && $0.path.hasSuffix("/notes") }
        #expect(await a.sync() == .offline)
        #expect(await a.sync() == .done)
        try await a.save("n", "T", "two\n")
        a.loseNextResponse { $0.method == "PUT" }
        #expect(await a.sync() == .offline)
        #expect(await a.sync() == .done)
        #expect(try await serverNotes() == [NoteView(id: "n", title: "T", content: "two\n")])
        #expect(try await a.note("n")?.dirty == false)
        #expect(try await a.notes.pendingNotes() == 0)
    }

    @Test func aDownloadCutOffBetweenPagesResumesFromTheStoredCursor() async throws {
        let a = try await client()
        for i in 0..<5 { try await a.save("n\(i)", "Note \(i)", "") }
        #expect(await a.sync() == .done)
        let b = try await client(pageSize: 2)
        let pages = Counter()
        b.beforeNext({ $0.path.hasSuffix("/sync") }, then: { pages.increment() })
        b.failNext { $0.path.hasSuffix("/sync") && pages.count >= 1 }
        #expect(await b.sync() == .offline)
        #expect(try await b.live().count == 2)
        await b.restart()
        #expect(await b.sync() == .done)
        #expect(try await a.live() == b.live())
    }

    @Test func aCrashWhileMakingAConflictedCopyLeavesTheEditIntactForTheNextTry() async throws {
        let a = try await client()
        let b = try await client()
        try await a.save("n", "T", "base\n")
        await syncAll(a, b)
        try await a.save("n", "T", "from a\n")
        try await b.save("n", "T", "from b\n")
        #expect(await a.sync() == .done)
        b.engine.store.faultAfterCopy = { throw CancellationError() }
        #expect(await b.sync() == .failed)
        #expect(try await b.live() == [NoteView(id: "n", title: "T", content: "from b\n")])
        #expect(try await b.note("n")?.dirty == true)
        await b.restart()
        #expect(await b.sync() == .done)
        let live = try await b.live()
        #expect(live.count == 2)
        #expect(live.first { $0.id == "n" }?.content == "from a\n")
        #expect(live.first { $0.id != "n" }?.content == "from b\n")
    }

    @Test func aDeleteReachesTheOtherDevice() async throws {
        let a = try await client()
        let b = try await client()
        try await a.save("n", "T", "x\n")
        await syncAll(a, b)
        try await a.notes.delete("n")
        await syncAll(a, b)
        #expect(try await b.live() == [])
        #expect(try await serverNotes() == [])
    }

    @Test func editedHereDeletedElsewhereBecomesARecoveredCopy() async throws {
        let a = try await client()
        let b = try await client()
        try await a.save("n", "T", "x\n")
        await syncAll(a, b)
        try await a.notes.delete("n")
        try await b.save("n", "T", "kept\n")
        #expect(await a.sync() == .done)
        #expect(await b.sync() == .done)
        await syncAll(a, b)
        let live = try await serverNotes()
        #expect(live.count == 1)
        let copy = try #require(live.first)
        #expect(copy.title == "T (Recovered copy)")
        #expect(copy.content == "kept\n")
        #expect(copy.conflictOf == nil)
        #expect(try await a.live() == live)
        #expect(b.events.count == 1)
        if case .recoveredCopy = b.events.first {} else { Issue.record("expected a recovered copy: \(b.events)") }
    }

    @Test func deletedHereEditedElsewhereComesBack() async throws {
        let a = try await client()
        let b = try await client()
        try await a.save("n", "T", "x\n")
        await syncAll(a, b)
        try await a.save("n", "T", "edited\n")
        try await b.notes.delete("n")
        #expect(await a.sync() == .done)
        #expect(await b.sync() == .done)
        await syncAll(a, b)
        let expected: Set = [NoteView(id: "n", title: "T", content: "edited\n")]
        #expect(try await b.live() == expected)
        #expect(try await serverNotes() == expected)
        #expect(b.events.count == 1)
        if case .restored = b.events.first {} else { Issue.record("expected a restore: \(b.events)") }
    }

    @Test func deletedOnBothSidesIsSimplyGone() async throws {
        let a = try await client()
        let b = try await client()
        try await a.save("n", "T", "x\n")
        await syncAll(a, b)
        try await a.notes.delete("n")
        try await b.notes.delete("n")
        await syncAll(a, b)
        #expect(try await a.live() == [])
        #expect(try await b.live() == [])
        #expect(b.events.isEmpty)
    }

    @Test func monthsOfflineCatchesUpThroughManySmallPages() async throws {
        let a = try await client()
        let b = try await client(pageSize: 7)
        for i in 0..<30 { try await a.save("n\(i)", "Note \(i)", "v0\n") }
        #expect(await a.sync() == .done)
        #expect(await b.sync() == .done)
        b.offline = true
        #expect(await b.sync() == .offline)
        for round in 1...8 {
            for i in 0..<30 where (i + round) % 3 == 0 { try await a.save("n\(i)", "Note \(i)", "v\(round)\n") }
            if round % 4 == 0 { try await a.notes.delete("n\(round)") }
            #expect(await a.sync() == .done)
        }
        b.offline = false
        #expect(await b.sync() == .done)
        #expect(try await a.live() == b.live())
        #expect(try await serverNotes() == b.live())
    }

    @Test func anEditMadeWhileItsUploadIsInFlightGoesOutNext() async throws {
        let a = try await client()
        try await a.save("n", "T", "first\n")
        #expect(await a.sync() == .done)
        try await a.save("n", "T", "second\n")
        let notes = a.notes
        a.beforeNext({ $0.method == "PUT" }, then: { _ = try? await notes.save("n", title: "T", content: "third\n") })
        // "second" goes out, then the same cycle sends "third" against the revision "second" got.
        #expect(await a.sync() == .done)
        #expect(try await serverNotes() == [NoteView(id: "n", title: "T", content: "third\n")])
        #expect(try await a.note("n")?.dirty == false)
        #expect(try await a.notes.pendingNotes() == 0)
    }

    @Test func aServerRestoredFromABackupLosesNothing() async throws {
        let a = try await client()
        let b = try await client()
        try await a.save("old", "Before backup", "x\n")
        await syncAll(a, b)
        server.stop()
        let backup = try server.snapshot()
        try await server.restart()
        try await a.save("new", "After backup", "y\n")
        try await b.save("old", "Before backup", "edited after\n")
        await syncAll(a, b)
        server.stop()
        try server.restore(backup)
        try await server.restart()
        try? FileManager.default.removeItem(at: backup)
        await syncAll(a, b)
        let expected: Set = [
            NoteView(id: "old", title: "Before backup", content: "edited after\n"),
            NoteView(id: "new", title: "After backup", content: "y\n"),
        ]
        #expect(try await serverNotes() == expected)
        #expect(try await a.live() == expected)
        #expect(try await b.live() == expected)
    }

    @Test func signedOutStopsSyncAndKeepsLocalWork() async throws {
        let a = TestClient(server)
        clients.append(a)
        try await a.save("n", "T", "x\n")
        #expect(await a.sync() == .signedOut)
        #expect(try await a.note("n") != nil)
        #expect(try await a.notes.pendingNotes() == 1)
    }

    @Test func aPortalInTheWayKeepsTheEditUntilTheServerAnswers() async throws {
        let a = try await client()
        try await a.save("n", "T", "kept\n")
        a.answerNext({ $0.path.hasSuffix("/notes") }, status: 200)
        #expect(await a.sync() == .failed)
        a.answerNext({ $0.path.hasSuffix("/notes") }, status: 302, body: "")
        #expect(await a.sync() == .failed)
        #expect(try await a.note("n")?.dirty == true)
        #expect(try await a.notes.pendingNotes() == 1)
        #expect(await a.sync() == .done)
        #expect(try await serverNotes() == [NoteView(id: "n", title: "T", content: "kept\n")])
        #expect(try await a.notes.pendingNotes() == 0)
    }

    @Test func aRestartRequeuesAnEditWithNothingQueued() async throws {
        let a = try await client()
        try await a.save("n", "T", "one\n")
        #expect(await a.sync() == .done)
        try await a.save("n", "T", "two\n")
        // As an older app's drop left it: dirty, nothing in the outbox.
        try await a.db.write { try $0.exec("DELETE FROM sync_outbox") }
        await a.restart()
        #expect(await a.sync() == .done)
        #expect(try await serverNotes() == [NoteView(id: "n", title: "T", content: "two\n")])
    }

    @Test func notConnectedIsNotAnError() async throws {
        let dir = FileManager.default.temporaryDirectory.appending(path: "notes-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let engine = SyncEngine(db: Database(path: dir.appending(path: "notes.db").path), api: { nil })
        #expect(await engine.sync().outcome == .notConnected)
        #expect(engine.status.value == .notConnected)
    }
}

/// N devices doing random edits, deletes, syncs, offline spells, lost
/// responses and restarts; then everyone syncs twice. Every device must equal
/// the server, and every text a device had changed locally must still exist
/// somewhere (in its note or in a copy).
@Suite(.serialized, .timeLimit(.minutes(5)))
struct RandomSyncTests {
    @Test func randomHistoriesConvergeWithoutLosingText() async throws {
        // Seeds share one server, so later runs start from a larger history.
        let seeds =
            ProcessInfo.processInfo.environment["NOTESD_SEEDS"]?.split(separator: ",").compactMap {
                UInt64($0.trimmingCharacters(in: .whitespaces))
            } ?? Array(1...6)
        let server = try await TestServer.start()
        for seed in seeds { try await run(seed, server) }
    }

    private func run(_ seed: UInt64, _ server: TestServer) async throws {
        var r = SeededRandom(seed: seed)
        var devices: [TestClient] = []
        for _ in 0..<3 { devices.append(try await TestClient(server, pageSize: 1 + r.int(6)).signIn()) }
        for step in 1...120 {
            let d = devices[r.int(devices.count)]
            // Sorted so a seed always picks the same notes (copies get random ids).
            let live = try await d.live().sorted {
                ($0.title, $0.content, $0.conflictOf ?? "") < ($1.title, $1.content, $1.conflictOf ?? "")
            }
            switch r.int(20) {
            case 0...5:
                if !live.isEmpty {
                    let n = live[r.int(live.count)]
                    let title = r.bool() ? "t\(seed)-\(step)" : n.title
                    let content = r.int(3) > 0 ? text(&r, seed, step) : n.content
                    try await d.save(n.id, title, content)
                }
            case 6...8:
                try await d.save("s\(seed)-\(step)", "t\(seed)-\(step)", text(&r, seed, step))
            case 9:
                if !live.isEmpty { try await d.notes.delete(live[r.int(live.count)].id) }
            case 10:
                d.offline.toggle()
            case 11:
                d.loseNextResponse { $0.method != "GET" }
            case 12:
                await d.restart()
            default:
                await d.sync()
            }
        }

        // Text a device changed and never sent: nobody else can have seen it, so it must survive. (Text in a
        // request whose answer was lost may have reached the server and been edited over since, legitimately.)
        var mustSurvive = Set<String>()
        for d in devices {
            d.offline = false
            d.clearFaults()
            let inFlight = Dictionary(
                try await d.notes.outbox().filter(\.sent).map { ($0.noteId, $0) }, uniquingKeysWith: { a, _ in a })
            for v in try await d.live() {
                let n = try #require(try await d.note(v.id))
                let changed = n.dirty && (n.baseContent.map { !Merge.sameMarkdown($0, n.content) } ?? true)
                let sentContent = try inFlight[n.id].map { try Payload.decode($0.payloadJson).content }
                if changed && sentContent != n.content { mustSurvive.insert(Markdown.canonical(n.content)) }
            }
        }

        for _ in 0..<2 {
            for d in devices { #expect(await d.sync() == .done, "seed \(seed)") }
        }

        let fresh = try await TestClient(server).signIn()
        #expect(await fresh.sync() == .done)
        let truth = try await fresh.live()
        for (i, d) in devices.enumerated() { #expect(try await d.live() == truth, "seed \(seed), device \(i)") }
        // Tags are derived on each device from what sync stored, so they converge with the notes.
        let tags = try await fresh.notes.tags()
        for (i, d) in devices.enumerated() {
            #expect(try await d.notes.tags() == tags, "seed \(seed), device \(i) tags")
        }
        let contents = Set(truth.map { Markdown.canonical($0.content) })
        for text in mustSurvive { #expect(contents.contains(text), "seed \(seed) lost: \(text)") }
        for d in devices { #expect(try await d.notes.pendingNotes() == 0, "seed \(seed): nothing left to send") }
    }

    private func text(_ r: inout SeededRandom, _ seed: UInt64, _ step: Int) -> String {
        switch r.int(3) {
        case 0: "* item \(seed)-\(step)\n* more\n"
        case 1: "**bold** \(seed)-\(step)\n"
        default: "plain \(seed)-\(step) #area/t\(r.int(3))\n"
        }
    }
}
