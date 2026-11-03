import Foundation
// The app and the tests name these types through NotesCore.
@_exported import NotesBiometrics
import Observation

/// Face ID unlock for locked notes (§10.1): the locked-notes key kept behind
/// `biometrics`, on this device only. It's kept against the lock password
/// entry it was made with; once that changes (a new password, or a reset
/// elsewhere) it stops working and the password is asked for, so a stale key
/// never encrypts new notes.
@MainActor
@Observable
public final class BiometricUnlock {
    public enum Outcome: Sendable, Equatable {
        case done
        case cancelled
        /// The password changed, the keyring was reset or Face ID changed: turned off, use the password.
        case stale
        /// Locked notes must be unlocked (with the password) before Face ID unlock can be turned on.
        case needsUnlock
        case failed
    }

    /// Turned on here. Whether it still works shows on use.
    public private(set) var enabled: Bool
    /// The offer after the lock password is waiting for an answer: the app shows its question.
    public private(set) var asking = false

    @ObservationIgnored private let locks: LockSession
    @ObservationIgnored private let biometrics: Biometrics
    @ObservationIgnored private let defaults: UserDefaults
    @ObservationIgnored private let ask: (@MainActor () async -> Bool)?
    @ObservationIgnored private var answering: CheckedContinuation<Bool, Never>?
    @ObservationIgnored private var offering: Task<Void, Never>?

    /// `ask`: answers the offer's question instead of `asking` and `answer(_:)`.
    public init(
        locks: LockSession, biometrics: Biometrics, defaults: UserDefaults = .standard,
        ask: (@MainActor () async -> Bool)? = nil
    ) {
        self.locks = locks
        self.biometrics = biometrics
        self.defaults = defaults
        self.ask = ask
        enabled = defaults.string(forKey: Self.bound) != nil
    }

    public func available() -> Bool { biometrics.available() }

    /// The lock password was just typed: unless Face ID unlock is on, turned
    /// off in Settings or "Not now" was chosen before, offer it straight away,
    /// so it's the last time the password is needed here.
    @discardableResult
    public func offer() -> Task<Void, Never>? {
        if enabled || offering != nil || defaults.bool(forKey: Self.declined) || !available() { return nil }
        let task = Task {
            defer { offering = nil }
            let declined = await question() ? (await enable()) == .cancelled : true
            // Only a choice is remembered: a failure (locked out, interrupted) offers again next time.
            if declined { defaults.set(true, forKey: Self.declined) }
        }
        offering = task
        return task
    }

    /// The answer to the offer's question.
    public func answer(_ yes: Bool) {
        asking = false
        answering?.resume(returning: yes)
        answering = nil
    }

    private func question() async -> Bool {
        if let ask { return await ask() }
        return await withCheckedContinuation { continuation in
            answering = continuation
            asking = true
        }
    }

    public func enable() async -> Outcome {
        guard let held = locks.keyWithEntry() else { return .needsUnlock }
        let current: String?
        do {
            current = try await locks.passwordEntry()
        } catch {
            return .failed
        }
        // The key in memory goes with an older keyring (a new password or a reset just synced, and the
        // session hasn't locked yet): kept against the new entry, it would encrypt notes no password opens.
        guard current == held.entry else { return .needsUnlock }
        switch await biometrics.seal(held.key) {
        case .ok:
            // The keyring may have moved on while Face ID was asked for.
            guard (try? await locks.passwordEntry()) == held.entry else {
                biometrics.forget()
                return .failed
            }
            defaults.set(held.entry, forKey: Self.bound)
            defaults.removeObject(forKey: Self.declined)
            enabled = true
            return .done
        case .cancelled: return .cancelled
        default: return .failed
        }
    }

    public func unlock() async -> Outcome {
        guard let bound = defaults.string(forKey: Self.bound) else { return stale() }
        let current: String?
        do {
            current = try await locks.passwordEntry()
        } catch {
            // An unread keyring says nothing about the kept key: it stays on, and the password works meanwhile.
            return .failed
        }
        guard current == bound else { return stale() }
        switch await biometrics.open() {
        case .ok(let key):
            return await locks.unlockWithKey(key, boundTo: bound) ? .done : stale()
        case .cancelled: return .cancelled
        case .invalidated: return stale()
        case .failed: return .failed
        }
    }

    /// This device changed the lock password from the entry `rewrapped`: the
    /// key is the same, so the kept copy follows the new entry. Only if it was
    /// kept against that entry, though; one kept against an older keyring
    /// (reset elsewhere since) is stale, and turned off rather than revived.
    public func passwordChanged(rewrapped: String?) {
        guard let kept = defaults.string(forKey: Self.bound) else { return }
        if let rewrapped, let next = locks.keyEntry(), kept == rewrapped {
            defaults.set(next, forKey: Self.bound)
        } else {
            disable()
        }
    }

    /// Turned off in Settings: not offered again after the password, only turned on from there.
    public func turnOff() {
        disable()
        defaults.set(true, forKey: Self.declined)
    }

    /// Off, and offered again: it went stale, or the account signed out.
    public func disable() {
        defaults.removeObject(forKey: Self.bound)
        defaults.removeObject(forKey: Self.declined)
        biometrics.forget()
        enabled = false
    }

    private func stale() -> Outcome {
        disable()
        return .stale
    }

    public static let bound = "biometricBoundTo"
    public static let declined = "biometricDeclined"
}
