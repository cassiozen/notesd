import Foundation
import Testing

@testable import NotesCore

/// Changes queued for notes in and out of shared spaces (§7.9), and what the feeds bring.
struct SharedSpacesTests {
    let fixture = StoreFixture()
    let spaces: SpaceStore
    let sync: SyncStore
    let family = RemoteSpace(
        id: "fam", name: "Family",
        shared: SharedSpace(role: "owner", members: [Member(id: 1, username: "owner"), Member(id: 2, username: "kid")]))

    init() {
        let clock = fixture.clock
        spaces = SpaceStore(db: fixture.db) { clock.now }
        sync = SyncStore(db: fixture.db) { clock.now }
    }

    private var store: NoteStore { fixture.store }

    /// The list with "fam" shared, and "a" synced, clean, in the default space.
    private func setUp() async throws {
        try await spaces.replace([RemoteSpace(id: Spaces.defaultId, name: "Notes"), family])
        try await sync.syncFeeds()
        try await store.save("a", title: "Groceries", content: "Milk\n")
        try await fixture.db.write { sql in
            try sql.exec("DELETE FROM sync_outbox")
            try sql.exec("UPDATE notes SET server_revision = 1, dirty = 0 WHERE id = 'a'")
        }
    }

    private func queued(_ id: String? = nil) async throws -> [String] {
        try await store.outbox().filter { id == nil || $0.noteId == id }.map { "\($0.type) \($0.feed)" }
    }

    @Test func movingIntoASharedSpaceQueuesAMoveWhereTheServerHasItEditsAfterItInTheSpace() async throws {
        try await setUp()
        try await store.move("a", to: "fam")
        try await store.save("a", title: "Groceries", content: "Milk, eggs\n")
        let ops = try await store.outbox()
        #expect(ops.map { "\($0.type) \($0.feed)" } == ["update ", "move ", "update fam"])
        #expect(ops[1].payloadJson.contains(#""spaceId":"fam""#))
        #expect(ops[2].payloadJson.contains("eggs"))
    }

    @Test func movedBackBeforeTheMoveWentNoMove() async throws {
        try await setUp()
        try await store.move("a", to: "fam")
        try await store.move("a", to: Spaces.defaultId)
        #expect(try await queued() == ["update "])
    }

    @Test func aNoteTheServerNeverHadIsJustCreatedInTheSharedSpace() async throws {
        try await setUp()
        try await store.save("b", title: "New", content: "")
        try await store.move("b", to: "fam")
        #expect(try await queued("b") == ["create fam"])
    }

    @Test func aDeleteGoesWhereTheServerHasTheNote() async throws {
        try await setUp()
        try await store.move("a", to: "fam")
        try await store.delete("a")
        #expect(try await queued() == ["delete "])
    }

    @Test func aMoveTheServerCantHaveIsDroppedWhenItsTurnComes() async throws {
        try await setUp()
        try await store.save("b", title: "New", content: "")
        // As an older outbox could hold: a move for a note whose create never went.
        try await fixture.db.write { sql in
            try sql.enqueue("b", type: moveOp, payload: Payload(spaceId: "fam"), now: 2_000, feed: "")
        }
        let move = try #require(try await store.outbox().first { $0.type == moveOp })
        let op = Operation(
            operationId: move.operationId, noteId: "b", type: moveOp, baseRevision: nil,
            payload: Payload(spaceId: "fam"), sent: false)
        #expect(try await sync.prepare(op) == nil)
        #expect(try await queued("b") == ["create "])
    }

    @Test func aNoteMovedWhereThisDeviceFollowsKeepsItsTextInTheNewSpace() async throws {
        try await setUp()
        _ = try await sync.applyPage(
            SyncPage(
                changes: [RemoteNote(id: "a", revision: 2, deletedAt: 5, spaceId: "default", movedTo: "fam")],
                nextCursor: 2, hasMore: false, generation: "g"))
        let a = try #require(try await store.get("a"))
        #expect(a.spaceId == "fam" && a.title == "Groceries")
        #expect(a.deletedAt == nil)
        // The space's feed brings it at the revision it kept, then another member's edit.
        _ = try await sync.applyPage(
            SyncPage(
                changes: [RemoteNote(id: "a", title: "Groceries", content: "Milk\n", revision: 1, spaceId: "fam")],
                nextCursor: 1, hasMore: false, generation: "h"),
            feed: "fam")
        _ = try await sync.applyPage(
            SyncPage(
                changes: [
                    RemoteNote(
                        id: "a", title: "Groceries", content: "Milk, eggs\n", revision: 2, spaceId: "fam", updatedBy: 2)
                ],
                nextCursor: 2, hasMore: false, generation: "h"),
            feed: "fam")
        let edited = try #require(try await store.get("a"))
        #expect(edited.content == "Milk, eggs\n" && edited.updatedBy == 2)
    }

