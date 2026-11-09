import Foundation
import Testing

@testable import NotesCore

/// Publishing a space (§7.10) against a real notesd: on from the app's own address, a new handle, off.
@Suite(.serialized, .timeLimit(.minutes(2)))
final class PublishingTests {
    let server: TestServer

    init() async throws {
        server = try await TestServer.start()
    }

    @Test func publishesChangesItsAddressAndTakesItDown() async throws {
        let c = try await TestClient(server).signIn()
        let api = c.api
        #expect(await api.putSpace("garden", name: "Garden Notes", icon: "").value != nil)

        let on = try #require(await api.publish("garden", origin: api.origin).value)
        #expect(
            on
                == PublishSettings(
                    handle: "garden-notes", url: "\(api.origin)/@garden-notes/", origin: api.origin, published: true))
        #expect(await api.publishSettings("garden").value == on)
        let garden = try #require(await api.spaces().value?.first { $0.id == "garden" })
        #expect(garden.traits.map(\.key) == ["published"])

        #expect(await api.publish("garden", handle: "days").value?.url == "\(api.origin)/@days/")
        if case .rejected(_, "invalid_request", _, "handle") = await api.publish("garden", handle: "-no-") {
        } else {
            Issue.record("an invalid handle is refused")
        }
        if case .conflict("space_default", _, _) = await api.publish("default", origin: api.origin) {
        } else {
            Issue.record("the default space can't be published")
        }

        #expect(await api.unpublish("garden").value != nil)
        #expect(await api.publishSettings("garden").value?.published == false)
        #expect(await api.spaces().value?.first { $0.id == "garden" }?.traits.isEmpty == true)
    }

    @Test func theOriginIsTheAddressTheAppReachesTheServerBy() {
        func origin(_ url: String) -> String { ApiClient(baseURL: URL(string: url)!).origin }
        #expect(origin("http://127.0.0.1:8080/") == "http://127.0.0.1:8080")
        #expect(origin("https://notes.example/") == "https://notes.example")
        #expect(origin("http://[fd7a::1]:8080/") == "http://[fd7a::1]:8080")
    }
}
