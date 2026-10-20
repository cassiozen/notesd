import Foundation
import Testing

@testable import NotesCore

/// Spaces (§7.7) between devices and a real notesd.
@Suite(.serialized, .timeLimit(.minutes(2)))
final class SpacesSyncTests {
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

    private func names(_ c: TestClient) async throws -> [String] {
        try await c.engine.spaces.list().map(\.name)
    }

    private func spaceOf(_ c: TestClient, _ id: String) async throws -> String? {
        try await c.notes.get(id)?.spaceId
    }

    @Test func aSpaceMadeOfflineArrivesWithTheNotesFiledInIt() async throws {
        let a = try await client()
        let b = try await client()
        let work = try await a.engine.spaces.create("  Work  ")
        try await a.notes.save("n", title: "Plan", content: "Ship it #q3\n", spaceId: work)
        await syncAll(a, b)

        #expect(try await names(b) == ["Notes", "Work"])
        #expect(try await spaceOf(b, "n") == work)
        #expect(try await b.notes.list(space: work).map(\.id) == ["n"])
        #expect(try await b.notes.list(space: Spaces.defaultId).isEmpty)
        #expect(try await b.notes.tags(space: work).map(\.key) == ["q3"])
        #expect(try await b.notes.tags(space: Spaces.defaultId).isEmpty)
    }

    @Test func theSameNameMadeOnTwoDevicesGetsANumberOnTheSecond() async throws {
        let a = try await client()
        let b = try await client()
        try await a.engine.spaces.create("Work")
        try await b.engine.spaces.create("work")
        await syncAll(a, b)
        #expect(try await names(a) == ["Notes", "Work", "work 2"])
        #expect(try await names(b) == names(a))
    }

    @Test func aNameDeletedHereAndUsedAgainKeepsIt() async throws {
        let a = try await client()
        let work = try await a.engine.spaces.create("Work")
        await syncAll(a)
        #expect(try await a.engine.spaces.delete(work))
        let again = try await a.engine.spaces.create("Work")
        await syncAll(a)
        #expect(try await names(a) == ["Notes", "Work"])
        #expect(try await a.engine.spaces.list().last?.id == again)
    }

    @Test func aNameDeletedHereWhileItsNotesMoveOutIsKeptByTheSpaceMadeInItsPlace() async throws {
        let a = try await client()
        let b = try await client()
        let old = try await a.engine.spaces.create("Work")
        try await a.notes.save("n", title: "Plan", content: "x\n", spaceId: old)
        await syncAll(a, b)

        // Offline: the note out, the space deleted, a new one by the same name with a note in it.
        #expect(try await a.notes.move("n", to: Spaces.defaultId))
        #expect(try await a.engine.spaces.delete(old))
        let again = try await a.engine.spaces.create("Work")
        try await a.notes.save("m", title: "Standup", content: "y\n", spaceId: again)
        await syncAll(a, b)

        #expect(try await names(a) == ["Notes", "Work"])
        #expect(try await names(b) == ["Notes", "Work"])
        #expect(try await spaceOf(b, "m") == again)
        #expect(try await spaceOf(b, "n") == Spaces.defaultId)
    }

    @Test func afterARestoreANoteDeletedOnBothSidesInDifferentSpacesStaysDeleted() async throws {
        let a = try await client()
        let b = try await client()
        try await a.save("n", "Plan", "x\n")
        await syncAll(a, b)
        server.stop()
        let backup = try server.snapshot()
        try await server.restart()
        // Deleted on a, its cursor run ahead of the backup: after the restore its next sync is a full one.
        try await a.notes.delete("n")
        for v in 1...3 {
            try await a.save("other", "Other", "\(v)\n")
            #expect(await a.sync() == .done)
        }
        server.stop()
        try server.restore(backup)
        try await server.restart()
        try? FileManager.default.removeItem(at: backup)

        // Deleted again on b, after moving it: the server's tombstone is in another space.
        let work = try await b.engine.spaces.create("Work")
        await syncAll(b)
        #expect(try await b.notes.move("n", to: work))
        await syncAll(b)
        try await b.notes.delete("n")
        await syncAll(b)
        await syncAll(a)
        #expect(try await a.live().map(\.title) == ["Other"])
    }

    @Test func afterARestoreANoteOnlyMovedOnTheServerIsTakenWithoutACopy() async throws {
        let a = try await client()
        let b = try await client()
        try await a.save("n", "Plan", "x\n")
        await syncAll(a, b)
        server.stop()
        let backup = try server.snapshot()
        try await server.restart()
        // a's cursor runs ahead of the backup, so after the restore its next sync is a full one.
        for v in 1...3 {
            try await a.save("other", "Other", "\(v)\n")
            #expect(await a.sync() == .done)
        }
        server.stop()
        try server.restore(backup)
        try await server.restart()
        try? FileManager.default.removeItem(at: backup)

        let work = try await b.engine.spaces.create("Work")
        await syncAll(b)
        #expect(try await b.notes.move("n", to: work))
        await syncAll(b)
        await syncAll(a)
        #expect(try await spaceOf(a, "n") == work)
        #expect(try await a.live().map(\.title).sorted() == ["Other", "Plan"])
    }

