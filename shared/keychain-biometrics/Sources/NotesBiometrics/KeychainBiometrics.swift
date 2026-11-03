import Foundation
import LocalAuthentication
import Security

/// Face ID or Touch ID, as this device has it: what the app calls it, and its symbol.
public struct BiometryKind: Sendable {
    public let name: String
    public let symbol: String

    public static let faceID = BiometryKind(name: "Face ID", symbol: "faceid")
    public static let touchID = BiometryKind(name: "Touch ID", symbol: "touchid")

    /// What this device has enrolled; Face ID when it can't tell.
    public static func current() -> BiometryKind {
        let context = LAContext()
        _ = context.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: nil)
        return context.biometryType == .touchID ? .touchID : .faceID
    }
}

/// The locked-notes key as a Keychain item that only Face ID (or Touch ID)
/// reads, every time, on this device only. The system makes it unreadable when
/// the enrolled faces or fingers change (`.biometryCurrentSet`), as Android's
/// Keystore key is destroyed then.
public final class KeychainBiometrics: Biometrics {
    private let service: String
    private let kind: BiometryKind

    public init(service: String, kind: BiometryKind) {
        self.service = service
        self.kind = kind
    }

    public func available() -> Bool {
        LAContext().canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: nil)
    }

    public func seal(_ key: Data) async -> BiometricResult {
        let context = LAContext()
        context.localizedCancelTitle = "Not Now"
        if let refused = await evaluate(context, reason: Self.reason("Turn on \(kind.name) unlock for locked notes")) {
            return refused
        }
        guard
            let access = SecAccessControlCreateWithFlags(
                nil, kSecAttrAccessibleWhenUnlockedThisDeviceOnly, .biometryCurrentSet, nil)
        else { return .failed }
        SecItemDelete(query as CFDictionary)
        var item = query
        item[kSecValueData as String] = key
        item[kSecAttrAccessControl as String] = access
        item[kSecUseAuthenticationContext as String] = context
        return SecItemAdd(item as CFDictionary, nil) == errSecSuccess ? .ok(Data()) : .failed
    }

    public func open() async -> BiometricResult {
        // Gone (Face ID enrolled again, or never kept): say so without asking for a face first.
        let probe = LAContext()
        probe.interactionNotAllowed = true
        var check = query
        check[kSecUseAuthenticationContext as String] = probe
        switch SecItemCopyMatching(check as CFDictionary, nil) {
        case errSecInteractionNotAllowed, errSecSuccess: break
        case errSecItemNotFound, errSecAuthFailed: return .invalidated
        // Anything else (the Keychain not available for now) says nothing about the item: try again later.
        default: return .failed
        }

        let context = LAContext()
        context.localizedFallbackTitle = "Use Password"
        if let refused = await evaluate(context, reason: Self.reason("Open your locked notes")) { return refused }
        // Already confirmed: the read doesn't ask again.
        context.interactionNotAllowed = true
        var read = query
        read[kSecReturnData as String] = true
        read[kSecMatchLimit as String] = kSecMatchLimitOne
        read[kSecUseAuthenticationContext as String] = context
        var result: AnyObject?
        switch SecItemCopyMatching(read as CFDictionary, &result) {
        case errSecSuccess:
            guard let key = result as? Data else { return .failed }
            return .ok(key)
        case errSecItemNotFound, errSecAuthFailed: return .invalidated
        default: return .failed
        }
    }

    public func forget() {
        SecItemDelete(query as CFDictionary)
    }

    private var query: [String: Any] {
        var query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
            kSecAttrAccount as String: "key",
        ]
        #if os(macOS)
            // Access control (the biometric check) works only in the data protection keychain, which iOS always
            // uses; the Mac's default, file-based one rejects it. Needs a signed app with an application identifier.
            query[kSecUseDataProtectionKeychain as String] = true
        #endif
        return query
    }

    /// The prompt's line. The Mac puts "<app> is trying to" before it; iOS shows it as it is.
    private static func reason(_ text: String) -> String {
        #if os(macOS)
            text.prefix(1).lowercased() + text.dropFirst()
        #else
            text + "."
        #endif
    }

    /// Nil once Face ID matched; otherwise what stopped it. Only the user's own
    /// dismissal is `cancelled`, so a lockout or an interruption isn't taken for a choice.
    private func evaluate(_ context: LAContext, reason: String) async -> BiometricResult? {
        do {
            return try await context.evaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, localizedReason: reason)
                ? nil : .failed
        } catch let error as LAError where error.code == .userCancel || error.code == .userFallback {
            return .cancelled
        } catch {
            return .failed
        }
    }
}