    @Test func aNoteMovedOutOfReachIsGoneLikeADeletion() async throws {
        try await setUp()
        _ = try await sync.applyPage(
            SyncPage(
                changes: [RemoteNote(id: "a", revision: 2, deletedAt: 5, spaceId: "default", movedTo: "elsewhere")],
                nextCursor: 2, hasMore: false, generation: "g"))
        #expect(try await store.get("a")?.deletedAt == 5)
    }

    @Test func aNoteAnotherMemberMovedToTheirOwnDefaultIsGoneHere() async throws {
        try await setUp()
        try await sync.setUserId(1)
        _ = try await sync.applyPage(
            SyncPage(
                changes: [RemoteNote(id: "s", title: "Shared", content: "", revision: 1, spaceId: "fam")],
                nextCursor: 1, hasMore: false, generation: "h"),
            feed: "fam")
        _ = try await sync.applyPage(
            SyncPage(
                changes: [
                    RemoteNote(id: "s", revision: 2, deletedAt: 5, spaceId: "fam", updatedBy: 2, movedTo: "default")
                ],
                nextCursor: 2, hasMore: false, generation: "h"),
            feed: "fam")
        #expect(try await store.get("s")?.deletedAt == 5)
    }

    @Test func aMoveIntoTheAccountsOwnSpaceIsFollowedWhenTheAccountMadeIt() async throws {
        try await setUp()
        try await sync.setUserId(1)
        try await sharedNote()
        _ = try await sync.applyPage(movedToDefault(by: 1), feed: "fam")
        let s = try #require(try await store.get("s"))
        #expect(s.spaceId == Spaces.defaultId && s.deletedAt == nil)
    }

    @Test func notKnowingWhoMovedItOrWhoThisAccountIsAMoveIntoItsOwnSpaceIsADeletion() async throws {
        try await setUp()
        try await sharedNote()
        _ = try await sync.applyPage(movedToDefault(by: 1), feed: "fam")
        #expect(try await store.get("s")?.deletedAt == 5, "this account's id not known yet")
        try await sync.setUserId(1)
        try await sharedNote(id: "t")
        _ = try await sync.applyPage(movedToDefault(id: "t", by: nil), feed: "fam")
        #expect(try await store.get("t")?.deletedAt == 5, "the mover not recorded")
    }

    @Test func leavingWithAMoveOutNotYetSentKeepsTheNoteSentAgainAsNewWhereItIs() async throws {
        try await setUp()
        _ = try await sync.applyPage(
            SyncPage(
                changes: [RemoteNote(id: "s", title: "Shared", content: "Text\n", revision: 3, spaceId: "fam")],
                nextCursor: 1, hasMore: false, generation: "h"),
            feed: "fam")
        try await store.move("s", to: Spaces.defaultId)
        #expect(try await queued("s") == ["update fam", "move fam"])
        try await spaces.replace([RemoteSpace(id: Spaces.defaultId, name: "Notes")])
        try await sync.syncFeeds()
        let s = try #require(try await store.get("s"))
        #expect(s.spaceId == Spaces.defaultId && s.serverRevision == nil && s.content == "Text\n")
        #expect(try await queued("s") == ["create "])
    }

    @Test func leavingWithAMoveInNotYetSentPutsTheNoteBackWhereTheServerHasIt() async throws {
        try await setUp()
        try await store.move("a", to: "fam")
        try await store.save("a", title: "Groceries", content: "Milk, eggs\n")
        try await spaces.replace([RemoteSpace(id: Spaces.defaultId, name: "Notes")])
        try await sync.syncFeeds()
        let a = try #require(try await store.get("a"))
        #expect(a.spaceId == Spaces.defaultId && a.content == "Milk, eggs\n")
        let ops = try await store.outbox().filter { $0.noteId == "a" }
        #expect(ops.map { "\($0.type) \($0.feed)" } == ["update "])
        #expect(ops[0].payloadJson.contains("eggs"))
        #expect(ops[0].payloadJson.contains(#""spaceId":"default""#))
    }

    @Test func leavingWithADeleteNotYetSentOfANoteMovedInHereKeepsTheDelete() async throws {
        try await setUp()
        try await store.move("a", to: "fam")
        try await store.delete("a")
        try await spaces.replace([RemoteSpace(id: Spaces.defaultId, name: "Notes")])
        try await sync.syncFeeds()
        let a = try #require(try await store.get("a"))
        #expect(a.spaceId == Spaces.defaultId && a.deletedAt != nil)
        #expect(try await queued("a") == ["delete "])
    }

    /// "s" (or `id`), synced in "fam".
    private func sharedNote(id: String = "s") async throws {
        _ = try await sync.applyPage(
            SyncPage(
                changes: [RemoteNote(id: id, title: "Shared", content: "", revision: 1, spaceId: "fam")],
                nextCursor: 1, hasMore: false, generation: "h"),
            feed: "fam")
    }

    private func movedToDefault(id: String = "s", by: Int64?) -> SyncPage {
        SyncPage(
            changes: [RemoteNote(id: id, revision: 2, deletedAt: 5, spaceId: "fam", updatedBy: by, movedTo: "default")],
            nextCursor: 2, hasMore: false, generation: "h")
    }

