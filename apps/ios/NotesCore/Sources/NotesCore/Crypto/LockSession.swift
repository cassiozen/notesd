import Foundation
import Synchronization

/// The locked-notes key on this device (§10.1). It is only ever in memory:
/// unlocked with the lock password against the synced keyring (so offline
/// too), dropped by `lock()` after `idle` without `touch()`, when the app
/// leaves the screen, or on request. Edits to locked notes are saved by the
/// `onBeforeLock` hooks while the key is still there; if one can't save, the
/// key stays (an unsaved edit would be lost) and the idle timer tries again.
///
/// The key is held against the lock password entry it came with. When a sync
/// brings a different one (a new password or a reset on another device) the
/// session locks: the key may be gone from the keyring, and new notes must
/// not be encrypted with it.
public final class LockSession: Sendable {
    public enum State: Hashable, Sendable {
        /// No lock password yet: nothing has been locked.
        case none
        /// Locked notes exist; their key isn't in memory.
        case locked
        /// The key is in memory: locked notes open without asking.
        case unlocked
    }

    /// How a keyring change ended. `unsupported`: the lock was set up by a newer notesd; no password opens it here.
    public enum Outcome: Hashable, Sendable { case done, wrongPassword, unsupported, notConnected, offline, failed }

    public typealias Hook = @Sendable () async -> Bool

    public let state = StateCell<State>(.none)

    private struct Inner {
        var key: Data?
        /// The password entry `key` was unlocked or made with.
        var keyEntry: String?
        var idleTask: Task<Void, Never>?
        var hooks: [UUID: Hook] = [:]
    }

    private let keys: LockKeyStore
    private let api: @Sendable () async -> ApiClient?
    private let iterations: Int
    private let idle: Duration
    private let mutex = AsyncLock()
    private let inner = Mutex(Inner())

    public init(
        keys: LockKeyStore, api: @escaping @Sendable () async -> ApiClient?,
        iterations: Int = LockedNotes.passwordIterations, idle: Duration = .seconds(5 * 60)
    ) {
        self.keys = keys
        self.api = api
        self.iterations = iterations
        self.idle = idle
    }

    /// The key while unlocked. Callers must not keep it.
    public func key() -> Data? { inner.withLock { $0.key } }

    /// The lock password entry the key in memory goes with; nil while locked.
    public func keyEntry() -> String? { inner.withLock { $0.keyEntry } }

    /// The key in memory and the entry it goes with, read together; nil while locked.
    public func keyWithEntry() -> (key: Data, entry: String)? {
        inner.withLock { inner in
            guard let key = inner.key, let entry = inner.keyEntry else { return nil }
            return (key, entry)
        }
    }

    /// Runs before the key is dropped, e.g. to save an open locked note while it
    /// can still be encrypted. False when that failed: the key is kept.
    /// Returns the call that removes the hook.
    public func onBeforeLock(_ hook: @escaping Hook) -> @Sendable () -> Void {
        let id = UUID()
        inner.withLock { $0.hooks[id] = hook }
        return { [weak self] in _ = self?.inner.withLock { $0.hooks.removeValue(forKey: id) } }
    }

    /// Re-reads the synced keyring: whether there's a lock password at all, and
    /// whether the key in memory still goes with it (see the class comment).
    public func refresh() async {
        let stale = await mutex.withLock {
            let entry: String?
            do {
                entry = try await self.passwordEntry()
            } catch {
                // An unread keyring says nothing about it: only one read showing another entry locks.
                return false
            }
            guard self.state.value == .unlocked else {
                self.state.set(entry == nil ? .none : .locked)
                return false
            }
            if entry == self.inner.withLock({ $0.keyEntry }) { return false }
            // The key may be gone from the keyring: from now on nothing is encrypted with it, not
            // even what the hooks save on the way out (an open note's edit waits for the next unlock).
            self.inner.withLock { $0.key = nil }
            return true
        }
        if stale { await lock(force: true) }
    }

    /// False on a wrong password, or when the keyring couldn't be read.
    public func unlock(_ password: String) async -> Bool { await unlocking(password) == .done }

    /// `unlock`, telling a wrong password (`.wrongPassword`) from a keyring
    /// this device couldn't read (`.failed`).
    public func unlocking(_ password: String) async -> Outcome {
        await mutex.withLock {
            guard let stored = try? await self.keys.keys() else { return .failed }
            let key: Data
            switch Result(catching: { () throws(LockedError) in try Keyring.unlock(stored, password) }) {
            case .success(let k): key = k
            case .failure(let error): return Self.outcome(error)
            }
            self.adopt(key, entry: Self.passwordEntry(stored))
            return .done
        }
    }

