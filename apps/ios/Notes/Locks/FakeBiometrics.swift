import Foundation
import NotesCore
import Synchronization

/// Face ID for UI tests: `--fake-biometrics=match` matches at once,
/// `--fake-biometrics=cancel` is dismissed. Without the flag it isn't available,
/// so flows that don't test it aren't interrupted.
nonisolated final class FakeBiometrics: Biometrics {
    private let mode: String?
    private let kept = Mutex<Data?>(nil)

    init(arguments: [String]) {
        let flag = "--fake-biometrics="
        let value = arguments.first { $0.hasPrefix(flag) }.map { String($0.dropFirst(flag.count)) }
        mode = value?.isEmpty == false ? value : nil
    }

    func available() -> Bool { mode != nil }

    func seal(_ key: Data) async -> BiometricResult {
        guard mode == "match" else { return .cancelled }
        kept.withLock { $0 = key }
        return .ok(Data())
    }

    func open() async -> BiometricResult {
        guard mode == "match" else { return .cancelled }
        return kept.withLock { $0 }.map { .ok($0) } ?? .invalidated
    }

    func forget() { kept.withLock { $0 = nil } }
}
