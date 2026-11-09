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
    "invalid_request", "hash_mismatch", "image_too_large", "unsupported_image", "space_default", "not_space_owner",
    "space_exists", "locked_in_shared_space",
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
    /// The account's id, as notes' updatedBy and shared spaces' members name it (§7.9). 0 from older servers.
    public var userId: Int64
    /// Whose session it is: their name and photo.
    public var profile: Profile

    public var tokens: Tokens { Tokens(access: accessToken, accessExpiresAt: accessExpiresAt, refresh: refreshToken) }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        session = try c.decodeIfPresent(Session.self, forKey: .session)
        username = try c.decodeIfPresent(String.self, forKey: .username) ?? ""
        accessToken = try c.decodeIfPresent(String.self, forKey: .accessToken) ?? ""
        accessExpiresAt = try c.decodeIfPresent(Int64.self, forKey: .accessExpiresAt) ?? 0
        refreshToken = try c.decodeIfPresent(String.self, forKey: .refreshToken) ?? ""
        refreshExpiresAt = try c.decodeIfPresent(Int64.self, forKey: .refreshExpiresAt) ?? 0
        userId = try c.decodeIfPresent(Int64.self, forKey: .userId) ?? 0
        profile = Profile(
            userId: userId,
            displayName: try c.decodeIfPresent(String.self, forKey: .displayName) ?? "",
            avatar: try c.decodeIfPresent(String.self, forKey: .avatar) ?? "")
    }

    private enum CodingKeys: String, CodingKey {
        case session, username, accessToken, accessExpiresAt, refreshToken, refreshExpiresAt
        case userId, displayName, avatar
    }
}

/// An account's name and photo (architecture §10), both optional. `userId` is 0 until the server has said.
public struct Profile: Decodable, Equatable, Sendable {
    public var userId: Int64
    /// "" for none: the apps show the username.
    public var displayName: String
    /// The photo's version, for `ApiClient.avatar`; "" for none.
    public var avatar: String

    public init(userId: Int64 = 0, displayName: String = "", avatar: String = "") {
        self.userId = userId
        self.displayName = displayName
        self.avatar = avatar
    }

    /// From an account, as the profile endpoints answer.
    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        userId = try c.decode(Int64.self, forKey: .id)
        displayName = try c.decodeIfPresent(String.self, forKey: .displayName) ?? ""
        avatar = try c.decodeIfPresent(String.self, forKey: .avatar) ?? ""
    }

    /// How the apps name someone: their chosen name, or else their username.
    public func name(_ username: String) -> String { displayName.isEmpty ? username : displayName }

    private enum CodingKeys: String, CodingKey { case id, displayName, avatar }
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
    /// The account that last changed it (§7.9); nil for changes from before that was recorded.
    public var updatedBy: Int64?
    /// On a tombstone a move to another file left: the space the note went to (§7.9).
    public var movedTo: String?

    public init(
        id: String, title: String = "", content: String = "", revision: Int64, changeSeq: Int64 = 0,
        createdAt: Int64 = 0, updatedAt: Int64 = 0, deletedAt: Int64? = nil, conflictOf: String? = nil,
        imageIds: [String]? = nil, spaceId: String = Spaces.defaultId, updatedBy: Int64? = nil, movedTo: String? = nil
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
        self.updatedBy = updatedBy
        self.movedTo = movedTo
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
        updatedBy = try c.decodeIfPresent(Int64.self, forKey: .updatedBy)
        movedTo = try c.decodeIfPresent(String.self, forKey: .movedTo)
    }

    private enum CodingKeys: String, CodingKey {
        case id, title, content, revision, changeSeq, createdAt, updatedAt, deletedAt, conflictOf, imageIds, spaceId
        case updatedBy, movedTo
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
    /// Who's in a shared space (§7.9); nil for the account's own.
    public var shared: SharedSpace?

    public init(
        id: String, name: String, icon: String = "", createdAt: Int64 = 0, noteCount: Int = 0,
        traits: [SpaceTrait] = [], shared: SharedSpace? = nil
    ) {
        self.id = id
        self.name = name
        self.icon = icon
        self.createdAt = createdAt
        self.noteCount = noteCount
        self.traits = traits
        self.shared = shared
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        name = try c.decode(String.self, forKey: .name)
        icon = try c.decodeIfPresent(String.self, forKey: .icon) ?? ""
        createdAt = try c.decode(Int64.self, forKey: .createdAt)
        noteCount = try c.decode(Int.self, forKey: .noteCount)
        traits = try c.decodeIfPresent([SpaceTrait].self, forKey: .traits) ?? []
        shared = try c.decodeIfPresent(SharedSpace.self, forKey: .shared)
    }
}

