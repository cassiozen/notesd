import NotesCore
import Testing

@testable import Notes

struct SpaceTraitsTests {
    let shared = SpaceTrait(key: "shared", label: "Shared", summary: "With Ana", exposure: "Visible to Ana")
    let published = SpaceTrait(
        key: "published", label: "Published", summary: "family.example", exposure: "Public at family.example")
    let agent = SpaceTrait(key: "agent", label: "Agent", summary: "On this device")
    let future = SpaceTrait(key: "mirrored", label: "Mirrored", summary: "To Drive", exposure: "Copied to Drive")

    @Test func theRegistryKeepsTheFixedOrder() {
        #expect(Traits.registry.map(\.key) == ["shared", "published", "agent"])
        #expect(Traits.registry.map(\.symbol) == ["person.2", "globe", "sparkles"])
    }

    @Test func anUnknownKeyHasTheGenericGlyphAndTheServersWords() {
        #expect(Traits.symbol("mirrored") == "puzzlepiece.extension")
        #expect(Traits.label(future) == "Mirrored")
        #expect(Traits.label(SpaceTrait(key: "mirrored")) == "mirrored")
        #expect(Traits.label(SpaceTrait(key: "shared")) == "Shared")
    }

    @Test func glyphsAreSpokenAsTheirLabelsWithAnyProblem() {
        var failing = published
        failing.problem = "Publishing failed"
        #expect(Traits.spoken([shared, failing]) == "Shared, Published: Publishing failed")
    }

    @Test func spaceInfoListsKnownTraitsInOrderThenUnknownOnesAsSent() {
        let rows = Traits.rows([future, agent, shared])
        #expect(rows.map(\.id) == ["shared", "agent", "mirrored"])
        guard case .on(_, .none) = rows[2] else {
            Issue.record("an unknown trait has no entry")
            return
        }
    }

    @Test func spaceInfoOffersOnlyTraitsThatCanBeTurnedOn() {
        let registry = [
            TraitKind(key: "shared", label: "Shared", symbol: "person.2", turnOn: { _ in }),
            TraitKind(key: "published", label: "Published", symbol: "globe"),
        ]
        let rows = Traits.rows([], registry: registry)
        #expect(rows.map(\.id) == ["shared"])
        guard case .off = rows[0] else {
            Issue.record("off, offered to turn on")
            return
        }
        #expect(Traits.rows([]).isEmpty, "none can be turned on yet")
    }

    @Test func aMoveAsksOnlyAboutWhatTheDestinationNewlyExposes() {
        #expect(Traits.newExposure(from: [], to: [shared, published, agent]) == [shared, published])
        #expect(Traits.newExposure(from: [shared], to: [shared, published]) == [published])
        #expect(Traits.newExposure(from: [shared, published], to: [shared]).isEmpty, "less exposed")
        #expect(Traits.newExposure(from: [shared], to: [shared]).isEmpty, "the same")
        #expect(Traits.newExposure(from: [], to: [agent]).isEmpty, "kept to the account")
        #expect(Traits.newExposure(from: [], to: [future]) == [future], "unknown keys ask too")
    }

    @Test func aMoveComparesWhoATraitReachesNotItsWords() {
        func with(_ sentence: String, _ ids: [String]) -> SpaceTrait {
            SpaceTrait(key: "shared", label: "Shared", exposure: sentence, audience: ids)
        }
        let anaBen = with("Visible to Ana and Ben", ["user:2", "user:3"])
        let anaCy = with("Visible to Ana and Cy", ["user:2", "user:4"])
        #expect(Traits.newExposure(from: [anaBen], to: [with("Visible to Ben and Ana", ["user:3", "user:2"])]).isEmpty)
        #expect(Traits.newExposure(from: [anaBen], to: [with("Visible to Ana", ["user:2"])]).isEmpty, "fewer people")
        #expect(Traits.newExposure(from: [anaBen], to: [anaCy]) == [anaCy])
    }
}
