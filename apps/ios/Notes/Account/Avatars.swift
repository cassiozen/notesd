import Foundation
import NotesCore
import UIKit

/// Profile photos (architecture §10), in files so they show offline: one per account, named for its version, so a
/// new photo replaces the old and a stale one is never shown.
@MainActor
final class Avatars {
    private let dir: URL
    private let api: @Sendable () -> ApiClient?
    /// One per account, its current version: an older one is dropped when a newer arrives.
    private var decoded: [Int64: (version: String, image: UIImage)] = [:]
    /// Moved on by wipe: a download that finishes afterwards mustn't bring a deleted photo back.
    private var generation = 0

    init(dir: URL, api: @escaping @Sendable () -> ApiClient?) {
        self.dir = dir
        self.api = api
    }

    /// Already in memory: shown in the first frame, without a flash of the initial.
    func cached(_ profile: Profile) -> UIImage? {
        decoded[profile.userId].flatMap { $0.version == profile.avatar ? $0.image : nil }
    }

    /// Nil without a photo, or while one can't be downloaded.
    func load(_ profile: Profile) async -> UIImage? {
        guard profile.userId != 0, !profile.avatar.isEmpty else { return nil }
        if let image = cached(profile) { return image }
        let gen = generation
        let file = file(profile)
        let onDisk = await Task.detached(priority: .userInitiated) { try? Data(contentsOf: file) }.value
        if let onDisk, let image = await Self.decode(onDisk) { return keep(image, for: profile, gen) }
        guard let fetched = await api()?.avatar(userId: profile.userId, version: profile.avatar).value,
            // Not a picture (a captive portal's page, say): not kept, so it's asked for again next time.
            let image = await Self.decode(fetched)
        else { return nil }
        await save(fetched, for: profile, gen)
        return keep(image, for: profile, gen)
    }

    /// A photo just set here, so it shows at once.
    func put(_ jpeg: Data, for profile: Profile) async {
        let gen = generation
        guard let image = await Self.decode(jpeg) else { return }
        await save(jpeg, for: profile, gen)
        _ = keep(image, for: profile, gen)
    }

    /// `userId`'s photo was removed.
    func forget(_ userId: Int64) async {
        decoded[userId] = nil
        await Self.removeFiles(of: userId, in: dir)
    }

    func wipe() {
        generation += 1
        decoded = [:]
        try? FileManager.default.removeItem(at: dir)
    }

    private func keep(_ image: UIImage, for profile: Profile, _ gen: Int) -> UIImage {
        if gen == generation { decoded[profile.userId] = (profile.avatar, image) }
        return image
    }

    private func file(_ profile: Profile) -> URL { dir.appending(path: "\(profile.userId)-\(profile.avatar).jpg") }

    private func save(_ data: Data, for profile: Profile, _ gen: Int) async {
        guard gen == generation else { return }
        let (dir, file, userId) = (dir, file(profile), profile.userId)
        await Self.removeFiles(of: userId, in: dir)
        await Task.detached(priority: .utility) {
            try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            try? data.write(to: file, options: .atomic)
        }.value
        // Wiped while writing.
        if gen != generation { await Self.removeFiles(of: userId, in: dir) }
    }

    private static func removeFiles(of userId: Int64, in dir: URL) async {
        await Task.detached(priority: .utility) {
            let fm = FileManager.default
            for old in (try? fm.contentsOfDirectory(atPath: dir.path)) ?? [] where old.hasPrefix("\(userId)-") {
                try? fm.removeItem(at: dir.appending(path: old))
            }
        }.value
    }

    /// Decoded off the main thread, so the first frame that shows it doesn't.
    private static func decode(_ data: Data) async -> UIImage? {
        await UIImage(data: data)?.byPreparingForDisplay()
    }
}
