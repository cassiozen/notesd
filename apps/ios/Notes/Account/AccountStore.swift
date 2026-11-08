import Foundation
import NotesCore
import Security
import Synchronization

nonisolated enum Account: Equatable, Sendable {
    /// Never connected (or all data removed): notes live on this device only.
    case none
    case signedIn(server: String, username: String)
    /// Connected once, no session now (signed out, or the session was revoked).
    /// The server stays fixed: these notes belong to it.
    case signedOut(server: String, username: String)

    var isSignedIn: Bool {
        if case .signedIn = self { true } else { false }
    }

    var server: String? {
        switch self {
        case .none: nil
        case .signedIn(let server, _), .signedOut(let server, _): server
        }
    }
}

/// The server, username and device tokens, in one Keychain item that only
/// this device can read, available after the first unlock so background sync
/// works. Read once at startup, then kept in memory.
nonisolated final class AccountStore: Credentials, Sendable {
    private struct Saved: Codable, Equatable {
        var server: String
        var username: String
        var access: String?
        var accessExpiresAt: Int64 = 0
        var refresh: String?
        // Optional: absent from items saved before the app kept a profile.
        var userId: Int64?
        var displayName: String?
        var avatar: String?

        var profile: Profile {
            Profile(userId: userId ?? 0, displayName: displayName ?? "", avatar: avatar ?? "")
        }

        /// The same account with no session: server, username and profile kept.
        var signedOut: Saved {
            Saved(
                server: server, username: username, userId: userId, displayName: displayName, avatar: avatar)
        }
    }

    private struct State {
        var saved: Saved?
        var client: ApiClient?
    }

    private let keychain: Keychain
    private let state: Mutex<State>
    let account: StateCell<Account>
    /// The account's name and photo, as the server last said.
    let profile: StateCell<Profile>

    init(keychain: Keychain = Keychain(service: "notesd.account")) {
        self.keychain = keychain
        let saved = keychain.read().flatMap { try? JSONDecoder().decode(Saved.self, from: $0) }
        state = Mutex(State(saved: saved))
        account = StateCell(Self.accountOf(saved))
        profile = StateCell(saved?.profile ?? Profile())
    }

    /// The client for the connected server, shared so its token refreshes are single-flight. Nil if never connected.
    func client() -> ApiClient? {
        state.withLock { state in
            guard let server = state.saved?.server, let url = URL(string: server) else { return nil }
            if let client = state.client, client.baseURL == url { return client }
            let client = ApiClient(baseURL: url, credentials: self)
            state.client = client
            return client
        }
    }

    func signedIn(server: String, _ signIn: SignIn) {
        write { _ in
            Saved(
                server: server, username: signIn.username, access: signIn.accessToken,
                accessExpiresAt: signIn.accessExpiresAt, refresh: signIn.refreshToken,
                userId: signIn.profile.userId, displayName: signIn.profile.displayName,
                avatar: signIn.profile.avatar)
        }
    }

    /// `username` at `server`'s name or photo changed, here or on another device. Asked before an await: dropped if
    /// someone else has signed in by the time it arrives.
    func profileChanged(_ profile: Profile, server: String, username: String) {
        write { saved in
            guard var saved, saved.server == server, saved.username == username, saved.profile != profile else {
                return saved
            }
            saved.userId = profile.userId
            saved.displayName = profile.displayName
            saved.avatar = profile.avatar
            return saved
        }
    }

    /// Forgets the tokens, keeps the server, username and profile.
    func signOut() {
        write { saved in saved?.signedOut }
    }

    func clear() { write { _ in nil } }

    func tokens() async -> Tokens? {
        state.withLock { state in
            guard let s = state.saved, let access = s.access, let refresh = s.refresh else { return nil }
            return Tokens(access: access, accessExpiresAt: s.accessExpiresAt, refresh: refresh)
        }
    }

    func refreshed(_ tokens: Tokens, replacing old: Tokens) async {
        write { saved in
            guard var saved, saved.refresh == old.refresh else { return saved }
            saved.access = tokens.access
            saved.accessExpiresAt = tokens.accessExpiresAt
            saved.refresh = tokens.refresh
            return saved
        }
    }

    func revoked(_ old: Tokens) async {
        write { saved in
            guard let saved, saved.refresh == old.refresh else { return saved }
            return saved.signedOut
        }
    }

    private func write(_ change: (Saved?) -> Saved?) {
        let next = state.withLock { state -> Saved? in
            let next = change(state.saved)
            if let next, let data = try? JSONEncoder().encode(next) {
                keychain.write(data)
            } else {
                keychain.delete()
            }
            state.saved = next
            return next
        }
        account.set(Self.accountOf(next))
        profile.set(next?.profile ?? Profile())
    }

    private static func accountOf(_ s: Saved?) -> Account {
        guard let s else { return .none }
        if s.access != nil && s.refresh != nil { return .signedIn(server: s.server, username: s.username) }
        return .signedOut(server: s.server, username: s.username)
    }
}

/// One generic-password item: this device only, readable after the first unlock.
nonisolated struct Keychain: Sendable {
    let service: String
    var accountName = "default"

    private var query: [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
            kSecAttrAccount as String: accountName,
        ]
    }

    func read() -> Data? {
        var q = query
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: AnyObject?
        guard SecItemCopyMatching(q as CFDictionary, &result) == errSecSuccess else { return nil }
        return result as? Data
    }

    func write(_ data: Data) {
        let attributes: [String: Any] = [
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        ]
        if SecItemUpdate(query as CFDictionary, attributes as CFDictionary) == errSecItemNotFound {
            SecItemAdd(query.merging(attributes) { $1 } as CFDictionary, nil)
        }
    }

    func delete() {
        SecItemDelete(query as CFDictionary)
    }
}
