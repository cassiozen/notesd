import Foundation
import Testing

@testable import NotesCore

/// Changing the account's password from a device, against a real notesd.
@Suite(.serialized, .timeLimit(.minutes(2)))
final class AccountPasswordTests {
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

    @Test func aWrongCurrentPasswordIsRefusedWithoutSigningOut() async throws {
        let a = try await client()
        let result = await a.api.changePassword(current: "not it", new: "a brand new one")
        guard case .rejected(401, "invalid_credentials", _, _) = result else {
            Issue.record("expected invalid_credentials, got \(result)")
            return
        }
        // Still signed in: the refusal isn't read as a lost session.
        #expect(await a.api.emailSettings().value != nil)
    }

    @Test func theNewPasswordSignsOutOtherDevicesButNotThisOne() async throws {
        let a = try await client()
        let b = try await client()
        let result = await a.api.changePassword(current: TestServer.password, new: "a brand new one")
        guard case .ok = result else {
            Issue.record("expected ok, got \(result)")
            return
        }
        #expect(await a.api.emailSettings().value != nil)
        guard case .unauthenticated = await b.api.emailSettings() else {
            Issue.record("the other device is still signed in")
            return
        }
        let again = await ApiClient(baseURL: server.url)
            .login(username: TestServer.username, password: "a brand new one", deviceName: "test")
        #expect(again.value != nil)
    }

    @Test func emailSettingsSayWhetherThisAccountMayChangeThem() async throws {
        let owner = try await client()
        #expect(await owner.api.emailSettings().value?.editable == true)
        // An older server, without the field: its one account is the owner.
        let old = try JSONDecoder().decode(EmailSettings.self, from: Data(#"{"configured":false}"#.utf8))
        #expect(old.editable)
    }
}
