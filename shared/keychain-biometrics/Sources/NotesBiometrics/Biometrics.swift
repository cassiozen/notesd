import Foundation

/// What asking for Face ID (or Touch ID) came to.
public enum BiometricResult: Sendable, Equatable {
    case ok(Data)
    /// Dismissed by the user: Cancel or "Use Password".
    case cancelled
    /// The kept key is gone: Face ID was enrolled again, or the passcode turned off.
    case invalidated
    /// Locked out, interrupted by the system, or not available.
    case failed
}

/// Keeps the locked-notes key behind the device's biometric. The apps use
/// the Keychain; tests fake it.
public protocol Biometrics: Sendable {
    /// Enrolled and allowed for this app.
    func available() -> Bool
    /// Asks for the biometric, then keeps `key` behind it, replacing what was kept.
    func seal(_ key: Data) async -> BiometricResult
    /// Asks for the biometric, then gives back the kept key.
    func open() async -> BiometricResult
    func forget()
}
