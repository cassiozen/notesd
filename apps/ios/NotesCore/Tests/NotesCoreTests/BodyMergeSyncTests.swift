import Foundation
import Testing

@testable import NotesCore

/// §6.2 between devices: bodies both sides changed merge block by block, and Undo splits a merge back.
@Suite(.serialized, .timeLimit(.minutes(2)))
final class BodyMergeSyncTests {
    let server: TestServer
    private var clients: [TestClient] = []

    init() async throws {
        server = try await TestServer.start()
    }

    private func client() async throws -> TestClient {
        let c = TestClient(server)
        clients.append(c)
        return try await c.signIn()
    }

    private func syncAll(_ cs: TestClient...) async {
        for _ in 0..<2 {
            for c in cs { #expect(await c.sync() == .done) }
        }
    }

    /// a and b share note "n"; then a sets `fromA` and b `fromB`, a syncing first.
    private func edited(_ title: String, _ body: String, _ fromA: String, _ fromB: String) async throws -> (
        TestClient, TestClient
    ) {
        let a = try await client()
        let b = try await client()
        try await a.save("n", title, body)
        await syncAll(a, b)
        try await a.save("n", title, fromA)
        try await b.save("n", title, fromB)
        #expect(await a.sync() == .done)
        #expect(await b.sync() == .done)
        await syncAll(a, b)
        return (a, b)
    }

    @Test func differentBlocksMergeWithoutACopy() async throws {
        let (a, b) = try await edited("Plan", "One\n\nTwo\n", "One!\n\nTwo\n", "One\n\nTwo!\n")
        let expected: Set = [NoteView(id: "n", title: "Plan", content: "One!\n\nTwo!\n")]
        #expect(try await a.live() == expected)
        #expect(try await b.live() == expected)
        #expect(b.events == [.merged(noteId: "n", title: "Plan")])
    }

    @Test func aTickedItemAndARewordedOneMerge() async throws {
        let (a, b) = try await edited(
            "List", "- [ ] milk\n- [ ] eggs\n", "- [ ] oat milk\n- [ ] eggs\n", "- [x] milk\n- [ ] eggs\n")
        #expect(try await a.live() == [NoteView(id: "n", title: "List", content: "- [x] oat milk\n- [ ] eggs\n")])
        #expect(try await b.live() == a.live())
    }

    @Test func aPartialConflictGivesBothNotesEveryChangeThatMerged() async throws {
        let (a, b) = try await edited(
            "Plan", "Intro\n\nTuesday\n\nOutro\n", "Intro, edited\n\nWednesday\n\nOutro\n",
            "Intro\n\nThursday\n\nOutro, edited\n")
        let live = try await a.live()
        #expect(try await b.live() == live)
        #expect(
            live.first { $0.id == "n" }
                == NoteView(id: "n", title: "Plan", content: "Intro, edited\n\nWednesday\n\nOutro, edited\n"))
        let copy = try #require(live.first { $0.id != "n" })
        #expect(
            copy
                == NoteView(
                    id: copy.id, title: "Plan (Conflicted copy)",
                    content: "Intro, edited\n\nThursday\n\nOutro, edited\n",
                    conflictOf: "n"))
        if case .conflictedCopy = b.events.first {} else { Issue.record("expected a conflicted copy: \(b.events)") }
        #expect(try await b.notes.mergeUndo("n") == nil)
    }

    @Test func undoPutsTheServersVersionBackAndKeepsThisDevicesAsACopy() async throws {
        let (a, b) = try await edited("Plan", "One\n\nTwo\n", "One!\n\nTwo\n", "One\n\nTwo!\n")
        #expect(try await b.notes.mergeUndo("n") != nil)
        let copyId = try #require(try await b.notes.undoMerge("n"))
        #expect(try await b.notes.mergeUndo("n") == nil)
        await syncAll(b, a)
        let expected: Set = [
            NoteView(id: "n", title: "Plan", content: "One!\n\nTwo\n"),
            NoteView(id: copyId, title: "Plan (Conflicted copy)", content: "One\n\nTwo!\n", conflictOf: "n"),
        ]
        #expect(try await a.live() == expected)
        #expect(try await b.live() == expected)
    }

    @Test func undoAfterALaterEditOnlyMakesTheCopy() async throws {
        let (_, b) = try await edited("Plan", "One\n\nTwo\n", "One!\n\nTwo\n", "One\n\nTwo!\n")
        try await b.save("n", "Plan", "One!\n\nTwo!\n\nThree\n")
        let copyId = try #require(try await b.notes.undoMerge("n"))
        #expect(try await b.note("n")?.content == "One!\n\nTwo!\n\nThree\n")
        #expect(try await b.note(copyId)?.content == "One\n\nTwo!\n")
    }

    @Test func aLaterConflictDropsTheEarlierMergesUndo() async throws {
        let (a, b) = try await edited("Plan", "One\n\nTwo\n", "One!\n\nTwo\n", "One\n\nTwo!\n")
        #expect(try await b.notes.mergeUndo("n") != nil)
        try await a.save("n", "Plan", "One, a\n\nTwo!\n")
        try await b.save("n", "Plan", "One, b\n\nTwo!\n")
        #expect(await a.sync() == .done)
        #expect(await b.sync() == .done)
        #expect(try await b.live().count == 2)
        #expect(try await b.notes.mergeUndo("n") == nil)
    }

    @Test func undoIsGoneOnceTheNoteIsDeleted() async throws {
        let (_, b) = try await edited("Plan", "One\n\nTwo\n", "One!\n\nTwo\n", "One\n\nTwo!\n")
        try await b.notes.delete("n")
        #expect(try await b.notes.mergeUndo("n") == nil)
        #expect(try await b.notes.undoMerge("n") == nil)
    }

    @Test func aSaveOverASyncChangeMergesTheBodyKeepingTheTyping() async throws {
        let a = try await client()
        let b = try await client()
        try await a.save("n", "Plan", "One\n\nTwo\n")
        await syncAll(a, b)
        try await a.save("n", "Plan", "One!\n\nTwo\n")
        await syncAll(a, b)
        // b's editor opened "One/Two" and its typing reaches the store after sync brought a's edit.
        let saved = try await b.notes.save(
            "n", title: "Plan", content: "One\n\nTwo!\n", basedOn: (title: "Plan", content: "One\n\nTwo\n"))
        #expect(saved?.content == "One!\n\nTwo!\n")
        #expect(try await b.live().count == 1)

        try await b.notes.undoMerge("n")
        let live = try await b.live()
        #expect(live.first { $0.id == "n" }?.content == "One\n\nTwo!\n")
        #expect(live.first { $0.id != "n" }?.content == "One!\n\nTwo\n")
    }
}
