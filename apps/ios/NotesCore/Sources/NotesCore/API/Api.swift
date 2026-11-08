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
    /// An answer notesd wouldn't give: a redirect, a 4xx without notesd's JSON error (a proxy or Wi-Fi
    /// portal page), or a 2xx that isn't what the endpoint returns.
    case unexpected(status: Int)
    /// 426: the server needs a newer app. Nothing is wrong with the request; keep it.
    case upgradeRequired(minVersion: String?)

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
        case .unexpected(let status): .unexpected(status: status)
        case .upgradeRequired(let minVersion): .upgradeRequired(minVersion: minVersion)
        }
    }

    public var value: T? {
        if case .ok(let value) = self { return value }
        return nil
    }
}

/// notesd error codes that mean a queued change can never succeed as sent: it's dropped, and the user told.
let permanentErrors: Set<String> = [
    "invalid_request", "hash_mismatch", "image_too_large", "unsupported_image", "space_default",
]

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
    /// Absent from servers before 1.0.
    public var serverVersion: String?
    public var apiVersion: Int?
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
    /// Absent from servers older than traits: none.
    public var traits: [SpaceTrait]

    public init(
        id: String, name: String, icon: String = "", createdAt: Int64 = 0, noteCount: Int = 0, traits: [SpaceTrait] = []
    ) {
        self.id = id
        self.name = name
        self.icon = icon
        self.createdAt = createdAt
        self.noteCount = noteCount
        self.traits = traits
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        name = try c.decode(String.self, forKey: .name)
        icon = try c.decodeIfPresent(String.self, forKey: .icon) ?? ""
        createdAt = try c.decode(Int64.self, forKey: .createdAt)
        noteCount = try c.decode(Int.self, forKey: .noteCount)
        traits = try c.decodeIfPresent([SpaceTrait].self, forKey: .traits) ?? []
    }
}

/// A feature on for a whole space (§7.8), worked out by the server. `exposure` is the sentence a move into the
/// space asks with, nil when the trait keeps notes to the account; `audience` is who it reaches ("user:3",
/// "public"), what a move compares; `problem` is set when it needs attention.
public struct SpaceTrait: Codable, Sendable, Hashable {
    public var key: String
    public var label: String
    public var summary: String
    public var exposure: String?
    public var problem: String?
    public var audience: [String]?

    public init(
        key: String, label: String = "", summary: String = "", exposure: String? = nil, problem: String? = nil,
        audience: [String]? = nil
    ) {
        self.key = key
        self.label = label
        self.summary = summary
        self.exposure = exposure
        self.problem = problem
        self.audience = audience
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        key = try c.decode(String.self, forKey: .key)
        label = try c.decodeIfPresent(String.self, forKey: .label) ?? ""
        summary = try c.decodeIfPresent(String.self, forKey: .summary) ?? ""
        exposure = try c.decodeIfPresent(String.self, forKey: .exposure)
        problem = try c.decodeIfPresent(String.self, forKey: .problem)
        audience = try c.decodeIfPresent([String].self, forKey: .audience)
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
    /// How the entry's key is derived from its secret; nil from servers before 1.0, implied by `kind`.
    public var kdf: Kdf?

    public init(
        id: String, kind: String, credentialId: String? = nil, salt: String, iterations: Int = 0, wrapped: String,
        createdAt: Int64 = 0, kdf: Kdf? = nil
    ) {
        self.id = id
        self.kind = kind
        self.credentialId = credentialId
        self.salt = salt
        self.iterations = iterations
        self.wrapped = wrapped
        self.createdAt = createdAt
        self.kdf = kdf
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
        kdf = try c.decodeIfPresent(Kdf.self, forKey: .kdf)
    }

    private enum CodingKeys: String, CodingKey {
        case id, kind, credentialId, salt, iterations, wrapped, createdAt, kdf
    }
}

/// A keyring entry's key derivation (§10.1). Parameters of functions this app doesn't know are dropped:
/// it can't use them anyway.
public struct Kdf: Codable, Sendable, Hashable {
    public static let pbkdf2 = "pbkdf2-sha256"
    public static let hkdf = "hkdf-sha256"

    public var name: String
    /// pbkdf2-sha256 only.
    public var iterations: Int?

    public init(name: String, iterations: Int? = nil) {
        self.name = name
        self.iterations = iterations
    }
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

/// Any version of a locked image: later ones are `lockedImageType` plus ".v<n>". Only `lockedImageType`
/// opens here; others are never shown nor stored again.
public func isLockedImageType(_ type: String) -> Bool {
    type == lockedImageType || type.hasPrefix(lockedImageType + ".")
}

/// The server's email settings (Resend). The key itself never comes back, only its last characters.
/// `editable`: whether this account may change them (admins). Servers from before accounts had roles leave it
/// out; their one account is the owner.
public struct EmailSettings: Decodable, Sendable, Hashable {
    public var configured: Bool
    public var from: String
    public var keyHint: String
    public var editable: Bool

    public init(configured: Bool = false, from: String = "", keyHint: String = "", editable: Bool = true) {
        self.configured = configured
        self.from = from
        self.keyHint = keyHint
        self.editable = editable
    }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        configured = try c.decodeIfPresent(Bool.self, forKey: .configured) ?? false
        from = try c.decodeIfPresent(String.self, forKey: .from) ?? ""
        keyHint = try c.decodeIfPresent(String.self, forKey: .keyHint) ?? ""
        editable = try c.decodeIfPresent(Bool.self, forKey: .editable) ?? true
    }

    private enum CodingKeys: String, CodingKey { case configured, from, keyHint, editable }
}

/// JSON as this package writes it: stable key order, "/" unescaped.
let jsonEncoder: JSONEncoder = {
    let encoder = JSONEncoder()
    encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
    return encoder
}()
