import Foundation

/// Sends one HTTP request. URLSession in the app; tests put faults and stubs here.
public protocol HTTPTransport: Sendable {
    func send(_ request: URLRequest) async throws -> (Data, HTTPURLResponse)
}

public struct URLSessionTransport: HTTPTransport {
    private let session: URLSession

    public init(session: URLSession) {
        self.session = session
    }

    /// No cookies or cache, a quick failure when nothing answers.
    public static let standard: URLSessionTransport = {
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 30
        config.waitsForConnectivity = false
        return URLSessionTransport(session: URLSession(configuration: config))
    }()

    public func send(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw URLError(.badServerResponse) }
        return (data, http)
    }
}

/// The body of a note create, update or delete. Built once per operation and
/// resent unchanged on retries, so the server can recognise a replay (§4.2).
public struct NoteRequest: Encodable, Sendable, Hashable {
    public var operationId: String
    public var id: String?
    public var baseRevision: Int64?
    public var title: String?
    public var content: String?
    public var createdAt: Int64?
    public var updatedAt: Int64?
    public var conflictOf: String?
    public var spaceId: String?
    public var imageIds: [String]?

    public init(
        operationId: String, id: String? = nil, baseRevision: Int64? = nil, title: String? = nil,
        content: String? = nil, createdAt: Int64? = nil, updatedAt: Int64? = nil, conflictOf: String? = nil,
        spaceId: String? = nil, imageIds: [String]? = nil
    ) {
        self.operationId = operationId
        self.id = id
        self.baseRevision = baseRevision
        self.title = title
        self.content = content
        self.createdAt = createdAt
        self.updatedAt = updatedAt
        self.conflictOf = conflictOf
        self.spaceId = spaceId
        self.imageIds = imageIds
    }
}

/// notesd's JSON API (server/README.md) for one server. Authenticated
/// calls send the device's bearer token, refresh it shortly before it expires,
/// and on a 401 refresh once and resend the identical request, so an
/// operationId inside it stays the same. Only one refresh runs at a time.
public final class ApiClient: Sendable {
    /// A 2xx answer that isn't the expected JSON: something other than notesd is at this address.
    public static let unexpectedResponse = "unexpected_response"

    public let baseURL: URL
    private let credentials: (any Credentials)?
    private let transport: any HTTPTransport
    private let clock: Clock
    private let refreshLock = AsyncLock()

    private static let refreshEarlyMs: Int64 = 60_000
    private static let defaultRetryAfter: Int64 = 60

    public init(
        baseURL: URL, credentials: (any Credentials)? = nil,
        transport: any HTTPTransport = URLSessionTransport.standard,
        clock: @escaping Clock = systemClock
    ) {
        self.baseURL = baseURL
        self.credentials = credentials
        self.transport = transport
        self.clock = clock
    }

    /// `/healthz`: is a server answering at this address at all.
    public func health() async -> ApiResult<Void> {
        await send(request("GET", "healthz")) { _ in () }
    }

    public func status() async -> ApiResult<AuthStatus> {
        await send(request("GET", "api/v1/auth/status"), decode: json(AuthStatus.self))
    }

    /// Creates the owner account on a server that has none yet.
    public func setup(username: String, password: String) async -> ApiResult<Void> {
        struct Body: Encodable {
            var username, password: String
        }
        let body = Body(username: username, password: password)
        return await send(request("POST", "api/v1/auth/setup", body: body)) { _ in () }
    }

    public func login(username: String, password: String, deviceName: String) async -> ApiResult<SignIn> {
        struct Body: Encodable {
            var username, password, client, deviceName: String
        }
        let body = Body(username: username, password: password, client: "device", deviceName: deviceName)
        return await send(request("POST", "api/v1/auth/login", body: body)) { reply in
            let signIn = try JSONDecoder().decode(SignIn.self, from: reply.data)
            guard !signIn.accessToken.isEmpty, !signIn.refreshToken.isEmpty, signIn.accessExpiresAt > 0 else {
                throw URLError(.cannotParseResponse)
            }
            return signIn
        }
    }

    /// Ends this device's session on the server.
    public func logout() async -> ApiResult<Void> {
        await authed(
            { self.request("POST", "api/v1/auth/logout", body: [String: String](), access: $0) }, decode: { _ in () })
    }

    public func createNote(_ body: NoteRequest) async -> ApiResult<MutationResult> {
        await authed(
            { self.request("POST", "api/v1/notes", body: body, access: $0) }, decode: json(MutationResult.self))
    }

    public func updateNote(_ id: String, _ body: NoteRequest) async -> ApiResult<MutationResult> {
        await authed(
            { self.request("PUT", "api/v1/notes/\(id)", body: body, access: $0) }, decode: json(MutationResult.self))
    }

    public func deleteNote(_ id: String, _ body: NoteRequest) async -> ApiResult<MutationResult> {
        await authed(
            { self.request("DELETE", "api/v1/notes/\(id)", body: body, access: $0) }, decode: json(MutationResult.self))
    }

