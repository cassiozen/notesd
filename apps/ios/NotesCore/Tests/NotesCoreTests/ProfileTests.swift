import Foundation
import Testing

@testable import NotesCore

/// An 8×8 JPEG: what the server takes as a photo.
private let jpeg = Data(
    base64Encoded:
        "/9j/2wCEABALDA4MChAODQ4SERATGCgaGBYWGDEjJR0oOjM9PDkzODdASFxOQERXRTc4UG1RV19iZ2hnPk1xeXBkeFxlZ2MBERISGBUYLxoaL2NCOEJjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY//AABEIAAgACAMBIgACEQEDEQH/xAGiAAABBQEBAQEBAQAAAAAAAAAAAQIDBAUGBwgJCgsQAAIBAwMCBAMFBQQEAAABfQECAwAEEQUSITFBBhNRYQcicRQygZGhCCNCscEVUtHwJDNicoIJChYXGBkaJSYnKCkqNDU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6g4SFhoeIiYqSk5SVlpeYmZqio6Slpqeoqaqys7S1tre4ubrCw8TFxsfIycrS09TV1tfY2drh4uPk5ebn6Onq8fLz9PX29/j5+gEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoLEQACAQIEBAMEBwUEBAABAncAAQIDEQQFITEGEkFRB2FxEyIygQgUQpGhscEJIzNS8BVictEKFiQ04SXxFxgZGiYnKCkqNTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqCg4SFhoeIiYqSk5SVlpeYmZqio6Slpqeoqaqys7S1tre4ubrCw8TFxsfIycrS09TV1tfY2dri4+Tl5ufo6ery8/T19vf4+fr/2gAMAwEAAhEDEQA/AJqKKK889Q//2Q=="
)!

/// A name and photo set from a device, against a real notesd.
@Suite(.serialized, .timeLimit(.minutes(2)))
final class ProfileTests {
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

    @Test func aNameAndPhotoAreSetReadAndRemoved() async throws {
        let a = try await client()
        let before = try #require(await a.api.session().value)
        #expect(before.profile.displayName == "")
        #expect(before.profile.avatar == "")
        #expect(before.profile.userId > 0)
        #expect(before.profile.name(before.username) == TestServer.username)

        #expect(await a.api.setDisplayName("  Maria ").value?.displayName == "Maria")
        let withPhoto = try #require(await a.api.setAvatar(jpeg).value)
        #expect(!withPhoto.avatar.isEmpty)
        #expect(withPhoto.displayName == "Maria")

        // Another device sees it as of now.
        let b = try await client()
        let seen = try #require(await b.api.session().value)
        #expect(seen.profile == withPhoto)
        let photo = try #require(await b.api.avatar(userId: withPhoto.userId, version: withPhoto.avatar).value)
        #expect(photo.prefix(2) == Data([0xFF, 0xD8]))

        let twoLines = await a.api.setDisplayName("Two\nlines")
        guard case .rejected(400, _, _, let field) = twoLines, field == "displayName" else {
            Issue.record("a name on two lines: \(twoLines)")
            return
        }
        #expect(await a.api.removeAvatar().value?.avatar == "")
        // The old version's URL may still be cached (it never changes); what says it's gone is the profile.
        #expect(await b.api.session().value?.profile.avatar == "")
    }
}
