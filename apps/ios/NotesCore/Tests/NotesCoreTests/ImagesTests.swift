import Foundation
import Synchronization
import Testing

@testable import NotesCore

/// Images (§7.6) against a real notesd: uploaded before their notes, fetched on first view.
@Suite(.serialized, .timeLimit(.minutes(2)))
final class ImagesTests {
    let server: TestServer
    private var clients: [TestClient] = []

    init() async throws {
        server = try await TestServer.start()
    }

    private func client() async throws -> TestClient {
        let c = TestClient(server)
        clients.append(c)
        return try await c.signIn()
    }

    static let png = Data(
        base64Encoded:
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGP4z8DwHwAFAAH/iZk9HQAAAABJRU5ErkJggg==")!
    private var png: Data { Self.png }

    private static func fetched(_ c: TestClient, _ id: String) async -> Bool {
        if case .ok = await c.api.getImage(id) { return true }
        return false
    }

    @Test func referencesAreParsedAndWrittenAsOnTheWeb() {
        let id = String(repeating: "ab", count: 32)
        let ref = ImageRef(id: id, width: 1600, height: 1200)
        #expect(ref.markdown() == "![](notesd:image/\(id)?w=1600&h=1200)")
        #expect(ref.markdown(alt: "a *cat* [1]") == "![a \\*cat\\* \\[1\\]](notesd:image/\(id)?w=1600&h=1200)")
        #expect(ImageRef.parse(ref.markdown(alt: "a *cat*")) == ref)
        #expect(ImageRef.alt(ref.markdown(alt: "a *cat*")) == "a *cat*")
        #expect(ImageRef.ids("x ![](notesd:image/\(id)?w=1&h=1) ![b](notesd:image/\(id)?w=2&h=2)") == [id])
        for other in [
            "![a](cat.png)",
            "![a](notesd:image/\(id))",
            "![a](notesd:image/\(id.uppercased())?w=1&h=1)",
            "![a](notesd:image/\(id)?w=0&h=1)",
            "`![a](notesd:image/\(id)?w=1&h=1)`",
        ] {
            #expect(ImageRef.parse(other) == nil, "\(other)")
        }
    }

    @Test func anImageAddedOfflineUploadsBeforeItsNoteAndOpensOnAnotherDevice() async throws {
        let a = try await client()
        let b = try await client()
        a.offline = true
        let id = try await a.images.add(png, type: "image/png")
        try await a.save("n", "Trip", ImageRef(id: id, width: 1, height: 1).markdown() + "\n")
        #expect(await a.sync() == .offline)
        #expect(try await a.images.pending().count == 1)
        // Still on this device while it waits.
        #expect(await a.images.load(id)?.bytes == png)

        a.offline = false
        let uploadedFirst = Counter()
        a.beforeNext(
            { $0.path.hasPrefix("/api/v1/notes") },
            then: {
                if await Self.fetched(b, id) { uploadedFirst.increment() }
            })
        #expect(await a.sync() == .done)
        #expect(uploadedFirst.count == 1, "the image reached the server before the note")
        #expect(try await a.images.pending().isEmpty)
        #expect(a.images.has(id), "kept in the cache after upload")

        #expect(await b.sync() == .done)
        #expect(!b.images.has(id))
        let file = try #require(await b.images.load(id))
        #expect(file.type == "image/png")
        #expect(file.bytes == png)
        b.offline = true
        #expect(await b.images.load(id)?.bytes == png, "cached for offline")
    }

    @Test func anImageAddedWhileANoteGoesUpStillReachesTheServerBeforeItsOwnNote() async throws {
        let a = try await client()
        let b = try await client()
        try await a.save("first", "First", "text\n")
        let added = Mutex<String?>(nil)
        // While the first note's request is out, a picture is added to another note.
        a.beforeNext(
            { $0.path == "/api/v1/notes" },
            then: {
                let id = try! await a.images.add(Self.png, type: "image/png")
                _ = try! await a.save("second", "Second", ImageRef(id: id, width: 1, height: 1).markdown() + "\n")
                added.withLock { $0 = id }
            })
        let uploadedFirst = Counter()
        a.beforeNext(
            { $0.path == "/api/v1/notes" && added.withLock { $0 } != nil },
            then: {
                if await Self.fetched(b, added.withLock { $0! }) { uploadedFirst.increment() }
            })
        #expect(await a.sync() == .done)
        #expect(uploadedFirst.count == 1, "the image reached the server before the note showing it")
    }

    @Test func aLostUploadResponseIsRetriedHarmlessly() async throws {
        let a = try await client()
        let id = try await a.images.add(png, type: "image/png")
        a.loseNextResponse { $0.path.hasPrefix("/api/v1/images") }
        #expect(await a.sync() == .offline)
        #expect(await a.sync() == .done)
        #expect(try await a.images.pending().isEmpty)
        #expect(await try client().images.load(id) != nil)
    }

    @Test func pendingImagesSurviveARestart() async throws {
        let a = try await client()
        a.offline = true
        let id = try await a.images.add(png, type: "image/png")
        await a.restart()
        #expect(try await a.images.pending() == [PendingImage(id: id, type: "image/png")])
        a.offline = false
        #expect(await a.sync() == .done)
        #expect(await try client().images.load(id) != nil)
    }

    @Test func anUploadAnsweredByAPortalHoldsBackOnlyTheNotesShowingIt() async throws {
        let a = try await client()
        let id = try await a.images.add(png, type: "image/png")
        try await a.save("pic", "Trip", ImageRef(id: id, width: 1, height: 1).markdown() + "\n")
        try await a.save("plain", "Other", "words\n")
        for _ in 1...3 {
            a.answerNext({ $0.path.hasPrefix("/api/v1/images") }, status: 413, body: "<html>too big</html>")
            #expect(await a.sync() == .failed)
        }
        #expect(try await a.note("plain")?.dirty == true, "nothing goes while the image is tried")
        // Parked: the note showing it waits too, the other goes, and that gives both another try.
        #expect(a.engine.parked.value == 1)
        #expect(await a.sync() == .done)
        #expect(try await a.note("plain")?.dirty == false)
        #expect(try await a.note("pic")?.dirty == true, "held with its image")
        #expect(await a.sync() == .done)
        #expect(try await a.note("pic")?.dirty == false)
        #expect(await Self.fetched(a, id))
    }

    @Test func anImageRefusedByNotesdIsDroppedNotRetried() async throws {
        let a = try await client()
        try await a.images.add(png, type: "image/png")
        a.answerNext({ $0.path.hasPrefix("/api/v1/images") }, status: 413, body: #"{"error":"image_too_large"}"#)
        #expect(await a.sync() == .done)
        #expect(try await a.images.pending().isEmpty)
    }

    @Test func aLockedImageOfALaterVersionIsNeitherShownNorResealed() async throws {
        let a = try await client()
        let sealed = Data((0..<40).map { UInt8($0) })
        let id = ImageRef.sha256(sealed)
        guard case .ok = await a.api.putImage(id, bytes: sealed, type: lockedImageType + ".v2") else {
            Issue.record(Comment(rawValue: "the server takes later locked versions"))
            return
        }
        #expect(await a.images.load(id) == nil)
        let key = LockedNotes.newKey()
        #expect(await a.images.move([id], locked: false, key: key) == nil)
        #expect(await a.images.move([id], locked: true, key: key) == nil)
        #expect(try await a.images.pending().isEmpty)
    }

    @Test func sniffingKnowsTheImageTypesTheServerAccepts() {
        #expect(ImageStore.sniff(png) == "image/png")
        #expect(ImageStore.sniff(Data([0xff, 0xd8, 0xff, 0])) == "image/jpeg")
        #expect(ImageStore.sniff(Data("GIF89a".utf8)) == "image/gif")
        #expect(ImageStore.sniff(Data("RIFF\0\0\0\0WEBPVP8 ".utf8)) == "image/webp")
        #expect(ImageStore.sniff(Data("<svg".utf8)) == nil)
    }

    /// The image ids the last `request` ("POST /api/v1/notes", ...) listed; nil if it listed none.
    private func listed(_ c: TestClient, _ request: String) throws -> [String]? {
        let body = try #require(c.transport.sentNotes.last { $0.0 == request }?.1)
        let object = try JSONSerialization.jsonObject(with: Data(body.utf8)) as? [String: Any]
        return object?["imageIds"] as? [String]
    }

    @Test func aLockedNotesSavesListItsImagesAndSoDoesAConflictedCopySyncMakes() async throws {
        let a = try await client()
        let b = try await client()
        let key = Data((0..<32).map { UInt8($0) })
        let (x, y, z) = ("a", "b", "c")
        let ids = [x, y, z].map { String(repeating: $0, count: 64) }
        func sealed(_ id: String) -> String {
            LockedNotes.encrypt(key, ImageRef(id: id, width: 1, height: 1).markdown() + "\n")
        }

        try await a.save("p", "Plain", ImageRef(id: ids[0], width: 1, height: 1).markdown() + "\n")
        _ = try await a.notes.save("n", title: "Secret", content: sealed(ids[0]), imageIds: [ids[0]])
        #expect(await a.sync() == .done)
        #expect(try listed(a, "POST /api/v1/notes") == [ids[0]])
        // A plain note's images the server reads from its content.
        let plain = try #require(a.transport.sentNotes.first { $0.1.contains("\"Plain\"") }?.1)
        #expect(!plain.contains("imageIds"))
        #expect(await b.sync() == .done)

        // Both change the body: b's becomes a copy, which sync uploads without being able to read it.
        _ = try await a.notes.save("n", title: "Secret", content: sealed(ids[1]), imageIds: [ids[1]])
        _ = try await b.notes.save("n", title: "Secret", content: sealed(ids[2]), imageIds: [ids[2]])
        #expect(await a.sync() == .done)
        #expect(try listed(a, "PUT /api/v1/notes/n") == [ids[1]])
        #expect(await b.sync() == .done)
        #expect(try await b.notes.list().contains { $0.title == "Secret (Conflicted copy)" })
        #expect(try listed(b, "POST /api/v1/notes") == [ids[2]], "the copy lists its images")
    }

    @Test func resealingEncryptsEveryImageForALockAndDecryptsThemForItsRemoval() async throws {
        let a = try await client()
        let key = LockedNotes.newKey()
        let plain = try await a.images.add(png, type: "image/png")
        let ref = ImageRef(id: plain, width: 1, height: 1)
        let body = "Before\n\n\(ref.markdown(alt: "dot"))\n\n\(ref.markdown())\n"

        let locked = try #require(await a.images.reseal(body, locked: true, key: key))
        let sealedId = try #require(ImageRef.ids(locked).first)
        #expect(sealedId != plain)
        #expect(locked == body.replacing(plain, with: sealedId))
        let sealed = try #require(await a.images.load(sealedId))
        #expect(sealed.type == lockedImageType)
        #expect(try LockedNotes.decryptImage(key, sealed.bytes) == png)
        // Already encrypted: nothing to do.
        #expect(await a.images.reseal(locked, locked: true, key: key) == locked)

        #expect(await a.sync() == .done)
        #expect(await try client().images.load(sealedId)?.type == lockedImageType)

        // Removing the lock brings back the same plain bytes, so the same id.
        #expect(await a.images.reseal(locked, locked: false, key: key) == body)
        // With another key the image won't open, and nothing changes.
        #expect(await a.images.reseal(locked, locked: false, key: LockedNotes.newKey()) == nil)
    }

    @Test func aPlainCopyALockReplacedIsRemovedUnlessAnotherNoteShowsIt() async throws {
        let a = try await client()
        a.offline = true
        let key = LockedNotes.newKey()
        let plain = try await a.images.add(png, type: "image/png")
        let body = ImageRef(id: plain, width: 1, height: 1).markdown() + "\n"
        _ = try await a.save("n", "Trip", body)
        _ = try await a.save("m", "Also", body)
        let locked = try #require(await a.images.reseal(body, locked: true, key: key))
        _ = try await a.save("n", "Trip", LockedNotes.encrypt(key, locked))

        // Note m still shows it.
        await a.images.purge([plain])
        #expect(a.images.has(plain))
        // Its unsent create now holds the new text too.
        _ = try await a.save("m", "Also", "Nothing here\n")
        await a.images.purge([plain])
        #expect(!a.images.has(plain))
        // Never uploaded: the plain bytes don't go now either.
        #expect(try await a.images.pending().map(\.id) == [ImageRef.ids(locked).first])
    }

    @Test func aLockChangeWaitsForImagesThisDeviceDoesntHave() async throws {
        let a = try await client()
        let b = try await client()
        let id = try await a.images.add(png, type: "image/png")
        #expect(await a.sync() == .done)
        b.offline = true
        let body = ImageRef(id: id, width: 1, height: 1).markdown() + "\n"
        #expect(await b.images.reseal(body, locked: true, key: LockedNotes.newKey()) == nil)
        #expect(try await b.images.pending().isEmpty)
        b.offline = false
        #expect(await b.images.reseal(body, locked: true, key: LockedNotes.newKey()) != nil)
    }
}
