import Foundation

/// The outcome of one request. Transport failures are values too: callers decide what offline means.
public enum ApiResult<T: Sendable>: Sendable {
    case ok(T)
    /// 409: the server's current state is in `current` (a note, for note conflicts), as JSON.
    case conflict(code: String, message: String, current: Data?)
    /// No usable session: never signed in, or the session was revoked.
    case unauthenticated
    case rateLimited(retryAfterSeconds: Int64)
    /// Any other error answer, e.g. a wrong password (401 on login) or invalid input (400).
    case rejected(status: Int, error: String, message: String, field: String?)
    case unreachable(String)

    /// A non-ok result, retyped: none of them carry a value.
    func failure<U>() -> ApiResult<U> {
        switch self {
        case .ok: preconditionFailure("not a failure")
        case .conflict(let code, let message, let current): .conflict(code: code, message: message, current: current)
        case .unauthenticated: .unauthenticated
        case .rateLimited(let seconds): .rateLimited(retryAfterSeconds: seconds)
        case .rejected(let status, let error, let message, let field):
            .rejected(status: status, error: error, message: message, field: field)
        case .unreachable(let cause): .unreachable(cause)
        }
    }

    public var value: T? {
        if case .ok(let value) = self { return value }
        return nil
    }
}

/// Device tokens. `accessExpiresAt` is Unix ms.
public struct Tokens: Hashable, Sendable, Codable {
    public var access: String
    public var accessExpiresAt: Int64
    public var refresh: String

    public init(access: String, accessExpiresAt: Int64, refresh: String) {
        self.access = access
        self.accessExpiresAt = accessExpiresAt
        self.refresh = refresh
    }
}

/// Where the client keeps the session's tokens; implemented by the app with the Keychain.
public protocol Credentials: Sendable {
    func tokens() async -> Tokens?
    /// A refresh of `old` succeeded. Ignore it if the session no longer holds `old`: a sign-out or new
    /// sign-in landed while the refresh was out, and must not be undone.
    func refreshed(_ tokens: Tokens, replacing old: Tokens) async
    /// The server refused `old`'s refresh token: that session is over. Local notes are not this protocol's business.
    func revoked(_ old: Tokens) async
}

public struct AuthStatus: Decodable, Sendable {
    public var setupRequired: Bool
}

public struct Session: Decodable, Sendable, Hashable {
    public var id: String
    public var kind: String
    public var name: String
    public var createdAt: Int64
    public var lastUsedAt: Int64
}

public struct SignIn: Decodable, Sendable {
    public var session: Session?
    public var username: String
    public var accessToken: String
    public var accessExpiresAt: Int64
    public var refreshToken: String
    public var refreshExpiresAt: Int64

    public var tokens: Tokens { Tokens(access: accessToken, accessExpiresAt: accessExpiresAt, refresh: refreshToken) }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        session = try c.decodeIfPresent(Session.self, forKey: .session)
        username = try c.decodeIfPresent(String.self, forKey: .username) ?? ""
        accessToken = try c.decodeIfPresent(String.self, forKey: .accessToken) ?? ""
        accessExpiresAt = try c.decodeIfPresent(Int64.self, forKey: .accessExpiresAt) ?? 0
        refreshToken = try c.decodeIfPresent(String.self, forKey: .refreshToken) ?? ""
        refreshExpiresAt = try c.decodeIfPresent(Int64.self, forKey: .refreshExpiresAt) ?? 0
    }

    private enum CodingKeys: String, CodingKey {
        case session, username, accessToken, accessExpiresAt, refreshToken, refreshExpiresAt
    }
}

struct ErrorBody: Decodable {
    var error = ""
    var message = ""
    var field: String?

    init() {}

    init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        error = try c.decodeIfPresent(String.self, forKey: .error) ?? ""
        message = try c.decodeIfPresent(String.self, forKey: .message) ?? ""
        field = try c.decodeIfPresent(String.self, forKey: .field)
    }

    private enum CodingKeys: String, CodingKey { case error, message, field }
}

/// A note as the server has it. A tombstone has `deletedAt` set and empty title and content.
public struct RemoteNote: Codable, Sendable, Hashable {
    public var id: String
    public var title: String
    public var content: String
    public var revision: Int64
    public var changeSeq: Int64
    public var createdAt: Int64
    public var updatedAt: Int64
    public var deletedAt: Int64?
    public var conflictOf: String?
    /// A locked note's images as the server records them (§7.6); absent for a plain note.
    public var imageIds: [String]?
    /// The space the note is in (§7.7).
    public var spaceId: String

    public init(
        id: String, title: String = "", content: String = "", revision: Int64, changeSeq: Int64 = 0,
        createdAt: Int64 = 0, updatedAt: Int64 = 0, deletedAt: Int64? = nil, conflictOf: String? = nil,
        imageIds: [String]? = nil, spaceId: String = Spaces.defaultId
    ) {
        self.id = id
        self.title = title
        self.content = content
        self.revision = revision
        self.changeSeq = changeSeq
        self.createdAt = createdAt
        self.updatedAt = updatedAt
        self.deletedAt = deletedAt
        self.conflictOf = conflictOf
        self.imageIds = imageIds
        self.spaceId = spaceId
    }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        // Required: a missing body would otherwise be stored as an empty note and synced back.
        title = try c.decode(String.self, forKey: .title)
        content = try c.decode(String.self, forKey: .content)
        revision = try c.decode(Int64.self, forKey: .revision)
        changeSeq = try c.decodeIfPresent(Int64.self, forKey: .changeSeq) ?? 0
        createdAt = try c.decodeIfPresent(Int64.self, forKey: .createdAt) ?? 0
        updatedAt = try c.decodeIfPresent(Int64.self, forKey: .updatedAt) ?? 0
        deletedAt = try c.decodeIfPresent(Int64.self, forKey: .deletedAt)
        conflictOf = try c.decodeIfPresent(String.self, forKey: .conflictOf)
        imageIds = try c.decodeIfPresent([String].self, forKey: .imageIds)
        spaceId = try c.decodeIfPresent(String.self, forKey: .spaceId) ?? Spaces.defaultId
    }

    private enum CodingKeys: String, CodingKey {
        case id, title, content, revision, changeSeq, createdAt, updatedAt, deletedAt, conflictOf, imageIds, spaceId
    }
}

