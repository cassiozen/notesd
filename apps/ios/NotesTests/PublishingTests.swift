import NotesCore
import Testing

@testable import Notes

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
}
