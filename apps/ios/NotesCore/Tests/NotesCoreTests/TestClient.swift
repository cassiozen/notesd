import Foundation
import Synchronization

@testable import NotesCore

/// What a test compares: a live note's user-visible state.
struct NoteView: Hashable, CustomStringConvertible {
    var id: String
    var title: String
    var content: String
    var conflictOf: String?

    var description: String { "\(id) \(title.quoted) \(content.quoted) \(conflictOf ?? "-")" }
}

/// The real network, with switches to make it misbehave.
final class FaultyTransport: HTTPTransport {
    enum Kind { case failBefore, loseResponse, before, answer }

    struct Fault {
        var match: @Sendable (URLRequest) -> Bool
        var kind: Kind
        var action: (@Sendable () async -> Void)?
        /// `answer`: what something in front of the server says instead (status, body).
        var reply: (Int, String)?
    }

    private struct State {
        var offline = false
        var faults: [Fault] = []
        var sentNotes: [(String, String)] = []
    }

    private let state = Mutex(State())

    var offline: Bool {
        get { state.withLock { $0.offline } }
        set { state.withLock { $0.offline = newValue } }
    }

    /// The JSON bodies of note requests that went out, as "METHOD path" and body.
    var sentNotes: [(String, String)] { state.withLock { $0.sentNotes } }

    func add(_ fault: Fault) {
        state.withLock { $0.faults.append(fault) }
    }

    func clearFaults() {
        state.withLock { $0.faults.removeAll() }
    }

    func send(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        let fault: Fault? = try state.withLock { state in
            if state.offline { throw URLError(.notConnectedToInternet) }
            guard let i = state.faults.firstIndex(where: { $0.match(request) }) else { return nil }
            return state.faults.remove(at: i)
        }
        switch fault?.kind {
        case .failBefore: throw URLError(.networkConnectionLost)
        case .answer:
            let (status, body) = fault?.reply ?? (500, "")
            let response = HTTPURLResponse(
                url: request.url!, statusCode: status, httpVersion: nil, headerFields: ["Content-Type": "text/html"])!
            return (Data(body.utf8), response)
        case .before: await fault?.action?()
        default: break
        }
        let path = request.url?.path() ?? ""
        if path.hasPrefix("/api/v1/notes"), let body = request.httpBody {
            let entry = ("\(request.httpMethod ?? "") \(path)", String(decoding: body, as: UTF8.self))
            state.withLock { $0.sentNotes.append(entry) }
        }
        let reply = try await URLSessionTransport.standard.send(request)
        if fault?.kind == .loseResponse { throw URLError(.networkConnectionLost) }
        return reply
    }
}

/// Tokens in memory.
final class MemoryCredentials: Credentials {
    private let stored = Mutex<Tokens?>(nil)

    var current: Tokens? {
        get { stored.withLock { $0 } }
        set { stored.withLock { $0 = newValue } }
    }

    func tokens() async -> Tokens? { current }

    func refreshed(_ tokens: Tokens, replacing old: Tokens) async {
        stored.withLock { if $0?.refresh == old.refresh { $0 = tokens } }
    }

    func revoked(_ old: Tokens) async {
        stored.withLock { if $0?.refresh == old.refresh { $0 = nil } }
    }
}

/// One device: its own database, session and sync engine against `server`,
/// plus switches to make the network misbehave.
final class TestClient: @unchecked Sendable {
    private let server: TestServer
    private let pageSize: Int
    private let dir: URL
    private(set) var db: Database
    private(set) var notes: NoteStore
    private(set) var engine: SyncEngine
    private(set) var images: ImageStore
    private(set) var events: [SyncEvent] = []
    let transport = FaultyTransport()
    private let credentials = MemoryCredentials()

    init(_ server: TestServer, pageSize: Int = 100) {
        self.server = server
        self.pageSize = pageSize
        dir = FileManager.default.temporaryDirectory.appending(path: "notes-client-\(UUID().uuidString)")
        try! FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        db = Database(path: dir.appending(path: "notes.db").path)
        notes = NoteStore(db: db)
        images = ImageStore(db: db, pendingDir: dir, cacheDir: dir, api: { nil })
        engine = SyncEngine(db: db, api: { nil }, pageSize: pageSize)
        images = newImages()
        engine = newEngine()
    }

    var api: ApiClient { ApiClient(baseURL: server.url, credentials: credentials, transport: transport) }

    private func newImages() -> ImageStore {
        ImageStore(
            db: db, pendingDir: dir.appending(path: "images"), cacheDir: dir.appending(path: "cache/images"),
            api: { [weak self] in self?.api })
    }

    private func newEngine() -> SyncEngine {
        SyncEngine(db: db, api: { [weak self] in self?.api }, images: images, pageSize: pageSize)
    }

    var offline: Bool {
        get { transport.offline }
        set { transport.offline = newValue }
    }

    /// The next matching request never reaches the server.
    func failNext(_ match: @escaping @Sendable (URLRequest) -> Bool) {
        transport.add(.init(match: match, kind: .failBefore))
    }

    /// The next matching request reaches the server, but its response is lost.
    func loseNextResponse(_ match: @escaping @Sendable (URLRequest) -> Bool) {
        transport.add(.init(match: match, kind: .loseResponse))
    }

    /// The next matching request is answered by something that isn't notesd (a portal, a proxy).
    func answerNext(
        _ match: @escaping @Sendable (URLRequest) -> Bool, status: Int = 200, body: String = "<html>Sign in</html>"
    ) {
        transport.add(.init(match: match, kind: .answer, reply: (status, body)))
    }

    /// Runs `action` just before the next matching request goes out.
    func beforeNext(
        _ match: @escaping @Sendable (URLRequest) -> Bool, then action: @escaping @Sendable () async -> Void
    ) {
        transport.add(.init(match: match, kind: .before, action: action))
    }

    func clearFaults() { transport.clearFaults() }

    @discardableResult
    func signIn() async throws -> TestClient {
        let result = await ApiClient(baseURL: server.url, transport: transport)
            .login(username: TestServer.username, password: TestServer.password, deviceName: "test")
        guard case .ok(let signIn) = result else { throw CancellationError() }
        credentials.current = signIn.tokens
        return self
    }

    /// One sync cycle, collecting what it tells the user into `events`.
    @discardableResult
    func sync() async -> SyncOutcome {
        let report = await engine.sync()
        events += report.events
        return report.outcome
    }

    /// Like killing the app and starting it again: new database connections and engine, same files.
    func restart() async {
        await db.close()
        db = Database(path: dir.appending(path: "notes.db").path)
        notes = NoteStore(db: db)
        images = newImages()
        engine = newEngine()
    }

    @discardableResult
    func save(_ id: String, _ title: String, _ content: String) async throws -> Note? {
        try await notes.save(id, title: title, content: content)
    }

    func live() async throws -> Set<NoteView> {
        var out = Set<NoteView>()
        for summary in try await notes.list() {
            let n = try await notes.get(summary.id)!
            out.insert(NoteView(id: n.id, title: n.title, content: n.content, conflictOf: n.conflictOf))
        }
        return out
    }

    func note(_ id: String) async throws -> Note? { try await notes.get(id) }

    deinit {
        try? FileManager.default.removeItem(at: dir)
    }
}

/// A Sendable counter for closures that run on the network path.
final class Counter: Sendable {
    private let value = Mutex(0)

    var count: Int { value.withLock { $0 } }

    func increment() { value.withLock { $0 += 1 } }
}

extension URLRequest {
    var path: String { url?.path() ?? "" }
    var method: String { httpMethod ?? "GET" }
}
