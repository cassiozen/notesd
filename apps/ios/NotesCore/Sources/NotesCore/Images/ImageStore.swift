import Foundation

/// An image added on this device that the server doesn't have yet.
public struct PendingImage: Hashable, Sendable {
    public var id: String
    public var type: String
}

/// This device's images (architecture §7.6). One added here is written to
/// `pendingDir` with a `pending_images` row and stays there until sync
/// uploads it; then it moves to `cacheDir`, where images downloaded on first
/// view live too. The system may clear the cache: a cached image is fetched
/// again, a pending one is never lost. Locked notes' images stay encrypted on
/// disk; they are decrypted in memory to be shown.
public final class ImageStore: Sendable {
    private let db: Database
    private let pendingDir: URL
    private let cacheDir: URL
    private let api: @Sendable () async -> ApiClient?
    private let clock: Clock

    public init(
        db: Database, pendingDir: URL, cacheDir: URL, api: @escaping @Sendable () async -> ApiClient?,
        clock: @escaping Clock = systemClock
    ) {
        self.db = db
        self.pendingDir = pendingDir
        self.cacheDir = cacheDir
        self.api = api
        self.clock = clock
    }

    /// Stores a new image's bytes (already prepared, and encrypted for a locked note); returns its id.
    public func add(_ bytes: Data, type: String) async throws -> String {
        let id = ImageRef.sha256(bytes)
        try Self.write(bytes, to: pendingDir.appending(path: try Self.fileName(id, type)))
        // The file is durable before the row says it exists.
        let now = clock()
        try await db.write { sql in
            try sql.exec(
                "INSERT OR IGNORE INTO pending_images (id, content_type, created_at) VALUES (?, ?, ?)", id, type, now)
        }
        return id
    }

    /// An image from this device, or from the server the first time it's needed; nil if neither has it.
    public func load(_ id: String) async -> ImageFile? {
        if let file = local(id) { return file }
        guard let client = await api(), let file = await client.getImage(id).value else { return nil }
        // The id is the hash: anything else isn't this image.
        guard ImageRef.sha256(file.bytes) == id, let name = try? Self.fileName(id, file.type) else { return nil }
        try? Self.write(file.bytes, to: cacheDir.appending(path: name))
        return file
    }

    /// Whether the image is on this device, so it can be shown (or re-encrypted) offline.
    public func has(_ id: String) -> Bool { local(id) != nil }

    private func local(_ id: String) -> ImageFile? {
        for dir in [pendingDir, cacheDir] {
            for (ext, type) in Self.types {
                if let bytes = try? Data(contentsOf: dir.appending(path: "\(id).\(ext)")) {
                    return ImageFile(type: type, bytes: bytes)
                }
            }
        }
        return nil
    }

    /// The note body `markdown` with each of its images stored again encrypted
    /// (`locked`) or plain, as a lock change needs (§10.1): new images are
    /// added as pending, and the references rewritten. Nil, changing nothing,
    /// when an image is neither on this device nor downloadable (offline), or
    /// won't open with `key`: a locked note must not point at plain images.
    public func reseal(_ markdown: String, locked: Bool, key: Data) async -> String? {
        guard let moved = await move(Set(ImageRef.ids(markdown)), locked: locked, key: key) else { return nil }
        if moved.isEmpty { return markdown }
        return markdown.replacing(ImageRef.reference) { m in
            let old = String(m.1)
            guard let next = moved[old] else { return String(m.0) }
            return String(m.0).replacing(old, with: next)
        }
    }

    /// `reseal` for images by id: each one not already stored the way `locked`
    /// asks is added again, encrypted or decrypted. Returns old id → new id
    /// for those, or nil (nothing added) if any can't be had or opened.
    public func move(_ ids: Set<String>, locked: Bool, key: Data) async -> [String: String]? {
        var next: [(id: String, bytes: Data, type: String)] = []
        for id in ids {
            // A locked image of a version this app can't open stays as it is: the lock can't change.
            guard let file = await load(id), file.type == lockedImageType || !isLockedImageType(file.type) else {
                return nil
            }
            if (file.type == lockedImageType) == locked { continue }
            if locked {
                next.append((id, LockedNotes.encryptImage(key, file.bytes), lockedImageType))
            } else {
                guard let plain = try? LockedNotes.decryptImage(key, file.bytes), let type = Self.sniff(plain) else {
                    return nil
                }
                next.append((id, plain, type))
            }
        }
        // Only once every image is in hand: a refusal leaves nothing half-done.
        var moved: [String: String] = [:]
        for item in next {
            guard let id = try? await add(item.bytes, type: item.type) else { return nil }
            moved[item.id] = id
        }
        return moved
    }

    /// Removes the images with ids `ids` from this device, pending upload or
    /// cached, unless a note still uses them: after a lock change they were
    /// stored again, and a plain copy of a locked note's picture mustn't stay
    /// behind, nor upload.
    public func purge(_ ids: Set<String>) async {
        var gone: [String] = []
        for id in ids {
            let removed = try? await db.write { sql -> Bool in
                // A note's text, a locked body's list, or an edit not sent yet.
                let used = try sql.int64(
                    """
                    SELECT EXISTS(SELECT 1 FROM notes WHERE instr(content, ?1) > 0)
                        OR EXISTS(SELECT 1 FROM locked_images WHERE instr(image_ids, ?1) > 0)
                        OR EXISTS(SELECT 1 FROM sync_outbox WHERE sent = 0 AND instr(payload_json, ?1) > 0)
                    """,
                    id)
                if used != 0 { return false }
                try sql.exec("DELETE FROM pending_images WHERE id = ?", id)
                return true
            }
            if removed == true { gone.append(id) }
        }
        let fm = FileManager.default
        for id in gone {
            for dir in [pendingDir, cacheDir] {
                for (ext, _) in Self.types { try? fm.removeItem(at: dir.appending(path: "\(id).\(ext)")) }
            }
        }
    }

