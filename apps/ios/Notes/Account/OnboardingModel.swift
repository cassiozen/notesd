import Foundation
import NotesCore
import Observation

/// Server address → sign in. With `lockedServer` it's "sign in again": straight to sign-in on the server these
/// notes belong to. A port of Android's OnboardingViewModel, minus creating the first account: App Store
/// guideline 5.1.1(v) requires an app that creates accounts to delete them too, and notesd can't delete one. A
/// server with no account yet sends the person to a browser for it.
///
/// Signed out, only the person whose notes these are can sign in: anyone else's first sync would take this iPhone's
/// notes into their account. Someone else removes all data first.
@MainActor
@Observable
final class OnboardingModel {
    enum Step { case server, signIn }
    enum Field { case server }

    private(set) var step: Step
    var server: String
    var username: String
    var password = ""
    private(set) var busy = false
    /// About the whole step, shown above its button.
    private(set) var error: String?
    private(set) var fieldErrors: [Field: String] = [:]
    private(set) var done = false

    let lockedServer: String?
    /// Whose notes this iPhone holds while signed out; nobody else may sign in.
    let lockedUsername: String?
    private let accounts: AccountStore
    private let deviceName: String
    private var url: URL?
    private var work: Task<Void, Never>?

    init(accounts: AccountStore, deviceName: String, lockedServer: String? = nil, username: String = "") {
        self.accounts = accounts
        self.deviceName = deviceName
        self.lockedServer = lockedServer
        if case .signedOut(_, let owner) = accounts.account.value {
            lockedUsername = owner
        } else {
            lockedUsername = nil
        }
        self.username = lockedUsername ?? username
        server = lockedServer ?? ""
        step = lockedServer == nil ? .server : .signIn
        url = lockedServer.flatMap(URL.init(string:))
    }

    func clearErrors() {
        error = nil
        fieldErrors = [:]
    }

    /// Back within the flow. False when there's no earlier step to go to.
    func back() -> Bool {
        let previous: Step? =
            switch step {
            case .server: nil
            case .signIn: lockedServer == nil ? .server : nil
            }
        guard let previous else { return false }
        step = previous
        password = ""
        clearErrors()
        return true
    }

    func submitServer() {
        run {
            guard case .valid(let url) = ServerAddress.parse(self.server) else {
                if case .invalid(let message) = ServerAddress.parse(self.server) { self.fail([.server: message]) }
                return
            }
            let api = ApiClient(baseURL: url)
            switch await api.health() {
            case .ok: break
            case .unreachable:
                return self.fail(
                    message: "Can’t reach a server at \(url.host() ?? self.server). Check the address, and that this "
                        + "iPhone is on the same network if the server is at home.")
            case let other: return self.fail(message: Self.message(other))
            }
            switch await api.status() {
            case .ok(let status) where status.setupRequired:
                // No step of its own: tapping Continue after creating it re-checks and moves on to sign-in.
                // Signed out, only this iPhone's owner may sign in, so an account under any other name goes unused.
                let name = self.lockedUsername.map { " with the username \($0)" } ?? ""
                self.fail(
                    message: "\(url.host() ?? self.server) has no account yet. Create it\(name) in a web browser at "
                        + "\(url.absoluteString), then come back and tap Continue.")
            case .ok:
                self.url = url
                self.step = .signIn
            case let other: self.fail(message: Self.message(other))
            }
        }
    }

    func submitSignIn() {
        run {
            if self.username.trimmed.isEmpty || self.password.isEmpty {
                return self.fail(message: "Enter your username and password.")
            }
            if !self.isLockedUser(self.username) { return self.fail(message: self.otherUserMessage) }
            guard let url = self.url else { return }
            let result = await ApiClient(baseURL: url)
                .login(username: self.username.trimmed, password: self.password, deviceName: self.deviceName)
            switch result {
            case .ok(let signIn): self.finish(signIn)
            case .rejected(_, "invalid_credentials", _, _): self.fail(message: "Wrong username or password.")
            case let other: self.fail(message: Self.message(other))
            }
        }
    }

    /// Leaving the flow: a request still in flight must not sign in afterwards.
    func cancel() {
        work?.cancel()
        work = nil
        busy = false
    }

    private func finish(_ signIn: SignIn) {
        guard !Task.isCancelled, let url else { return }
        // The server's spelling of the username, in case it compares names differently than this iPhone.
        if !isLockedUser(signIn.username) { return fail(message: otherUserMessage) }
        accounts.signedIn(server: url.absoluteString, signIn)
        password = ""
        done = true
    }

    /// Usernames compare as the server compares them: trimmed, ignoring case.
    private func isLockedUser(_ username: String) -> Bool {
        guard let lockedUsername else { return true }
        return username.trimmed.lowercased() == lockedUsername.trimmed.lowercased()
    }

    private var otherUserMessage: String {
        "This iPhone has \(lockedUsername ?? "")’s notes. To sign in as someone else, remove all data from this "
            + "iPhone in Settings first."
    }

    private func run(_ block: @escaping @MainActor () async -> Void) {
        if busy { return }
        busy = true
        clearErrors()
        work = Task {
            await block()
            guard !Task.isCancelled else { return }
            busy = false
            work = nil
        }
    }

    private func fail(_ fields: [Field: String] = [:], message: String? = nil) {
        error = message
        fieldErrors = fields
    }

    private static func message<T>(_ r: ApiResult<T>) -> String {
        switch r {
        case .unreachable: "Can’t reach the server. Check your connection and try again."
        case .rateLimited: "Too many attempts. Wait a moment and try again."
        case .unexpected, .rejected(404, _, _, _):
            "There’s no notes server at that address."
        case .upgradeRequired: SyncMessages.upgradeRequired
        case .rejected(let status, _, let message, _):
            message.isEmpty ? "The server refused that (error \(status))." : message
        case .conflict(_, let message, _, _): message
        default: "Something went wrong. Try again."
        }
    }
}
