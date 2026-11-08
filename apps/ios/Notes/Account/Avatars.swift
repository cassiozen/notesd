import Foundation
import NotesCore
import UIKit

/// Profile photos (architecture §10), in files so they show offline: one per account, named for its version, so a
/// new photo replaces the old and a stale one is never shown.
@MainActor
final class Avatars {
    private let dir: URL
    private let api: @Sendable () -> ApiClient?
    private var decoded: [String: UIImage] = [:]

    init(dir: URL, api: @escaping @Sendable () -> ApiClient?) {
        self.dir = dir
        self.api = api
    }

    /// Already in memory: shown in the first frame, without a flash of the initial.
    func cached(_ profile: Profile) -> UIImage? { decoded[key(profile)] }

    /// Nil without a photo, or while one can't be downloaded.
    func load(_ profile: Profile) async -> UIImage? {
        guard profile.userId != 0, !profile.avatar.isEmpty else { return nil }
        if let image = cached(profile) { return image }
        let file = dir.appending(path: "\(key(profile)).jpg")
        var data = try? Data(contentsOf: file)
        if data == nil, let fetched = await api()?.avatar(userId: profile.userId, version: profile.avatar).value {
            save(fetched, for: profile)
            data = fetched
        }
        guard let data, let image = UIImage(data: data) else { return nil }
        decoded[key(profile)] = image
        return image
    }

    /// A photo just set here, so it shows at once.
    func put(_ jpeg: Data, for profile: Profile) {
        save(jpeg, for: profile)
        decoded[key(profile)] = UIImage(data: jpeg)
    }

    func wipe() {
        decoded = [:]
        try? FileManager.default.removeItem(at: dir)
    }

    private func save(_ data: Data, for profile: Profile) {
        let fm = FileManager.default
        try? fm.createDirectory(at: dir, withIntermediateDirectories: true)
        for old in (try? fm.contentsOfDirectory(atPath: dir.path)) ?? [] where old.hasPrefix("\(profile.userId)-") {
            try? fm.removeItem(at: dir.appending(path: old))
        }
        try? data.write(to: dir.appending(path: "\(key(profile)).jpg"), options: .atomic)
    }

    private func key(_ profile: Profile) -> String { "\(profile.userId)-\(profile.avatar)" }
}