/// A shared space's people, as this account sees them (§7.9). `role` is `owner` or `member`.
public struct SharedSpace: Codable, Sendable, Hashable {
    public var role: String
    /// The owner first.
    public var members: [Member]
    /// Pending invitations; only the owner gets them.
    public var invites: [Invitee]

    public var isOwner: Bool { role == "owner" }

    public init(role: String, members: [Member] = [], invites: [Invitee] = []) {
        self.role = role
        self.members = members
        self.invites = invites
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        role = try c.decode(String.self, forKey: .role)
        members = try c.decodeIfPresent([Member].self, forKey: .members) ?? []
        invites = try c.decodeIfPresent([Invitee].self, forKey: .invites) ?? []
    }
}

/// An account as other accounts see it.
public struct Member: Codable, Sendable, Hashable, Identifiable {
    public var id: Int64
    public var username: String
    /// The name and photo (`avatar`, its version) they chose; "" for none, and from servers before profiles.
    public var displayName: String
    public var avatar: String

    /// How the apps name someone: their chosen name, or else their username.
    public var name: String { displayName.isEmpty ? username : displayName }

    public init(id: Int64, username: String, displayName: String = "", avatar: String = "") {
        self.id = id
        self.username = username
        self.displayName = displayName
        self.avatar = avatar
    }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(Int64.self, forKey: .id)
        username = try c.decode(String.self, forKey: .username)
        displayName = try c.decodeIfPresent(String.self, forKey: .displayName) ?? ""
        avatar = try c.decodeIfPresent(String.self, forKey: .avatar) ?? ""
    }
}

/// A pending invitation into a shared space, as its owner sees it.
public struct Invitee: Codable, Sendable, Hashable, Identifiable {
    public var id: String
    public var userId: Int64
    public var username: String
    public var displayName: String
    public var avatar: String

    /// Whom it's for, as a member would be.
    public var person: Member { Member(id: userId, username: username, displayName: displayName, avatar: avatar) }

    public init(id: String, userId: Int64, username: String, displayName: String = "", avatar: String = "") {
        self.id = id
        self.userId = userId
        self.username = username
        self.displayName = displayName
        self.avatar = avatar
    }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        userId = try c.decode(Int64.self, forKey: .userId)
        username = try c.decode(String.self, forKey: .username)
        displayName = try c.decodeIfPresent(String.self, forKey: .displayName) ?? ""
        avatar = try c.decodeIfPresent(String.self, forKey: .avatar) ?? ""
    }
}

/// An invitation waiting for this account (§7.9).
public struct RemoteInvite: Codable, Sendable, Hashable, Identifiable {
    public var id: String
    public var spaceId: String
    public var spaceName: String
    public var spaceIcon: String
    public var from: Member
    public var createdAt: Int64

    public init(
        id: String, spaceId: String, spaceName: String, spaceIcon: String = "", from: Member, createdAt: Int64 = 0
    ) {
        self.id = id
        self.spaceId = spaceId
        self.spaceName = spaceName
        self.spaceIcon = spaceIcon
        self.from = from
        self.createdAt = createdAt
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        spaceId = try c.decode(String.self, forKey: .spaceId)
        spaceName = try c.decode(String.self, forKey: .spaceName)
        spaceIcon = try c.decodeIfPresent(String.self, forKey: .spaceIcon) ?? ""
        from = try c.decode(Member.self, forKey: .from)
        createdAt = try c.decodeIfPresent(Int64.self, forKey: .createdAt) ?? 0
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
