import Testing

@testable import Notes
@testable import NotesCore

/// What publishing's refusals say (§7.10), the web's words.
struct PublishingTests {
    @Test func refusalsInWords() {
        let taken: ApiResult<Void> = .conflict(code: "handle_taken", message: "", current: nil)
        #expect(Publishing.message(taken) == "Another space has that address. Try another.")
        let invalid: ApiResult<Void> = .rejected(status: 400, error: "invalid_request", message: "", field: "handle")
        #expect(Publishing.message(invalid) == Publishing.handleRules)
        let locked: ApiResult<Void> = .conflict(code: "space_has_locked", message: "", current: nil)
        #expect(Publishing.message(locked) == "Unlock its locked notes to publish it.")
        #expect(Publishing.message(locked, locked: 1) == "Unlock its locked note to publish it.")
        #expect(Publishing.message(locked, locked: 3) == "Unlock its 3 locked notes to publish it.")
        let owner: ApiResult<Void> = .rejected(status: 403, error: "not_space_owner", message: "", field: nil)
        #expect(Publishing.message(owner) == "Only the space’s owner can do that.")
        #expect(Publishing.message(ApiResult<Void>.ok(())) == nil)
    }

    @Test func theAddressWithoutItsScheme() {
        let s = PublishSettings(
            handle: "garden", url: "https://n.example:8443/@garden/", origin: "https://n.example:8443", published: true)
        #expect(Publishing.shown(s) == "n.example:8443/@garden")
    }

    @Test func turningOnShowsAtOnceInTheServersOrderAndOffKeepsTheLockRules() {
        let shared = SpaceTrait(key: "shared", label: "Shared", summary: "With Ana", exposure: "Visible to Ana")
        let agent = SpaceTrait(key: "agent", label: "Agent", summary: "On this device")
        let published = SpaceTrait(key: "published", label: "Published", summary: "At n.example/@g", exposure: "Public")
        func space(_ traits: SpaceTrait...) -> Space { Space(id: "s", name: "Garden", noteCount: 0, traits: traits) }

        let on = Publishing.shown(space(shared, agent), true)
        #expect(on.traits.map(\.key) == ["shared", "published", "agent"])
        #expect(on.traits[1].summary == "Publishing…")
        #expect(Traits.exposing(Publishing.shown(space(), true))?.key == "published")

        let off = Publishing.shown(space(shared, published), false)
        #expect(off.traits == [shared])
        #expect(off.leaving == [published])
        #expect(Traits.exposing(Publishing.shown(space(published), false)) == published)

        // What the list says already wins.
        #expect(Publishing.shown(space(published), true) == space(published))
        #expect(Publishing.shown(space(), false) == space())
        #expect(Publishing.shown(space(published), nil) == space(published))
    }
}
