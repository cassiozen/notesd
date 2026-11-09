import Foundation
import Testing

@testable import NotesCore

/// Shared spaces (§7.9) between two accounts and a real notesd: sharing, joining, edits both ways, moves in
/// and out, and leaving.
@Suite(.serialized, .timeLimit(.minutes(2)))
final class SharedSpacesSyncTests {
    let server: TestServer
    private var clients: [TestClient] = []

    init() async throws {
        server = try await TestServer.start()
        try await server.addAccount("ben")
    }

    private func owner() async throws -> TestClient {
        let c = TestClient(server)
        clients.append(c)
        return try await c.signIn()
    }

    private func ben() async throws -> TestClient {
        let c = TestClient(server)
        clients.append(c)
        return try await c.signIn("ben")
    }

    private func syncAll(_ cs: TestClient...) async {
        for _ in 0..<2 {
            for c in cs { #expect(await c.sync() == .done) }
        }
    }

    private func benId(_ c: TestClient) async throws -> Int64 {
        let people = try #require(await c.api.people().value)
        return try #require(people.first { $0.username == "ben" }?.id)
    }

    /// The owner's "Family" with one note, shared with ben, who joined: both synced.
    private func shared() async throws -> (owner: TestClient, ben: TestClient, family: String) {
        let o = try await owner()
        let b = try await ben()
        let family = try await o.engine.spaces.create("Family")
        try await o.notes.save("n", title: "Groceries", content: "Milk\n", spaceId: family)
        await syncAll(o)
        guard case .ok = await o.api.inviteToSpace(family, userId: try await benId(o)) else {
            Issue.record("invite failed")
            throw CancellationError()
        }
        await syncAll(o, b)
        let invite = try #require(try await b.engine.spaces.invites().first)
        #expect(invite.spaceName == "Family" && invite.from.username == "owner")
        guard case .ok = await b.api.acceptInvite(invite.id) else {
            Issue.record("accept failed")
            throw CancellationError()
        }
        await syncAll(b, o)
        return (o, b, family)
    }

    @Test func aSharedSpacesNotesReachTheMemberWhoJoinedAndTheOwnerKeepsThem() async throws {
        let (o, b, family) = try await shared()
        #expect(try await b.engine.spaces.invites().isEmpty)
        #expect(try await b.notes.list(space: family).map(\.title) == ["Groceries"])
        #expect(try await o.notes.list(space: family).map(\.title) == ["Groceries"])
        #expect(try await o.engine.store.feeds() == [family])
        let space = try #require(try await b.engine.spaces.list().first { $0.id == family })
        #expect(space.shared?.role == "member")
        #expect(space.shared?.members.map(\.username) == ["owner", "ben"])
    }

    @Test func editsGoBothWaysAndNameWhoMadeThem() async throws {
        let (o, b, family) = try await shared()
        let benId = try await benId(o)
        // Named by the name they chose, once the space's members are read again.
        guard case .ok = await b.api.setDisplayName("Ben B") else { throw CancellationError() }
        try await b.notes.save("n", title: "Groceries", content: "Milk, eggs\n")
        await syncAll(b, o)
        let n = try #require(try await o.note("n"))
        #expect(n.content == "Milk, eggs\n" && n.updatedBy == benId)
        let news = try #require(try await o.engine.spaces.news()[family])
        #expect(news.count == 1 && news.names == ["Ben B"])
        try await o.engine.spaces.visit(family)
        #expect(try await o.engine.spaces.news().isEmpty)

        try await o.notes.save("m", title: "Chores", content: "Dishes\n", spaceId: family)
        await syncAll(o, b)
        #expect(try await b.note("m")?.title == "Chores")
        #expect(try await b.engine.spaces.news()[family]?.names == ["owner"])
    }

    @Test func notesMoveInAndOutOfASharedSpace() async throws {
        let (o, b, family) = try await shared()
        try await o.save("in", "Plans", "Beach\n")
        await syncAll(o)
        // Edited and moved in one go: the text where the server has it, then the move.
        try await o.notes.save("in", title: "Plans", content: "Beach, hike\n")
        #expect(try await o.notes.move("in", to: family))
        await syncAll(o, b)
        #expect(try await b.note("in")?.content == "Beach, hike\n")
        #expect(try await o.note("in")?.spaceId == family)
        #expect(try await o.notes.outbox().isEmpty)

        // Out into ben's own space: gone for the owner, who can't follow it there.
        #expect(try await b.notes.move("in", to: Spaces.defaultId))
        await syncAll(b, o)
        #expect(try await b.note("in")?.spaceId == Spaces.defaultId)
        #expect(try await b.note("in")?.content == "Beach, hike\n")
        #expect(try await o.note("in")?.deletedAt != nil)
    }

    @Test func aNoteFiledIntoASpaceSharedSinceGoesToItsFile() async throws {
        let o = try await owner()
        let other = TestClient(server)
        clients.append(other)
        try await other.signIn()
        let b = try await ben()
        let family = try await o.engine.spaces.create("Family")
        await syncAll(o, other)
        // The other device files a note there without knowing it's about to be shared.
        other.offline = true
        try await other.notes.save("late", title: "Late", content: "x\n", spaceId: family)
        guard case .ok = await o.api.inviteToSpace(family, userId: try await benId(o)) else {
            throw CancellationError()
        }
        other.offline = false
        await syncAll(other, o)
        let invite = try #require(try await b.sync() == .done ? b.engine.spaces.invites().first : nil)
        _ = await b.api.acceptInvite(invite.id)
        await syncAll(b)
        #expect(try await b.note("late")?.title == "Late")
        #expect(try await b.note("late")?.spaceId == family)
        #expect(try await other.notes.outbox().isEmpty)
    }

    @Test func aMemberWhoLeavesLosesTheSpacesNotes() async throws {
        let (o, b, family) = try await shared()
        let benId = try await benId(o)
        guard case .ok = await b.api.removeMember(family, userId: benId) else { throw CancellationError() }
        await syncAll(b, o)
        #expect(try await b.note("n") == nil)
        #expect(try await b.engine.store.feeds().isEmpty)
        #expect(try await b.engine.spaces.list().map(\.id) == [Spaces.defaultId])
        // The owner keeps it, private again.
        #expect(try await o.notes.list(space: family).map(\.title) == ["Groceries"])
        #expect(try await o.engine.spaces.list().first { $0.id == family }?.traits.isEmpty == true)
    }

    @Test func picturesInASharedSpaceGoToItsFileAndLoadFromTheAccountsAddress() async throws {
        let (o, b, family) = try await shared()
        let id = try await o.images.add(ImagesTests.png, type: "image/png")
        try await o.notes.save(
            "pic", title: "Pic", content: ImageRef(id: id, width: 1, height: 1).markdown() + "\n", spaceId: family)
        await syncAll(o, b)
        #expect(try await o.images.pending().isEmpty)
        #expect(await b.images.load(id)?.bytes == ImagesTests.png)
    }
}