    /// Changes after `since`. `generation` is the one stored from the last page, nil before the first sync.
    public func sync(since: Int64, limit: Int, generation: String?) async -> ApiResult<SyncPage> {
        var query = [
            URLQueryItem(name: "since", value: String(since)), URLQueryItem(name: "limit", value: String(limit)),
        ]
        if let generation { query.append(URLQueryItem(name: "generation", value: generation)) }
        let items = query
        return await authed(
            { self.request("GET", "api/v1/sync", query: items, access: $0) }, decode: json(SyncPage.self))
    }

    // MARK: email

    public func emailSettings() async -> ApiResult<EmailSettings> {
        await authed(
            { self.request("GET", "api/v1/settings/email", access: $0) }, decode: json(EmailSettings.self))
    }

    /// A nil `apiKey` keeps the saved one.
    public func saveEmailSettings(from: String, apiKey: String?) async -> ApiResult<EmailSettings> {
        struct Body: Encodable {
            var from: String
            var apiKey: String?
        }
        let body = Body(from: from, apiKey: apiKey)
        return await authed(
            { self.request("PUT", "api/v1/settings/email", body: body, access: $0) }, decode: json(EmailSettings.self))
    }

    public func deleteEmailSettings() async -> ApiResult<Void> {
        await authed({ self.request("DELETE", "api/v1/settings/email", access: $0) }, decode: { _ in () })
    }

    /// Emails the server's copy, which must be at `revision` (409 stale_revision otherwise).
    public func emailNote(_ id: String, to: [String], revision: Int64) async -> ApiResult<Void> {
        struct Body: Encodable {
            var to: [String]
            var revision: Int64
        }
        let body = Body(to: to, revision: revision)
        return await authed(
            { self.request("POST", "api/v1/notes/\(id)/email", body: body, access: $0) }, decode: { _ in () })
    }

    // MARK: locked notes

    /// The locked-notes keyring.
    public func lockKeys() async -> ApiResult<[LockKey]> {
        struct Keys: Decodable { var keys: [LockKey]? }
        return await authed(
            { self.request("GET", "api/v1/locks", access: $0) },
            decode: { try JSONDecoder().decode(Keys.self, from: $0.data).keys ?? [] })
    }

    // MARK: spaces

    /// Every space (§7.7), the default first.
    public func spaces() async -> ApiResult<[RemoteSpace]> {
        struct Spaces: Decodable { var spaces: [RemoteSpace]? }
        return await authed(
            { self.request("GET", "api/v1/spaces", access: $0) },
            decode: { try JSONDecoder().decode(Spaces.self, from: $0.data).spaces ?? [] })
    }

    /// Creates or renames a space and sets its icon; the answer has the name as stored (a clash gets a number).
    public func putSpace(_ id: String, name: String, icon: String) async -> ApiResult<RemoteSpace> {
        struct Body: Encodable {
            var name: String
            var icon: String
        }
        return await authed(
            { self.request("PUT", "api/v1/spaces/\(id)", body: Body(name: name, icon: icon), access: $0) },
            decode: json(RemoteSpace.self))
    }

    /// Deletes an empty space: a conflict (space_not_empty) while it holds notes.
    public func deleteSpace(_ id: String) async -> ApiResult<Void> {
        await authed({ self.request("DELETE", "api/v1/spaces/\(id)", access: $0) }, decode: { _ in () })
    }

    /// Creates or replaces a keyring entry; `verifier` proves this device holds the key (the first password entry sets it).
    public func putLockKey(_ key: LockKey, verifier: String) async -> ApiResult<LockKey> {
        struct Body: Encodable {
            var kind: String
            var credentialId: String?
            var salt: String
            var iterations: Int
            var wrapped: String
            var verifier: String
        }
        let body = Body(
            kind: key.kind, credentialId: key.credentialId, salt: key.salt, iterations: key.iterations,
            wrapped: key.wrapped, verifier: verifier)
        return await authed(
            { self.request("PUT", "api/v1/locks/keys/\(key.id)", body: body, access: $0) },
            decode: json(LockKey.self))
    }

    /// Forgets the keyring: notes locked so far can never be opened again.
    public func resetLocks(accountPassword: String) async -> ApiResult<Void> {
        let body = ["accountPassword": accountPassword]
        return await authed(
            { self.request("DELETE", "api/v1/locks", body: body, access: $0) }, decode: { _ in () })
    }

    // MARK: images

    /// Uploads an image (§7.6); `id` is the SHA-256 of `bytes`, so a retry stores nothing new.
    public func putImage(_ id: String, bytes: Data, type: String) async -> ApiResult<Void> {
        await authed(
            { access in
                var r = self.request("PUT", "api/v1/images/\(id)", access: access)
                r.httpBody = bytes
                r.setValue(type, forHTTPHeaderField: "Content-Type")
                return r
            }, decode: { _ in () })
    }

