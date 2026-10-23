import Foundation
import NotesCore
import Observation

/// Server address → new account (when the server has none yet) or sign in. With `lockedServer` it's
/// "sign in again": straight to sign-in on the server these notes belong to. A port of Android's OnboardingViewModel.
///
/// Signed out, only the person whose notes these are can sign in: anyone else's first sync would take this iPhone's
/// notes into their account. Someone else removes all data first.
@MainActor
@Observable
final class OnboardingModel {
    enum Step { case server, account, signIn }
    enum Field { case server, username, password, confirm }

    private(set) var step: Step
    var server: String
    var username: String
    var password = ""
    var confirm = ""
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
            case .account: .server
            case .signIn: lockedServer == nil ? .server : nil
            }
        guard let previous else { return false }
        step = previous
        password = ""
        confirm = ""
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
            case .ok(let status):
                self.url = url
                self.step = status.setupRequired ? .account : .signIn
            case let other: self.fail(message: Self.message(other))
            }
        }
    }

    func submitAccount() {
        run {
            var errors: [Field: String] = [:]
            if self.username.trimmed.isEmpty { errors[.username] = "Choose a username." }
            if self.password.count < 8 {
                errors[.password] = "Use at least 8 characters."
            } else if self.confirm != self.password {
                errors[.confirm] = "The passwords don’t match."
            }
            if !errors.isEmpty { return self.fail(errors) }
            // Before setup: an account made under another name would be left on the server, unused.
            if !self.isLockedUser(self.username) { return self.fail(message: self.otherUserMessage) }
            guard let url = self.url else { return }
            let api = ApiClient(baseURL: url)
            // The fields stay bound while setup runs; login must use what setup did.
            let username = self.username.trimmed
            let password = self.password
            switch await api.setup(username: username, password: password) {
            case .ok: break
            case .rejected(_, "invalid_request", let message, let field?):
                let message = message.prefix(1).uppercased() + message.dropFirst() + "."
                return self.fail([field == "username" ? .username : .password: message])
            case .conflict:
                self.step = .signIn
                self.password = ""
                self.confirm = ""
                return self.fail(message: "This server already has an account. Sign in instead.")
            case let other:
                return self.fail(message: Self.message(other))
            }
            switch await api.login(username: username, password: password, deviceName: self.deviceName) {
            case .ok(let signIn): self.finish(signIn)
            case let other:
                // The account exists now; plain sign-in takes it from here.
                self.step = .signIn
                self.password = ""
                self.confirm = ""
                self.fail(message: Self.message(other))
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
        confirm = ""
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
        case .rejected(let status, let error, _, _) where error == ApiClient.unexpectedResponse || status == 404:
            "There’s no notes server at that address."
        case .rejected(let status, _, let message, _):
            message.isEmpty ? "The server refused that (error \(status))." : message
        case .conflict(_, let message, _): message
        default: "Something went wrong. Try again."
        }
    }
}