/// The answer to a create, update or delete.
public struct MutationResult: Decodable, Sendable, Hashable {
    public var id: String
    public var status: String
    public var revision: Int64
    public var changeSeq: Int64
    public var updatedAt: Int64
    public var deletedAt: Int64?
    /// Creates and updates: the space the note is in now, the default if the one sent is gone (§7.7).
    public var spaceId: String?

    public init(
        id: String, status: String, revision: Int64, changeSeq: Int64, updatedAt: Int64, deletedAt: Int64?,
        spaceId: String? = nil
    ) {
        self.id = id
        self.status = status
        self.revision = revision
        self.changeSeq = changeSeq
        self.updatedAt = updatedAt
        self.deletedAt = deletedAt
        self.spaceId = spaceId
    }
}

/// A space as the server stores it (§7.7).
public struct RemoteSpace: Codable, Sendable, Hashable {
    public var id: String
    public var name: String
    /// A key into the app's icons; "" for the default one.
    public var icon: String
    public var createdAt: Int64
    public var noteCount: Int

    public init(id: String, name: String, icon: String = "", createdAt: Int64 = 0, noteCount: Int = 0) {
        self.id = id
        self.name = name
        self.icon = icon
        self.createdAt = createdAt
        self.noteCount = noteCount
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        name = try c.decode(String.self, forKey: .name)
        icon = try c.decodeIfPresent(String.self, forKey: .icon) ?? ""
        createdAt = try c.decode(Int64.self, forKey: .createdAt)
        noteCount = try c.decode(Int.self, forKey: .noteCount)
    }
}

/// One page of the change feed: latest state per note, ascending changeSeq.
public struct SyncPage: Decodable, Sendable {
    public var changes: [RemoteNote]
    public var nextCursor: Int64
    public var hasMore: Bool
    public var generation: String

    public init(changes: [RemoteNote], nextCursor: Int64, hasMore: Bool, generation: String) {
        self.changes = changes
        self.nextCursor = nextCursor
        self.hasMore = hasMore
        self.generation = generation
    }
}

/// One wrapped copy of the locked-notes key (§10.1); nothing here opens without its secret.
public struct LockKey: Codable, Sendable, Hashable {
    public var id: String
    public var kind: String
    public var credentialId: String?
    public var salt: String
    public var iterations: Int
    public var wrapped: String
    public var createdAt: Int64

    public init(
        id: String, kind: String, credentialId: String? = nil, salt: String, iterations: Int = 0, wrapped: String,
        createdAt: Int64 = 0
    ) {
        self.id = id
        self.kind = kind
        self.credentialId = credentialId
        self.salt = salt
        self.iterations = iterations
        self.wrapped = wrapped
        self.createdAt = createdAt
    }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        kind = try c.decode(String.self, forKey: .kind)
        credentialId = try c.decodeIfPresent(String.self, forKey: .credentialId)
        salt = try c.decode(String.self, forKey: .salt)
        iterations = try c.decodeIfPresent(Int.self, forKey: .iterations) ?? 0
        wrapped = try c.decode(String.self, forKey: .wrapped)
        createdAt = try c.decodeIfPresent(Int64.self, forKey: .createdAt) ?? 0
    }

    private enum CodingKeys: String, CodingKey { case id, kind, credentialId, salt, iterations, wrapped, createdAt }
}

/// An image as stored on the server: its type, as served, and its bytes.
public struct ImageFile: Sendable, Hashable {
    public var type: String
    public var bytes: Data

    public init(type: String, bytes: Data) {
        self.type = type
        self.bytes = bytes
    }
}

/// The type a locked note's encrypted image is uploaded and served as (§10.1).
public let lockedImageType = "application/vnd.notesd.locked"

/// The server's email settings (Resend). The key itself never comes back, only its last characters.
public struct EmailSettings: Decodable, Sendable, Hashable {
    public var configured: Bool
    public var from: String
    public var keyHint: String

    public init(configured: Bool = false, from: String = "", keyHint: String = "") {
        self.configured = configured
        self.from = from
        self.keyHint = keyHint
    }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        configured = try c.decodeIfPresent(Bool.self, forKey: .configured) ?? false
        from = try c.decodeIfPresent(String.self, forKey: .from) ?? ""
        keyHint = try c.decodeIfPresent(String.self, forKey: .keyHint) ?? ""
    }

    private enum CodingKeys: String, CodingKey { case configured, from, keyHint }
}

/// JSON as this package writes it: stable key order, "/" unescaped.
let jsonEncoder: JSONEncoder = {
    let encoder = JSONEncoder()
    encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
    return encoder
}()