    @Test func anIconGoesWithTheSpaceAndANewOneReplacesIt() async throws {
        let a = try await client()
        let b = try await client()
        let work = try await a.engine.spaces.create("Work", icon: "duck")
        await syncAll(a, b)
        #expect(try await b.engine.spaces.list().first { $0.id == work }?.icon == "duck")
        #expect(try await b.engine.spaces.list().first { $0.id == Spaces.defaultId }?.icon == "")

        try await b.engine.spaces.update(work, name: "Office", icon: "castle")
        await syncAll(b, a)
        let s = try await a.engine.spaces.list().first { $0.id == work }
        #expect(s?.name == "Office")
        #expect(s?.icon == "castle")
    }

    @Test func aMoveHereAndAnEditThereBothStay() async throws {
        let a = try await client()
        let b = try await client()
        let work = try await a.engine.spaces.create("Work")
        try await a.save("n", "Plan", "base\n")
        await syncAll(a, b)

        #expect(try await a.notes.move("n", to: work))
        try await b.save("n", "Plan", "edited\n")
        await syncAll(a, b)
        for c in [a, b] {
            #expect(try await spaceOf(c, "n") == work)
            #expect(try await c.notes.get("n")?.content == "edited\n")
        }
        #expect(try await a.live().count == 1)
    }

    @Test func movedDifferentlyOnTwoDevicesTheServersSpaceWins() async throws {
        let a = try await client()
        let b = try await client()
        let work = try await a.engine.spaces.create("Work")
        let home = try await a.engine.spaces.create("Home")
        try await a.save("n", "Plan", "base\n")
        await syncAll(a, b)

        try await a.notes.move("n", to: work)
        try await b.notes.move("n", to: home)
        #expect(await a.sync() == .done)
        await syncAll(b, a)
        #expect(try await spaceOf(a, "n") == work)
        #expect(try await spaceOf(b, "n") == work)
        #expect(try await b.live().count == 1)
    }

    @Test func aNoteFiledIntoASpaceDeletedElsewhereLandsInTheDefault() async throws {
        let a = try await client()
        let b = try await client()
        let work = try await a.engine.spaces.create("Work")
        await syncAll(a, b)

        #expect(try await a.engine.spaces.delete(work))
        #expect(await a.sync() == .done)
        try await b.notes.save("n", title: "Late", content: "filed offline\n", spaceId: work)
        await syncAll(b, a)
        #expect(try await spaceOf(b, "n") == Spaces.defaultId)
        #expect(try await spaceOf(a, "n") == Spaces.defaultId)
        #expect(try await names(b) == ["Notes"])
    }

    @Test func aDeleteRefusedBecauseNotesArrivedElsewhereBringsTheSpaceBack() async throws {
        let a = try await client()
        let b = try await client()
        let work = try await a.engine.spaces.create("Work")
        await syncAll(a, b)

        try await b.notes.save("n", title: "Busy", content: "in work\n", spaceId: work)
        #expect(await b.sync() == .done)
        #expect(try await a.engine.spaces.delete(work))
        #expect(try await names(a) == ["Notes"])
        #expect(await a.sync() == .done)
        // The person who deleted it hears why it's back.
        #expect(try await a.engine.store.state().lastError?.contains("“Work” wasn’t deleted") == true)
        await syncAll(a)
        #expect(try await names(a) == ["Notes", "Work"])
        #expect(try await spaceOf(a, "n") == work)
    }

    @Test func notesMovedOutOfflineLetTheSpaceBeDeletedInTheSameSync() async throws {
        let a = try await client()
        let work = try await a.engine.spaces.create("Work")
        try await a.notes.save("n", title: "Plan", content: "x\n", spaceId: work)
        await syncAll(a)

        #expect(try await !a.engine.spaces.delete(work))
        try await a.notes.move("n", to: Spaces.defaultId)
        #expect(try await a.engine.spaces.delete(work))
        await syncAll(a)
        let fresh = try await client()
        await syncAll(fresh)
        #expect(try await names(fresh) == ["Notes"])
        #expect(try await spaceOf(fresh, "n") == Spaces.defaultId)
    }

    @Test func searchCountsTheMatchesInOtherSpaces() async throws {
        let a = try await client()
        let work = try await a.engine.spaces.create("Work")
        try await a.notes.save("h", title: "Garden", content: "home\n")
        try await a.notes.save("w", title: "Garden plan", content: "work\n", spaceId: work)
        #expect(try await a.notes.search("garden", scope: .only(Spaces.defaultId)).map(\.id) == ["h"])
        #expect(try await a.notes.countElsewhere("garden", space: Spaces.defaultId) == 1)
        let elsewhere = try await a.notes.search("garden", scope: .except(Spaces.defaultId))
        #expect(elsewhere.map(\.id) == ["w"])
        #expect(elsewhere.first?.spaceId == work)
    }
}
