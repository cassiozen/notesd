import Testing

@testable import Notes
@testable import NotesCore

struct SpaceSwitcherTests {
    let shared = SpaceTrait(key: "shared", label: "Shared", summary: "With Ana and Ben", exposure: "Visible to Ana")
    let failing = SpaceTrait(
        key: "published", label: "Published", summary: "family.example", problem: "Publishing failed")
    let agent = SpaceTrait(key: "agent", label: "Agent")

    func space(_ id: String, _ traits: [SpaceTrait] = []) -> Space {
        Space(id: id, name: id.capitalized, noteCount: 0, traits: traits)
    }

    @Test func theCardHasALinePerTraitWithItsSummaryOrProblem() {
        #expect(
            Switcher.cardLines(space("family", [shared, failing, agent])) == [
                SwitcherLine(symbol: "person.2", text: "Shared · With Ana and Ben"),
                SwitcherLine(symbol: "globe", text: "Publishing failed", problem: true),
                SwitcherLine(symbol: "sparkles", text: "Agent"),
            ])
    }

    @Test func aSpaceWithoutTraitsIsOnlyYou() {
        #expect(Switcher.cardLines(space("notes")) == [SwitcherLine(text: "Only you")])
        #expect(Switcher.detail(space("notes"), change: nil) == .onlyYou)
        #expect(Switcher.spoken(space("notes"), change: nil) == "Notes, only you")
    }

    @Test func aRowNamesItsTraitsOrTheirProblems() {
        #expect(
            Switcher.detail(space("family", [shared, failing]), change: nil)
                == .traits([
                    SwitcherLine(symbol: "person.2", text: "Shared"),
                    SwitcherLine(symbol: "globe", text: "Publishing failed", problem: true),
                ]))
        let family = space("family", [shared, failing])
        #expect(Switcher.spoken(family, change: nil) == "Family, Shared, Publishing failed")
    }

    @Test func whatChangedTakesTheRowAfterTheGlyphs() {
        let work = space("work", [shared])
        #expect(
            Switcher.detail(work, change: "Ben edited 3 notes · 2h")
                == .change(glyphs: [SwitcherLine(symbol: "person.2", text: "")], text: "Ben edited 3 notes · 2h"))
        #expect(Switcher.spoken(work, change: "Ben edited 3 notes · 2h") == "Work, Ben edited 3 notes · 2h")
        let edited = "Ana edited 1 note"
        #expect(Switcher.detail(space("notes"), change: edited) == .change(glyphs: [], text: edited))
    }

    @Test func theCurrentSpaceIsntListedAndTheOthersKeepTheirOrder() {
        let spaces = [space("default"), space("family"), space("health"), space("work")]
        #expect(Switcher.others(spaces, current: "health").map(\.id) == ["default", "family", "work"])
        #expect(Switcher.others([space("default")], current: "default").isEmpty, "one space: no Switch to")
    }
}
