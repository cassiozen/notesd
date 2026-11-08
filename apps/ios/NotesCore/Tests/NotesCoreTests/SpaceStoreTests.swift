import Foundation
import Testing

@testable import NotesCore

/// Space traits (§7.8) on this device's copy of the spaces.
struct SpaceStoreTests {
    let fixture = StoreFixture()
    let spaces: SpaceStore

    init() {
        spaces = SpaceStore(db: fixture.db)
    }

    @Test func traitsComeFromTheServerEvenWhileARenameWaits() async throws {
        let id = try await spaces.create("Work")
        let shared = SpaceTrait(key: "shared", label: "Shared", summary: "With Ana", exposure: "Visible to Ana")
        try await spaces.replace([
            RemoteSpace(id: Spaces.defaultId, name: "Notes"), RemoteSpace(id: id, name: "Office", traits: [shared]),
        ])
        let work = try #require(try await spaces.list().first { $0.id == id })
        #expect(work.name == "Work", "the rename still waits")
        #expect(work.traits == [shared])

        try await spaces.replace([
            RemoteSpace(id: Spaces.defaultId, name: "Notes"), RemoteSpace(id: id, name: "Office"),
        ])
        #expect(try await spaces.list().first { $0.id == id }?.traits == [])
    }

    @Test func traitsComeWithTheAnswerToASentChange() async throws {
        let id = try await spaces.create("Work")
        let change = try #require(try await spaces.pending().first)
        try await spaces.update(id, name: "Play")
        let agent = SpaceTrait(key: "agent", label: "Agent", summary: "On")
        try await spaces.sent(change, stored: RemoteSpace(id: id, name: "Work", traits: [agent]))
        let space = try #require(try await spaces.list().first { $0.id == id })
        #expect(space.name == "Play", "changed again since it went: still waiting")
        #expect(space.traits == [agent])
    }

    @Test func traitsALaterReleaseWroteDontBreakTheList() async throws {
        try await fixture.db.write { sql in
            try sql.exec("UPDATE spaces SET traits = ? WHERE id = ?", "{not a list", Spaces.defaultId)
        }
        #expect(try await spaces.list().map(\.traits) == [[]])
    }

    @Test func traitsAreReadAsSentAndAsNoneFromAnOlderServer() throws {
        let json = #"""
            {"id":"s","name":"Family","createdAt":1,"noteCount":2,"traits":[
                {"key":"shared","label":"Shared","summary":"With Ana","exposure":"Visible to Ana","since":3},
                {"key":"published","label":"Published","summary":"Off the air","problem":"Publishing failed"}
            ]}
            """#
        let space = try JSONDecoder().decode(RemoteSpace.self, from: Data(json.utf8))
        #expect(
            space.traits == [
                SpaceTrait(key: "shared", label: "Shared", summary: "With Ana", exposure: "Visible to Ana"),
                SpaceTrait(key: "published", label: "Published", summary: "Off the air", problem: "Publishing failed"),
            ])
        let old = #"{"id":"s","name":"Family","createdAt":1,"noteCount":2}"#
        #expect(try JSONDecoder().decode(RemoteSpace.self, from: Data(old.utf8)).traits == [])
    }
}
