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

    @Test func aSpaceWithoutTraitsGoesUnsaid() {
        #expect(Switcher.cardLines(space("notes")).isEmpty)
        #expect(Switcher.detail(space("notes"), change: nil) == nil)
        #expect(Switcher.spoken(space("notes"), change: nil) == "Notes")
    }

    @Test func aRowIsOneLineUnlessATraitHasAProblem() {
        #expect(Switcher.detail(space("work", [shared, agent]), change: nil) == nil, "the glyphs say it")
        #expect(Switcher.spoken(space("work", [shared]), change: nil) == "Work, Shared")
        let family = space("family", [shared, failing])
        #expect(Switcher.detail(family, change: nil) == .problems("Publishing failed"))
        #expect(Switcher.spoken(family, change: nil) == "Family, Shared, Published: Publishing failed")
        var broken = agent
        broken.problem = "Agent stopped"
        #expect(
            Switcher.detail(space("family", [shared, failing, broken]), change: nil)
                == .problems("Publishing failed · Agent stopped"))
    }

    @Test func whatChangedTakesTheSecondLine() {
        let news = "Ana edited 2 notes · 2h"
        let family = space("family", [shared, failing])
        #expect(Switcher.detail(family, change: news) == .change(news), "before a problem")
        #expect(Switcher.spoken(family, change: news) == "Family, Ana edited 2 notes · 2h")
        #expect(Switcher.detail(space("notes"), change: news) == .change(news))
        #expect(
            Switcher.spoken(space("work", [shared]), change: "Ana edited a note · 2h") == "Work, Ana edited a note · 2h"
        )
    }

    @Test func theCurrentSpaceIsntListedAndTheOthersKeepTheirOrder() {
        let spaces = [space("default"), space("family"), space("health"), space("work")]
        #expect(Switcher.others(spaces, current: "health").map(\.id) == ["default", "family", "work"])
        #expect(Switcher.others([space("default")], current: "default").isEmpty, "one space: no Switch to")
    }
}