    /// Unlocks with a key kept on the device (behind Face ID), if the keyring
    /// still has the password entry it was kept against.
    public func unlockWithKey(_ key: Data, boundTo: String) async -> Bool {
        await mutex.withLock {
            guard key.count == LockedNotes.keyBytes, (try? await self.passwordEntry()) == boundTo else { return false }
            self.adopt(key, entry: boundTo)
            return true
        }
    }

    /// First use: a new key under `password`. Needs the server, which keeps the keyring for every device.
    public func setup(_ password: String) async -> Outcome {
        await mutex.withLock {
            guard let client = await self.api() else { return .notConnected }
            let created = Keyring.create(password, iterations: self.iterations)
            switch await client.putLockKey(created.entry, verifier: created.verifier) {
            case .ok(let saved):
                try? await self.keys.replace([saved])
                self.adopt(created.key, entry: saved.wrapped)
                return .done
            case let other:
                return Self.outcome(other)
            }
        }
    }

    /// Re-wraps the same key under `next`: no note changes, and other devices take it on their next sync.
    public func changePassword(current: String, next: String) async -> Outcome {
        await mutex.withLock {
            guard let client = await self.api() else { return .notConnected }
            guard let stored = try? await self.keys.keys() else { return .failed }
            let key: Data
            switch Result(catching: { () throws(LockedError) in try Keyring.unlock(stored, current) }) {
            case .success(let k): key = k
            case .failure(let error): return Self.outcome(error)
            }
            let entry = Keyring.passwordEntry(key, next, iterations: self.iterations)
            switch await client.putLockKey(entry, verifier: LockedNotes.verifier(key)) {
            case .ok(let saved):
                try? await self.keys.replace([saved] + stored.filter { $0.id != saved.id })
                self.adopt(key, entry: saved.wrapped)
                return .done
            case let other:
                return Self.outcome(other)
            }
        }
    }

    /// Forgets the keyring everywhere after a forgotten lock password; notes locked so far can't be opened again.
    public func reset(accountPassword: String) async -> Outcome {
        await mutex.withLock {
            guard let client = await self.api() else { return .notConnected }
            switch await client.resetLocks(accountPassword: accountPassword) {
            case .ok:
                try? await self.keys.replace([])
                self.drop()
                self.state.set(.none)
                return .done
            case .rejected(401, _, _, _):
                return .wrongPassword
            case let other:
                return Self.outcome(other)
            }
        }
    }

    /// Saves what the hooks have pending, then drops the key. If a hook couldn't
    /// save, the key stays and false comes back, unless `force`d (the key no
    /// longer goes with the keyring, so it mustn't stay).
    @discardableResult
    public func lock(force: Bool = false) async -> Bool {
        if state.value != .unlocked { return true }
        var saved = true
        for hook in inner.withLock({ Array($0.hooks.values) }) {
            saved = await hook() && saved
        }
        if !saved && !force {
            touch()
            return false
        }
        await mutex.withLock {
            self.drop()
            self.state.set((try? await self.passwordEntry()) == nil ? .none : .locked)
        }
        return true
    }

    /// Signed out or wiped: drop the key without saving.
    public func forget() async {
        await mutex.withLock {
            self.drop()
            self.state.set(.none)
        }
    }

    /// The user did something: the idle timeout starts over.
    public func touch() {
        guard state.value == .unlocked else { return }
        let idle = self.idle
        let task = Task { [weak self] in
            try? await Task.sleep(for: idle)
            if !Task.isCancelled { await self?.lock() }
        }
        inner.withLock { inner in
            inner.idleTask?.cancel()
            inner.idleTask = task
        }
    }

    private func adopt(_ key: Data, entry: String?) {
        inner.withLock { inner in
            inner.key = key
            inner.keyEntry = entry
        }
        state.set(.unlocked)
        touch()
    }

    private func drop() {
        inner.withLock { inner in
            inner.idleTask?.cancel()
            inner.idleTask = nil
            inner.key = nil
            inner.keyEntry = nil
        }
    }

    /// The lock password entry as synced; nil when there's none. A key kept
    /// elsewhere on the device (behind Face ID) is only good while this is
    /// unchanged: a reset replaces the key, so a stale copy must never encrypt new notes.
    public func passwordEntry() async throws -> String? { Self.passwordEntry(try await keys.keys()) }

    private static func passwordEntry(_ keys: [LockKey]) -> String? {
        keys.first { $0.kind == WrapKind.password.rawValue }?.wrapped
    }

    private static func outcome(_ error: LockedError) -> Outcome {
        switch error.reason {
        case .invalid: .wrongPassword
        case .unsupportedKdf, .unsupportedVersion: .unsupported
        case .malformedEntry: .failed
        }
    }

    private static func outcome<T>(_ result: ApiResult<T>) -> Outcome {
        switch result {
        case .unreachable, .rateLimited: .offline
        case .unauthenticated: .notConnected
        default: .failed
        }
    }
}
