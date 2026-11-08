import Foundation
import Synchronization
import Testing

@testable import NotesCore

/// A real notesd on a free port with an empty data dir, its owner account
/// already created. Restarts keep the port, so clients keep their URL.
///
/// The binary comes from NOTESD_SERVER_BIN (`mise run test:ios:core` builds it);
/// without it, the tests build server/ with Go once per run.
final class TestServer: Sendable {
    static let username = "owner"
    static let password = "correct horse"

    let dataDir: URL
    /// Where notesd sends email (`--resend-url`), when a test fakes Resend.
    private let resendURL: String?
    private let state = Mutex<(process: Process?, listen: String)>((nil, "127.0.0.1:0"))

    var url: URL { URL(string: "http://\(state.withLock { $0.listen })/")! }

    private init(resendURL: String?) {
        self.resendURL = resendURL
        dataDir = FileManager.default.temporaryDirectory.appending(path: "notesd-\(UUID().uuidString)")
        try! FileManager.default.createDirectory(at: dataDir, withIntermediateDirectories: true)
    }

    static func start(resendURL: String? = nil) async throws -> TestServer {
        let server = TestServer(resendURL: resendURL)
        try await server.launch()
        let setup = await ApiClient(baseURL: server.url).setup(username: username, password: password)
        guard case .ok = setup else {
            Issue.record("owner setup failed: \(setup)")
            throw CancellationError()
        }
        return server
    }

    private func launch() async throws {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: try Self.binary())
        process.arguments =
            ["--data-dir", dataDir.path, "--listen", state.withLock { $0.listen }]
            // Pinned: the listen line is parsed, so inherited NOTESD_LOG_* mustn't reshape them.
            + ["--log-level", "info", "--log-format", "text"]
            + (resendURL.map { ["--resend-url", $0] } ?? [])
        // An exported NOTESD_OWNER_* would create the owner at startup, before start() can.
        process.environment = ProcessInfo.processInfo.environment.filter { !$0.key.hasPrefix("NOTESD_OWNER_") }
        let pipe = Pipe()
        process.standardOutput = pipe
        process.standardError = pipe
        try process.run()
        state.withLock { $0.process = process }

        var log = ""
        var lines = pipe.fileHandleForReading.bytes.lines.makeAsyncIterator()
        while true {
            guard let line = try await lines.next() else {
                Issue.record("notesd exited:\n\(log)")
                throw CancellationError()
            }
            log += line + "\n"
            if let m = line.firstMatch(of: /listen=(\S+)/) {
                state.withLock { $0.listen = String(m.1) }
                break
            }
        }
        // Keep draining the log so the server never blocks on a full pipe.
        let rest = lines
        Task.detached {
            var rest = rest
            while (try? await rest.next()) != nil {}
        }
    }

    /// Makes another account, with `password`, through the owner (an admin): invited, then its setup link used.
    func addAccount(_ username: String, password: String = TestServer.password) async throws {
        let owner = await ApiClient(baseURL: url).login(
            username: Self.username, password: Self.password, deviceName: "x")
        guard case .ok(let signIn) = owner else {
            Issue.record("owner login failed: \(owner)")
            throw CancellationError()
        }
        struct Link: Decodable {
            struct Token: Decodable { var token: String }
            var link: Token
        }
        guard let invited: Link = try await post("api/v1/users", ["username": username], access: signIn.accessToken)
        else { throw CancellationError() }
        let _: Empty? = try await post(
            "api/v1/auth/setup-link/use", ["token": invited.link.token, "password": password], access: nil)
    }

    private struct Empty: Decodable {}

    private func post<T: Decodable>(_ path: String, _ body: [String: String], access: String?) async throws -> T? {
        var request = URLRequest(url: url.appending(path: path))
        request.httpMethod = "POST"
        request.httpBody = try JSONEncoder().encode(body)
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        if let access { request.setValue("Bearer \(access)", forHTTPHeaderField: "Authorization") }
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode) else {
            Issue.record("\(path): \(String(decoding: data, as: UTF8.self))")
            throw CancellationError()
        }
        return data.isEmpty ? nil : try JSONDecoder().decode(T.self, from: data)
    }

    func stop() {
        guard
            let process = state.withLock({ state in
                defer { state.process = nil }
                return state.process
            })
        else {
            return
        }
        process.terminate()
        // Not waitUntilExit: it waits on the run loop for the exit notification, and from a deinit on
        // a concurrency thread it can miss one already delivered, hanging the run for good.
        let deadline = Date().addingTimeInterval(10)
        while process.isRunning && Date() < deadline { usleep(10_000) }
        if process.isRunning { kill(process.processIdentifier, SIGKILL) }
    }

    func restart() async throws {
        stop()
        _ = try await launch()
    }

    /// A copy of the data dir, for restoring later as if from a backup. Stop the server first.
    func snapshot() throws -> URL {
        let copy = FileManager.default.temporaryDirectory.appending(path: "notesd-backup-\(UUID().uuidString)")
        try FileManager.default.copyItem(at: dataDir, to: copy)
        return copy
    }

    func restore(_ backup: URL) throws {
        try FileManager.default.removeItem(at: dataDir)
        try FileManager.default.copyItem(at: backup, to: dataDir)
    }

    deinit {
        stop()
        try? FileManager.default.removeItem(at: dataDir)
    }

    // MARK: the binary

    private static let built = Mutex<Result<String, any Error>?>(nil)

    private static func binary() throws -> String {
        if let path = ProcessInfo.processInfo.environment["NOTESD_SERVER_BIN"], !path.isEmpty { return path }
        return try built.withLock { built in
            if let built { return try built.get() }
            let result = Result { try buildServer() }
            built = result
            return try result.get()
        }
    }

    private static func buildServer() throws -> String {
        let serverDir = Fixtures.sharedDir.deletingLastPathComponent().appending(path: "server")
        let out = FileManager.default.temporaryDirectory.appending(path: "notesd-test-\(UUID().uuidString)").path
        let go = Process()
        go.executableURL = URL(fileURLWithPath: "/usr/bin/env")
        // Go comes from the repository's mise toolchain.
        go.arguments = ["mise", "exec", "--", "go", "build", "-o", out, "./cmd/notesd"]
        go.currentDirectoryURL = serverDir
        var env = ProcessInfo.processInfo.environment
        env["PATH"] = (env["PATH"] ?? "") + ":/opt/homebrew/bin:/usr/local/bin"
        go.environment = env
        try go.run()
        go.waitUntilExit()
        guard go.terminationStatus == 0 else {
            throw DatabaseError(code: go.terminationStatus, message: "go build failed; set NOTESD_SERVER_BIN")
        }
        return out
    }
}