    /// Downloads an image with the type it is stored as.
    public func getImage(_ id: String) async -> ApiResult<ImageFile> {
        await authed(
            { self.request("GET", "api/v1/images/\(id)", access: $0) },
            decode: { ImageFile(type: $0.type ?? "application/octet-stream", bytes: $0.data) })
    }

    // MARK: plumbing

    private func authed<T: Sendable>(
        _ build: @Sendable (String) -> URLRequest, decode: @Sendable (Reply) throws -> T
    ) async -> ApiResult<T> {
        guard let credentials, var tokens = await credentials.tokens() else { return .unauthenticated }
        if tokens.accessExpiresAt - clock() < Self.refreshEarlyMs {
            switch await refresh(stale: tokens) {
            case .ok(let fresh): tokens = fresh
            case let failed: return failed.failure()
            }
        }
        let first = await send(build(tokens.access), decode: decode)
        guard case .unauthenticated = first else { return first }
        switch await refresh(stale: tokens) {
        case .ok(let fresh): return await send(build(fresh.access), decode: decode)
        case let failed: return failed.failure()
        }
    }

    /// New tokens for `stale`, unless another caller already replaced them.
    private func refresh(stale: Tokens) async -> ApiResult<Tokens> {
        await refreshLock.withLock {
            guard let credentials = self.credentials, let current = await credentials.tokens() else {
                return .unauthenticated
            }
            if current.access != stale.access { return .ok(current) }
            let body = ["refreshToken": current.refresh]
            let result = await self.send(self.request("POST", "api/v1/auth/refresh", body: body)) { reply in
                let signIn = try JSONDecoder().decode(SignIn.self, from: reply.data)
                guard !signIn.accessToken.isEmpty else { throw URLError(.cannotParseResponse) }
                return signIn
            }
            switch result {
            case .ok(let signIn):
                // Refresh tokens aren't rotated; keep ours if the answer leaves it out.
                let next = Tokens(
                    access: signIn.accessToken, accessExpiresAt: signIn.accessExpiresAt,
                    refresh: signIn.refreshToken.isEmpty ? current.refresh : signIn.refreshToken)
                await credentials.refreshed(next, replacing: current)
                return .ok(next)
            case .unauthenticated:
                await credentials.revoked(current)
                return .unauthenticated
            case let failed:
                return failed.failure()
            }
        }
    }

    /// An answer as received: JSON for most endpoints.
    struct Reply {
        var code: Int
        var data: Data
        /// The media type, without parameters.
        var type: String?
    }

    private func send<T: Sendable>(_ request: URLRequest, decode: (Reply) throws -> T) async -> ApiResult<T> {
        let data: Data
        let response: HTTPURLResponse
        do {
            (data, response) = try await transport.send(request)
        } catch {
            return .unreachable(error.localizedDescription)
        }
        let code = response.statusCode
        if (200...299).contains(code) {
            do {
                let type = response.value(forHTTPHeaderField: "Content-Type")?
                    .split(separator: ";").first.map { $0.trimmingCharacters(in: .whitespaces).lowercased() }
                return .ok(try decode(Reply(code: code, data: data, type: type)))
            } catch {
                return .rejected(
                    status: code, error: Self.unexpectedResponse, message: "The server's answer wasn't understood.",
                    field: nil)
            }
        }
        let object = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
        let error = object["error"] as? String ?? ""
        let message = object["message"] as? String ?? ""
        switch code {
        case 401 where error == "unauthenticated":
            return .unauthenticated
        case 409:
            let current = (object["current"] as? [String: Any]).flatMap {
                try? JSONSerialization.data(withJSONObject: $0)
            }
            return .conflict(code: error, message: message, current: current)
        case 429:
            let retry = response.value(forHTTPHeaderField: "Retry-After")?.trimmingCharacters(in: .whitespaces)
            return .rateLimited(retryAfterSeconds: retry.flatMap { Int64($0) } ?? Self.defaultRetryAfter)
        default:
            return .rejected(
                status: code, error: error.isEmpty ? "http_\(code)" : error, message: message,
                field: object["field"] as? String)
        }
    }

    private func request(
        _ method: String, _ path: String, query: [URLQueryItem] = [], body: (any Encodable)? = nil,
        access: String? = nil
    ) -> URLRequest {
        var url = baseURL.appending(path: path)
        if !query.isEmpty { url.append(queryItems: query) }
        var request = URLRequest(url: url)
        request.httpMethod = method
        if let body {
            request.httpBody = try? jsonEncoder.encode(body)
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        if let access { request.setValue("Bearer \(access)", forHTTPHeaderField: "Authorization") }
        return request
    }
}

private func json<T: Decodable & Sendable>(_ type: T.Type) -> @Sendable (ApiClient.Reply) throws -> T {
    { try JSONDecoder().decode(T.self, from: $0.data) }
}
