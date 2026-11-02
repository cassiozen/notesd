import Foundation

/// Keyring logic shared by every way of unlocking (§10.1), apart from storage
/// and the network. The locked-notes key never leaves memory unwrapped.
public enum Keyring {
    /// A brand-new key and its password entry, to upload with `verifier`.
    public struct Created: Sendable {
        public var key: Data
        public var entry: LockKey
        public var verifier: String
    }

    public static func create(_ password: String, iterations: Int = LockedNotes.passwordIterations) -> Created {
        let key = LockedNotes.newKey()
        return Created(
            key: key, entry: passwordEntry(key, password, iterations: iterations), verifier: LockedNotes.verifier(key))
    }

    /// The password entry for `key`: a new salt, wrapped under `password`.
    public static func passwordEntry(_ key: Data, _ password: String, iterations: Int = LockedNotes.passwordIterations)
        -> LockKey
    {
        let salt = LockedNotes.randomBytes(16)
        // Our own count, always positive.
        let kek = try! LockedNotes.passwordKek(password, salt: salt, iterations: iterations)
        return LockKey(
            id: WrapKind.password.rawValue, kind: WrapKind.password.rawValue, salt: LockedNotes.encode(salt),
            iterations: iterations, wrapped: LockedNotes.wrap(kek, key, kind: .password),
            kdf: Kdf(name: Kdf.pbkdf2, iterations: iterations))
    }

    /// How an entry's secret becomes the key that unwraps it.
    public enum Derivation: Hashable, Sendable {
        case pbkdf2(iterations: Int)
        case hkdf
    }

    /// `entry`'s derivation: from its `kdf`, or implied by its kind for entries from before 1.0. A function
    /// this app doesn't know is `.unsupportedKdf`; one that doesn't fit the kind, or no usable
    /// iteration count, `.malformedEntry`.
    public static func derivation(_ entry: LockKey) throws(LockedError) -> Derivation {
        let kind = WrapKind(rawValue: entry.kind)
        let name = entry.kdf?.name ?? (kind == .passkey ? Kdf.hkdf : Kdf.pbkdf2)
        switch name {
        case Kdf.pbkdf2:
            // The top-level count only stands in for a missing kdf (entries from before 1.0).
            let iterations = entry.kdf.map { $0.iterations } ?? entry.iterations
            guard kind == .password || kind == .recovery, let iterations, let rounds = UInt32(exactly: iterations),
                rounds > 0
            else { throw LockedNotes.malformedEntry }
            return .pbkdf2(iterations: iterations)
        case Kdf.hkdf:
            guard kind == .passkey else { throw LockedNotes.malformedEntry }
            return .hkdf
        default:
            throw .unsupportedKdf
        }
    }

    /// The key, from the password entry. An error means a wrong password (or no lock password yet), unless
    /// its `reason` says the entry can't be used here at all.
    public static func unlock(_ keys: [LockKey], _ password: String) throws(LockedError) -> Data {
        guard let entry = keys.first(where: { $0.kind == WrapKind.password.rawValue }) else {
            throw LockedError("No lock password set")
        }
        guard case .pbkdf2(let iterations) = try derivation(entry) else { throw LockedNotes.malformedEntry }
        let kek = try LockedNotes.passwordKek(
            password, salt: try LockedNotes.decode(entry.salt), iterations: iterations)
        return try LockedNotes.unwrap(kek, entry.wrapped, kind: .password)
    }
}
