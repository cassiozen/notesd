import Foundation
import ImageIO
import NotesCore
import Synchronization
import UIKit

extension Notification.Name {
    /// A picture finished loading: editors redraw the attachments showing it.
    static let notesImageReady = Notification.Name("notesd.imageReady")
}

/// Pictures for the editor, decoded off the main thread at the size they're
/// drawn and kept in memory; the bytes come from the `ImageStore` (this
/// device's files, or the server the first time). A picture that couldn't be
/// had is tried again after 30 s. Locked notes' pictures are decrypted with
/// `decrypt`, when it can (the locked notes key is in memory).
///
/// Called from TextKit while it draws, so not tied to the main actor; NSCache
/// is thread-safe and the rest is behind a mutex.
nonisolated final class ImageLoader: @unchecked Sendable {
    private let store: ImageStore
    private let cache = NSCache<NSString, UIImage>()
    private let state = Mutex<(loading: Set<String>, failed: [String: Date])>(([], [:]))
    private let decrypt: @Sendable (Data) -> Data?

    nonisolated(unsafe) static var shared: ImageLoader?

    init(store: ImageStore, decrypt: @escaping @Sendable (Data) -> Data? = { _ in nil }) {
        self.store = store
        self.decrypt = decrypt
        cache.countLimit = 60
    }

    /// The picture `id` decoded no wider than `pixels`, or nil while it loads
    /// (then a `.notesImageReady` notification follows) or if it can't be had.
    func image(_ id: String, pixels: Int) -> UIImage? {
        let key = "\(id)@\(pixels)"
        if let image = cache.object(forKey: key as NSString) { return image }
        let start = state.withLock { state -> Bool in
            if state.loading.contains(key) { return false }
            if let at = state.failed[key], Date().timeIntervalSince(at) < 30 { return false }
            state.loading.insert(key)
            return true
        }
        guard start else { return nil }
        Task.detached(priority: .userInitiated) { [self] in
            let image = await self.decode(id, pixels: pixels)
            self.state.withLock { state in
                state.loading.remove(key)
                if image == nil { state.failed[key] = Date() } else { state.failed[key] = nil }
            }
            guard let image else { return }
            self.cache.setObject(image, forKey: key as NSString)
            await MainActor.run { NotificationCenter.default.post(name: .notesImageReady, object: id) }
        }
        return nil
    }

    /// Drops decrypted pictures, when locked notes lock again.
    func forgetLocked() {
        cache.removeAllObjects()
    }

    private func decode(_ id: String, pixels: Int) async -> UIImage? {
        guard let file = await store.load(id) else { return nil }
        // A locked image of a later version: no key here opens it, so it shows as missing.
        if isLockedImageType(file.type) && file.type != lockedImageType { return nil }
        let bytes = file.type == lockedImageType ? decrypt(file.bytes) : file.bytes
        guard let bytes, let source = CGImageSourceCreateWithData(bytes as CFData, nil) else { return nil }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: max(pixels, 1),
            kCGImageSourceShouldCacheImmediately: true,
        ]
        return CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary).map { UIImage(cgImage: $0) }
    }
}
