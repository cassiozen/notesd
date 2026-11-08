import Foundation
import NotesCore
import UIKit

/// Profile photos (architecture §10), in files so they show offline: one per account, named for its version, so a
/// new photo replaces the old and a stale one is never shown.
@MainActor
final class Avatars {
    private let dir: URL
    private let api: @Sendable () -> ApiClient?
    /// By account, its latest version only; the system evicts under memory pressure, or past the cost limit.
    private let decoded: NSCache<NSNumber, Entry> = {
        let cache = NSCache<NSNumber, Entry>()
        cache.totalCostLimit = 16 << 20
        return cache
    }()
    // Moved on by wipe, for everyone, and by put and forget, for one account: what a download in flight finds
    // changed when it lands is no longer wanted.
    private var generation = 0
    private var changed: [Int64: Int] = [:]

    private final class Entry {
        let version: String
        let image: UIImage
        init(_ version: String, _ image: UIImage) { (self.version, self.image) = (version, image) }
    }

    private struct Stamp: Equatable {
        let generation: Int
        let changed: Int
    }

    init(dir: URL, api: @escaping @Sendable () -> ApiClient?) {
        self.dir = dir
        self.api = api
    }

    /// Already in memory: shown in the first frame, without a flash of the initial.
    func cached(_ profile: Profile) -> UIImage? {
        decoded.object(forKey: profile.userId as NSNumber).flatMap { $0.version == profile.avatar ? $0.image : nil }
    }

    /// Nil without a photo, or while one can't be downloaded.
    func load(_ profile: Profile) async -> UIImage? {
        guard profile.userId != 0, !profile.avatar.isEmpty else { return nil }
        if let image = cached(profile) { return image }
        let gen = stamp(profile.userId)
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
        guard let image = await Self.decode(jpeg) else { return }
        changed[profile.userId, default: 0] += 1
        let gen = stamp(profile.userId)
        await save(jpeg, for: profile, gen)
        _ = keep(image, for: profile, gen)
    }

    /// `userId`'s photo was removed.
    func forget(_ userId: Int64) async {
        changed[userId, default: 0] += 1
        decoded.removeObject(forKey: userId as NSNumber)
        await Self.removeFiles(of: userId, in: dir)
    }

    func wipe() {
        generation += 1
        decoded.removeAllObjects()
        try? FileManager.default.removeItem(at: dir)
    }

    private func stamp(_ userId: Int64) -> Stamp { Stamp(generation: generation, changed: changed[userId] ?? 0) }

    private func keep(_ image: UIImage, for profile: Profile, _ gen: Stamp) -> UIImage {
        if gen == stamp(profile.userId) {
            let cost = Int(image.size.width * image.scale * image.size.height * image.scale) * 4
            decoded.setObject(Entry(profile.avatar, image), forKey: profile.userId as NSNumber, cost: cost)
        }
        return image
    }

    private func file(_ profile: Profile) -> URL { dir.appending(path: "\(profile.userId)-\(profile.avatar).jpg") }

    private func save(_ data: Data, for profile: Profile, _ gen: Stamp) async {
        guard gen == stamp(profile.userId) else { return }
        let (dir, file, userId) = (dir, file(profile), profile.userId)
        await Self.removeFiles(of: userId, in: dir)
        await Task.detached(priority: .utility) {
            try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            try? data.write(to: file, options: .atomic)
        }.value
        // Wiped, removed or replaced while writing.
        if gen != stamp(userId) { await Self.removeFiles(of: userId, in: dir) }
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