    @Test func leavingASharedSpaceTakesItsNotesOffTheDevice() async throws {
        try await setUp()
        _ = try await sync.applyPage(
            SyncPage(
                changes: [RemoteNote(id: "s", title: "Shared", content: "", revision: 1, spaceId: "fam")],
                nextCursor: 1, hasMore: false, generation: "h"),
            feed: "fam")
        #expect(try await sync.feeds() == ["fam"])
        try await spaces.replace([RemoteSpace(id: Spaces.defaultId, name: "Notes")])
        try await sync.syncFeeds()
        #expect(try await sync.feeds() == [])
        #expect(try await store.get("s") == nil)
    }

    @Test func eachFeedKeepsItsOwnCursor() async throws {
        try await setUp()
        _ = try await sync.applyPage(
            SyncPage(changes: [], nextCursor: 7, hasMore: false, generation: "h"), feed: "fam")
        #expect(try await sync.state(feed: "fam").cursor == 7)
        #expect(try await sync.state(feed: "fam").generation == "h")
        #expect(try await sync.state().cursor == 0)
    }

    @Test func whatOthersChangedSinceTheSpaceWasLastShown() async throws {
        try await setUp()
        try await sync.setUserId(1)
        fixture.now = 2_000
        _ = try await sync.applyPage(
            SyncPage(
                changes: [
                    RemoteNote(
                        id: "s", title: "Shared", content: "", revision: 1, updatedAt: 3_000, spaceId: "fam",
                        updatedBy: 2)
                ],
                nextCursor: 1, hasMore: false, generation: "h"),
            feed: "fam")
        #expect(try await spaces.news()["fam"] == SpaceNews(count: 1, usernames: ["kid"], latestAt: 3_000))
        fixture.now = 4_000
        try await spaces.visit("fam")
        #expect(try await spaces.news().isEmpty)
    }

    @Test func aVisitTimedWhenTheSpaceWasShownLeavesWhatChangedSinceAsNews() async throws {
        try await setUp()
        try await sync.setUserId(1)
        _ = try await sync.applyPage(
            SyncPage(
                changes: [
                    RemoteNote(
                        id: "s", title: "Shared", content: "", revision: 1, updatedAt: 3_000, spaceId: "fam",
                        updatedBy: 2)
                ],
                nextCursor: 1, hasMore: false, generation: "h"),
            feed: "fam")
        fixture.now = 4_000
        try await spaces.visit("fam", at: 2_500)
        #expect(try await spaces.news()["fam"]?.count == 1)
        // Stamped ahead of this clock: past it, or it would stay news until the clock catches up.
        _ = try await sync.applyPage(
            SyncPage(
                changes: [
                    RemoteNote(
                        id: "t", title: "Ahead", content: "", revision: 1, updatedAt: 9_000, spaceId: "fam",
                        updatedBy: 2)
                ],
                nextCursor: 2, hasMore: false, generation: "h"),
            feed: "fam")
        try await spaces.visit("fam", at: 4_000)
        #expect(try await spaces.news().isEmpty)
    }

    @Test func aSpaceSharedAgainCountsOnlyWhatsNewSince() async throws {
        try await setUp()
        try await fixture.db.write { try $0.exec("UPDATE spaces SET visited_at = 1 WHERE id = 'fam'") }
        var unshared = family
        unshared.shared = nil
        try await spaces.replace([RemoteSpace(id: Spaces.defaultId, name: "Notes"), unshared])
        fixture.now = 9_000
        try await spaces.replace([RemoteSpace(id: Spaces.defaultId, name: "Notes"), family])
        try await sync.syncFeeds()
        #expect(
            try await fixture.db.read { try $0.int64("SELECT visited_at FROM spaces WHERE id = 'fam'") } == 9_000)
    }

    @Test func editsHereRecordThisAccount() async throws {
        try await sync.setUserId(7)
        try await store.save("n", title: "Mine", content: "")
        #expect(try await store.get("n")?.updatedBy == 7)
        #expect(try await store.list().first?.updatedBy == 7)
    }

    @Test func theListKeepsMembersAndInvitationsAndWhenTheSpaceWasShown() async throws {
        try await setUp()
        try await spaces.visit("fam")
        let visited = try await fixture.db.read { try $0.int64("SELECT visited_at FROM spaces WHERE id = 'fam'") }
        try await spaces.replace([RemoteSpace(id: Spaces.defaultId, name: "Notes"), family])
        #expect(try await spaces.list().first { $0.id == "fam" }?.shared == family.shared)
        #expect(
            try await fixture.db.read { try $0.int64("SELECT visited_at FROM spaces WHERE id = 'fam'") } == visited)
        let invite = RemoteInvite(
            id: "i", spaceId: "trip", spaceName: "Trip", from: Member(id: 3, username: "ana"), createdAt: 9)
        try await spaces.replaceInvites([invite])
        #expect(try await spaces.invites() == [invite])
    }
}
