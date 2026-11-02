import Foundation
import Synchronization
import Testing

@testable import NotesCore

/// Answers requests from a handler and records them, in place of a server.
final class StubTransport: HTTPTransport {
    typealias Handler = @Sendable (URLRequest) throws -> (Int, String, [String: String])

    private let handler: Mutex<Handler>
    private let recorded = Mutex<[URLRequest]>([])

    init(_ handler: @escaping Handler = { _ in (500, "", [:]) }) {
        self.handler = Mutex(handler)
    }

    /// Answers in order, one per request.
    convenience init(queue replies: [(Int, String, [String: String])]) {
        let queue = Mutex(replies)
        self.init { _ in queue.withLock { $0.isEmpty ? (500, "", [:]) : $0.removeFirst() } }
    }

    var requests: [URLRequest] { recorded.withLock { $0 } }

    func send(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        recorded.withLock { $0.append(request) }
        let (code, body, headers) = try handler.withLock { $0 }(request)
        let response = HTTPURLResponse(url: request.url!, statusCode: code, httpVersion: nil, headerFields: headers)!
        return (Data(body.utf8), response)
    }
}

private let jsonHeaders = ["Content-Type": "application/json"]
private let unauthenticated = (401, #"{"error":"unauthenticated","message":"authentication required"}"#, jsonHeaders)

private func refreshed(_ access: String, _ now: Int64) -> (Int, String, [String: String]) {
    (
        200,
        #"{"session":{"id":"s","kind":"device","name":"P","createdAt":1,"lastUsedAt":1},"accessToken":"\#(access)","accessExpiresAt":\#(now + 3_600_000),"refreshToken":"r1"}"#,
        jsonHeaders
    )
}

private func body(_ request: URLRequest) -> [String: String] {
    (try? JSONSerialization.jsonObject(with: request.httpBody ?? Data())) as? [String: String] ?? [:]
}

struct ApiClientTests {
    let now: Int64 = 1_000_000
    let creds = MemoryCredentials()

    init() {
        creds.current = Tokens(access: "a1", accessExpiresAt: now + 3_600_000, refresh: "r1")
    }

    private func api(_ transport: StubTransport) -> ApiClient {
        let now = now
        return ApiClient(baseURL: URL(string: "http://127.0.0.1:1/")!, credentials: creds, transport: transport) { now }
    }

    @Test func statusAndLogin() async throws {
        let stub = StubTransport(queue: [
            (200, #"{"setupRequired":false}"#, jsonHeaders),
            (
                200,
                #"{"session":{"id":"s1","kind":"device","name":"Pixel","createdAt":1,"lastUsedAt":2},"username":"me","accessToken":"a","accessExpiresAt":5,"refreshToken":"r","refreshExpiresAt":6}"#,
                jsonHeaders
            ),
        ])
        let api = api(stub)
        #expect(await api.status().value?.setupRequired == false)
        let login = try #require(await api.login(username: "me", password: "secret", deviceName: "Pixel").value)
        #expect(login.tokens == Tokens(access: "a", accessExpiresAt: 5, refresh: "r"))
        let sent = stub.requests[1]
        #expect(sent.path == "/api/v1/auth/login")
        #expect(body(sent) == ["username": "me", "password": "secret", "client": "device", "deviceName": "Pixel"])
    }

    @Test func aWrongPasswordIsARejectionNotALostSession() async throws {
        let stub = StubTransport(queue: [
            (401, #"{"error":"invalid_credentials","message":"invalid username or password"}"#, jsonHeaders)
        ])
        guard
            case .rejected(_, let error, _, _) = await api(stub).login(
                username: "me", password: "nope", deviceName: "P")
        else {
            Issue.record(Comment(rawValue: "expected a rejection"))
            return
        }
        #expect(error == "invalid_credentials")
    }

    @Test func rateLimitingReportsWhenToRetry() async throws {
        let stub = StubTransport(queue: [(429, #"{"error":"rate_limited"}"#, ["Retry-After": "42"])])
        guard case .rateLimited(let seconds) = await api(stub).login(username: "me", password: "x", deviceName: "P")
        else {
            Issue.record(Comment(rawValue: "expected rate limiting"))
            return
        }
        #expect(seconds == 42)
    }

    @Test func anUnreachableServerIsAValue() async throws {
        // Nothing listens on port 1.
        guard case .unreachable = await ApiClient(baseURL: URL(string: "http://127.0.0.1:1/")!).status() else {
            Issue.record(Comment(rawValue: "expected unreachable"))
            return
        }
    }

    @Test func somethingThatIsntNotesd() async throws {
        let stub = StubTransport(queue: [(200, "<html>router</html>", [:])])
        guard case .unexpected(200) = await api(stub).status() else {
            Issue.record(Comment(rawValue: "expected an unexpected answer"))
            return
        }
    }

    @Test func answersNotesdWouldntGiveAreUnexpected() async throws {
        for (answer, status) in [
            ((302, "", ["Location": "https://portal.example/login"]), 302),
            ((403, "<html>sign in to the Wi-Fi</html>", ["Content-Type": "text/html"]), 403),
            ((404, #"{"message":"no error code"}"#, jsonHeaders), 404),
            ((401, "", [:]), 401),
            ((409, "<html>conflict</html>", [:]), 409),
            ((200, #"{"id":"n"}"#, jsonHeaders), 200),
        ] as [((Int, String, [String: String]), Int)] {
            let stub = StubTransport(queue: [answer])
            let result = await api(stub).updateNote("n", NoteRequest(operationId: "o"))
            guard case .unexpected(let got) = result, got == status else {
                Issue.record(Comment(rawValue: "\(answer.0): got \(result)"))
                continue
            }
        }
    }

    @Test func redirectsAreNotFollowed() async throws {
        let server = try FakeResend()
        server.enqueue(302, "", location: server.url + "/elsewhere")
        server.enqueue(200, #"{"setupRequired":false}"#)
        guard case .unexpected(302) = await ApiClient(baseURL: URL(string: server.url)!).status() else {
            Issue.record(Comment(rawValue: "expected the redirect itself"))
            return
        }
        #expect(server.bodies.count == 1)
    }

    @Test func aServerErrorIsNotUnexpected() async throws {
        let stub = StubTransport(queue: [(502, "<html>bad gateway</html>", [:])])
        guard case .rejected(502, "http_502", _, _) = await api(stub).updateNote("n", NoteRequest(operationId: "o"))
        else {
            Issue.record(Comment(rawValue: "expected a server problem"))
            return
        }
    }

    @Test func upgradeRequiredCarriesTheMinimum() async throws {
        let stub = StubTransport(queue: [
            (426, #"{"error":"upgrade_required","message":"update","minVersion":"1.2.0"}"#, jsonHeaders)
        ])
        guard case .upgradeRequired("1.2.0") = await api(stub).sync(since: 0, limit: 1, generation: nil) else {
            Issue.record(Comment(rawValue: "expected upgrade required"))
            return
        }
    }

    @Test func everyRequestSaysWhichAppSentIt() async throws {
        let stub = StubTransport(queue: [(200, #"{"setupRequired":false}"#, jsonHeaders), (204, "", [:])])
        let api = ApiClient(
            baseURL: URL(string: "http://127.0.0.1:1/")!, credentials: creds, transport: stub, clock: { [now] in now },
            version: "1.2.3")
        _ = await api.status()
        _ = await api.logout()
        for request in stub.requests {
            #expect(request.value(forHTTPHeaderField: "X-Notesd-Client") == "ios/1.2.3")
            #expect(request.value(forHTTPHeaderField: "User-Agent") == "notesd-ios/1.2.3")
        }
    }

    @Test func statusMayCarryTheServerVersion() async throws {
        let stub = StubTransport(queue: [
            (200, #"{"setupRequired":false,"serverVersion":"v1.0.0","apiVersion":1}"#, jsonHeaders),
            (200, #"{"setupRequired":true}"#, jsonHeaders),
        ])
        let api = api(stub)
        let status = try #require(await api.status().value)
        #expect(status.serverVersion == "v1.0.0")
        #expect(status.apiVersion == 1)
        let old = try #require(await api.status().value)
        #expect(old.serverVersion == nil && old.apiVersion == nil)
    }

    @Test func successBodiesAreChecked() async throws {
        let id = String(repeating: "a", count: 64)
        let stub = StubTransport(queue: [
            (200, "", [:]),
            (204, "", [:]),
            (200, "<html>ok</html>", [:]),
            (201, #"{"id":"\#(id)","contentType":"image/png","size":1,"createdAt":1}"#, jsonHeaders),
        ])
        let api = api(stub)
        guard case .unexpected(200) = await api.deleteSpace("s") else {
            Issue.record(Comment(rawValue: "a space delete answers 204"))
            return
        }
        guard case .ok = await api.deleteSpace("s") else {
            Issue.record(Comment(rawValue: "expected ok"))
            return
        }
        guard case .unexpected(200) = await api.putImage(id, bytes: Data([1]), type: "image/png") else {
            Issue.record(Comment(rawValue: "an image upload answers its JSON"))
            return
        }
        guard case .ok = await api.putImage(id, bytes: Data([1]), type: "image/png") else {
            Issue.record(Comment(rawValue: "expected ok"))
            return
        }
    }

    @Test func a401RefreshesOnceAndResendsTheSameRequest() async throws {
        let stub = StubTransport(queue: [unauthenticated, refreshed("a2", now), (204, "", [:])])
        guard case .ok = await api(stub).logout() else {
            Issue.record(Comment(rawValue: "expected ok"))
            return
        }
        let (first, refresh, retry) = (stub.requests[0], stub.requests[1], stub.requests[2])
        #expect(first.value(forHTTPHeaderField: "Authorization") == "Bearer a1")
        #expect(refresh.path == "/api/v1/auth/refresh")
        #expect(body(refresh) == ["refreshToken": "r1"])
        #expect(retry.value(forHTTPHeaderField: "Authorization") == "Bearer a2")
        #expect(first.httpBody == retry.httpBody)
        #expect(creds.current?.access == "a2")
    }

    @Test func aTokenAboutToExpireIsRefreshedFirst() async throws {
        creds.current = Tokens(access: "a1", accessExpiresAt: now + 30_000, refresh: "r1")
        let stub = StubTransport(queue: [refreshed("a2", now), (204, "", [:])])
        guard case .ok = await api(stub).logout() else {
            Issue.record(Comment(rawValue: "expected ok"))
            return
        }
        #expect(stub.requests[0].path == "/api/v1/auth/refresh")
        #expect(stub.requests[1].value(forHTTPHeaderField: "Authorization") == "Bearer a2")
    }

    @Test func aRefusedRefreshEndsTheSession() async throws {
        let stub = StubTransport(queue: [unauthenticated, unauthenticated])
        guard case .unauthenticated = await api(stub).logout() else {
            Issue.record(Comment(rawValue: "expected signed out"))
            return
        }
        #expect(creds.current == nil)
        #expect(stub.requests.count == 2)
    }

    @Test func aRefreshThatCantReachTheServerKeepsTheSession() async throws {
        creds.current = Tokens(access: "a1", accessExpiresAt: now + 30_000, refresh: "r1")
        let stub = StubTransport { _ in throw URLError(.cannotConnectToHost) }
        guard case .unreachable = await api(stub).logout() else {
            Issue.record(Comment(rawValue: "expected unreachable"))
            return
        }
        #expect(creds.current?.access == "a1")
    }

    @Test func concurrent401sShareOneRefresh() async throws {
        // Answered by what each request is, not arrival order: the calls race, so
        // one may refresh before the other's first request reaches the server.
        let now = now
        let stub = StubTransport { request in
            if request.path == "/api/v1/auth/refresh" { return refreshed("a2", now) }
            if request.value(forHTTPHeaderField: "Authorization") == "Bearer a2" { return (204, "", [:]) }
            return unauthenticated
        }
        let api = api(stub)
        async let one = api.logout()
        async let two = api.logout()
        let results = await [one, two]
        for result in results {
            guard case .ok = result else {
                Issue.record(Comment(rawValue: "expected ok: \(result)"))
                return
            }
        }
        #expect(stub.requests.filter { $0.path == "/api/v1/auth/refresh" }.count == 1)
    }

    @Test func aSignInWithoutUsableTokensIsRejected() async throws {
        let session = #""session":{"id":"s","kind":"device","name":"P","createdAt":1,"lastUsedAt":1}"#
        for answer in [
            #"{\#(session),"username":"me"}"#,
            #"{\#(session),"accessToken":"","accessExpiresAt":5,"refreshToken":"r"}"#,
            #"{\#(session),"accessToken":"a","accessExpiresAt":5,"refreshToken":""}"#,
            #"{\#(session),"accessToken":"a","accessExpiresAt":0,"refreshToken":"r"}"#,
        ] {
            let stub = StubTransport(queue: [(200, answer, jsonHeaders)])
            guard
                case .unexpected(let status) = await api(stub).login(
                    username: "me", password: "x", deviceName: "P")
            else {
                Issue.record(Comment(rawValue: "expected an unexpected answer for \(answer)"))
                continue
            }
            #expect(status == 200)
        }
    }

    @Test func aRefreshWithoutAnAccessTokenKeepsTheSession() async throws {
        let stub = StubTransport(queue: [unauthenticated, (200, #"{"accessExpiresAt":5}"#, jsonHeaders)])
        guard case .unexpected = await api(stub).logout() else {
            Issue.record(Comment(rawValue: "expected an unexpected answer"))
            return
        }
        #expect(creds.current?.access == "a1")
        #expect(stub.requests.count == 2)
    }

    @Test func aSignInDuringARefreshIsNotOverwritten() async throws {
        let newSession = Tokens(access: "b1", accessExpiresAt: now + 3_600_000, refresh: "rb")
        let creds = creds
        let now = now
        let stub = StubTransport { request in
            guard request.path == "/api/v1/auth/refresh" else { return unauthenticated }
            creds.current = newSession
            return refreshed("a2", now)
        }
        _ = await api(stub).logout()
        #expect(creds.current == newSession)
    }

    @Test func aSignInDuringARefusedRefreshIsNotSignedOut() async throws {
        let newSession = Tokens(access: "b1", accessExpiresAt: now + 3_600_000, refresh: "rb")
        let creds = creds
        let stub = StubTransport { request in
            if request.path == "/api/v1/auth/refresh" { creds.current = newSession }
            return unauthenticated
        }
        _ = await api(stub).logout()
        #expect(creds.current == newSession)
    }

    @Test func noSessionNoRequest() async throws {
        let stub = StubTransport()
        guard case .unauthenticated = await ApiClient(baseURL: URL(string: "http://x/")!, transport: stub).logout()
        else {
            Issue.record(Comment(rawValue: "expected signed out"))
            return
        }
        #expect(stub.requests.isEmpty)
    }
}

struct ServerAddressTests {
    private func url(_ input: String) -> String? {
        if case .valid(let url) = ServerAddress.parse(input) { return url.absoluteString }
        return nil
    }

    private func invalid(_ input: String) -> Bool {
        if case .invalid = ServerAddress.parse(input) { return true }
        return false
    }

    @Test func bareHostsGetASchemeByWhereTheyAre() {
        #expect(url("notes.example.com") == "https://notes.example.com/")
        #expect(url("  notes.example.com/ ") == "https://notes.example.com/")
        #expect(url("192.168.1.5:8080") == "http://192.168.1.5:8080/")
        #expect(url("10.0.2.2:8080") == "http://10.0.2.2:8080/")
        #expect(url("nas.local") == "http://nas.local/")
        #expect(url("localhost:8080") == "http://localhost:8080/")
        #expect(url("example.com/notes/") == "https://example.com/notes")
        #expect(url("Notes.Example.com:443") == "https://notes.example.com/")
    }

    @Test func explicitSchemesAreKeptButHttpOnlyOnAPrivateNetwork() {
        #expect(url("https://192.168.1.5") == "https://192.168.1.5/")
        #expect(url("http://172.20.0.1") == "http://172.20.0.1/")
        #expect(invalid("http://notes.example.com"))
        #expect(invalid("http://172.32.0.1"))
        #expect(invalid("http://8.8.8.8"))
    }

    @Test func nonsenseIsRefused() {
        for input in ["", "   ", "ftp://x", "https://", "a b", "example.com/?q=1"] {
            #expect(invalid(input), "\(input)")
        }
    }

    @Test func privateRanges() {
        for h in [
            "10.1.2.3", "127.0.0.1", "172.16.0.1", "172.31.255.255", "192.168.0.1", "169.254.1.1", "::1", "fd00::1",
            "fe80::1",
        ] {
            #expect(ServerAddress.isPrivate(h), "\(h)")
        }
        for h in [
            "172.15.0.1", "172.32.0.1", "192.169.0.1", "8.8.8.8", "example.com", "local", "2001:db8::1", "10.0.0.256",
        ] {
            #expect(!ServerAddress.isPrivate(h), "\(h)")
        }
    }
}