    public func pending() async throws -> [PendingImage] {
        try await db.read { sql in
            try sql.query("SELECT id, content_type FROM pending_images ORDER BY created_at, id") {
                PendingImage(id: $0.text(0), type: $0.text(1))
            }
        }
    }

    /// Uploads every pending image that isn't parked, oldest first. Called by sync before any
    /// note operation goes out, so no device sees a reference before its bytes
    /// exist. Stops at the first failure that a retry might fix; an answer
    /// notesd wouldn't give counts against the image, and the third parks it
    /// (the notes showing it wait with it).
    public func upload(_ client: ApiClient) async throws -> ApiResult<Void> {
        let waiting = try await db.read { sql in
            try sql.query("SELECT id, content_type FROM pending_images WHERE parked = 0 ORDER BY created_at, id") {
                PendingImage(id: $0.text(0), type: $0.text(1))
            }
        }
        for image in waiting {
            // A type that can't be stored can't be uploaded either: nothing to retry.
            guard let name = try? Self.fileName(image.id, image.type) else {
                try await forget(image.id)
                continue
            }
            let file = pendingDir.appending(path: name)
            let bytes: Data
            do {
                bytes = try Data(contentsOf: file)
            } catch {
                // Gone for good: forgotten, so it doesn't hold every note back. Only unreadable for now
                // (the phone locked during a background sync): kept, and the cycle stops to try again.
                if !FileManager.default.fileExists(atPath: file.path) {
                    try await forget(image.id)
                    continue
                }
                throw error
            }
            switch await client.putImage(image.id, bytes: bytes, type: image.type) {
            case .ok:
                try await uploaded(image, name)
            // Refused for good (bytes not matching the id, too large, not an
            // image): retrying won't change that, and holding it back would
            // stop every note from syncing. Its notes show it as missing.
            case .rejected(_, let error, _, _) where permanentErrors.contains(error):
                try await uploaded(image, name)
            case .unexpected(let status),
                .rejected(let status, _, _, _) where status < 500:
                try await attempted(image)
                return .unexpected(status: status)
            case let other:
                return other
            }
        }
        return .ok(())
    }

    private func uploaded(_ image: PendingImage, _ name: String) async throws {
        let fm = FileManager.default
        try? fm.createDirectory(at: cacheDir, withIntermediateDirectories: true)
        let to = cacheDir.appending(path: name)
        try? fm.removeItem(at: to)
        try? fm.moveItem(at: pendingDir.appending(path: name), to: to)
        try await forget(image.id)
    }

    private func attempted(_ image: PendingImage) async throws {
        try await db.write { sql in
            try sql.exec(
                "UPDATE pending_images SET attempts = attempts + 1, parked = attempts + 1 >= ? WHERE id = ?",
                maxAttempts, image.id)
        }
    }

    private func forget(_ id: String) async throws {
        try await db.write { try $0.exec("DELETE FROM pending_images WHERE id = ?", id) }
    }

    /// Removes every image from this device (signing out, removing all data).
    public func wipe() async throws {
        try await db.write { try $0.exec("DELETE FROM pending_images") }
        try? FileManager.default.removeItem(at: pendingDir)
        try? FileManager.default.removeItem(at: cacheDir)
    }

    /// File extension by type: the type is kept in the name, so a cached file needs no row.
    static let types: [(String, String)] = [
        ("jpg", "image/jpeg"), ("png", "image/png"), ("webp", "image/webp"), ("gif", "image/gif"),
        ("locked", lockedImageType),
    ]

    private static func fileName(_ id: String, _ type: String) throws -> String {
        guard let ext = types.first(where: { $0.1 == type })?.0 else {
            throw DatabaseError(code: 0, message: "Not an image type: \(type)")
        }
        return "\(id).\(ext)"
    }

    private static func write(_ bytes: Data, to target: URL) throws {
        try FileManager.default.createDirectory(
            at: target.deletingLastPathComponent(), withIntermediateDirectories: true)
        // Written to a temporary file and renamed, so a crash never leaves half an image.
        try bytes.write(to: target, options: .atomic)
    }

    /// The image type of decrypted bytes, from their magic numbers.
    public static func sniff(_ b: Data) -> String? {
        let b = [UInt8](b.prefix(12))
        func ascii(_ from: Int, _ to: Int) -> String {
            b.count >= to ? String(decoding: b[from..<to], as: UTF8.self) : ""
        }
        if b.count >= 3 && b[0] == 0xff && b[1] == 0xd8 && b[2] == 0xff { return "image/jpeg" }
        if b.count >= 4 && b[0] == 0x89 && ascii(1, 4) == "PNG" { return "image/png" }
        if ascii(0, 4) == "GIF8" { return "image/gif" }
        if ascii(0, 4) == "RIFF" && ascii(8, 12) == "WEBP" { return "image/webp" }
        return nil
    }
}
