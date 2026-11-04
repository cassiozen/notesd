import Foundation
import NotesBiometrics
import Security

// Called from Rust on a blocking thread (src/touch_id.rs): Touch ID waits on the user, so each call blocks until
// it's answered. Results are BiometricResult as a number: 0 ok, 1 cancelled, 2 invalidated, 3 failed.

@_cdecl("notesd_touch_id_available")
public func available() -> Bool {
    // Unsigned builds (dev, CI) can't use the data protection keychain the key is kept in: no Touch ID there.
    signed() && KeychainBiometrics(service: "", kind: .touchID).available()
}

@_cdecl("notesd_touch_id_seal")
public func seal(_ service: UnsafePointer<CChar>, _ key: UnsafePointer<UInt8>, _ length: Int) -> Int32 {
    let biometrics = KeychainBiometrics(service: String(cString: service), kind: .touchID)
    let data = Data(bytes: key, count: length)
    return code(wait { await biometrics.seal(data) })
}

/// The key into `out` (room for `capacity` bytes), its length into `length`.
@_cdecl("notesd_touch_id_open")
public func open(
    _ service: UnsafePointer<CChar>, _ out: UnsafeMutablePointer<UInt8>, _ capacity: Int,
    _ length: UnsafeMutablePointer<Int>
) -> Int32 {
    let biometrics = KeychainBiometrics(service: String(cString: service), kind: .touchID)
    let result = wait { await biometrics.open() }
    guard case .ok(var key) = result else { return code(result) }
    defer { key.resetBytes(in: 0..<key.count) }
    guard key.count <= capacity else { return code(.failed) }
    key.copyBytes(to: out, count: key.count)
    length.pointee = key.count
    return code(result)
}

@_cdecl("notesd_touch_id_forget")
public func forget(_ service: UnsafePointer<CChar>) {
    KeychainBiometrics(service: String(cString: service), kind: .touchID).forget()
}

private func code(_ result: BiometricResult) -> Int32 {
    switch result {
    case .ok: 0
    case .cancelled: 1
    case .invalidated: 2
    case .failed: 3
    }
}

/// An application identifier comes only with signing and its provisioning profile.
private func signed() -> Bool {
    guard let task = SecTaskCreateFromSelf(nil) else { return false }
    return SecTaskCopyValueForEntitlement(task, "com.apple.application-identifier" as CFString, nil) != nil
}

private final class Box: @unchecked Sendable {
    var result = BiometricResult.failed
}

private func wait(_ work: @escaping @Sendable () async -> BiometricResult) -> BiometricResult {
    let box = Box()
    let done = DispatchSemaphore(value: 0)
    Task.detached {
        box.result = await work()
        done.signal()
    }
    done.wait()
    return box.result
}
