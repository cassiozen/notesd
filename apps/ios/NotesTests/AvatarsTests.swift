import Foundation
import NotesCore
import Testing
import UIKit

@testable import Notes

/// Profile photos kept on this phone (architecture §10).
@MainActor
struct AvatarsTests {
    let dir = FileManager.default.temporaryDirectory.appending(path: "avatars-\(UUID().uuidString)")

    private struct Signed: Credentials {
        func tokens() async -> Tokens? { Tokens(access: "a", accessExpiresAt: .max, refresh: "r") }
        func refreshed(_ tokens: Tokens, replacing old: Tokens) async {}
        func revoked(_ old: Tokens) async {}
    }

    /// Answers every request with `body`, once `open` lets it.
    private final class Stub: HTTPTransport, @unchecked Sendable {
        private let lock = NSLock()
        private var waiting: [CheckedContinuation<Void, Never>] = []
        private var held: Bool
        private var _requests = 0
        let body: Data

        init(_ body: Data, held: Bool = false) {
            self.body = body
            self.held = held
        }

        var requests: Int { lock.withLock { _requests } }

        func open() {
            let resume = lock.withLock {
                held = false
                defer { waiting = [] }
                return waiting
            }
            for c in resume { c.resume() }
        }

        func send(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
            await withCheckedContinuation { c in
                let wait = lock.withLock {
                    _requests += 1
                    if held { waiting.append(c) }
                    return held
                }
                if !wait { c.resume() }
            }
            return (body, HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!)
        }
    }

    private var jpeg: Data {
        UIGraphicsImageRenderer(size: CGSize(width: 8, height: 8)).jpegData(withCompressionQuality: 0.9) { ctx in
            UIColor.green.setFill()
            ctx.fill(CGRect(x: 0, y: 0, width: 8, height: 8))
        }
    }

    private func avatars(_ stub: Stub) -> Avatars {
        let client = ApiClient(baseURL: URL(string: "http://notesd.test")!, credentials: Signed(), transport: stub)
        return Avatars(dir: dir) { client }
    }

    private var files: [String] { ((try? FileManager.default.contentsOfDirectory(atPath: dir.path)) ?? []).sorted() }

    private func untilRequested(_ stub: Stub, _ n: Int) async {
        let deadline = Date.now.addingTimeInterval(5)
        while stub.requests < n, Date.now < deadline { await Task.yield() }
    }

    @Test func aDownloadedPhotoIsKeptAndAnythingElseIsNot() async {
        let avatars = avatars(Stub(jpeg))
        #expect(await avatars.load(Profile(userId: 1, displayName: "", avatar: "v1")) != nil)
        #expect(avatars.cached(Profile(userId: 1, displayName: "", avatar: "v1")) != nil)
        #expect(avatars.cached(Profile(userId: 1, displayName: "", avatar: "v2")) == nil)
        #expect(files == ["1-v1.jpg"])

        let portal = self.avatars(Stub(Data("<html>Wi-Fi</html>".utf8)))
        #expect(await portal.load(Profile(userId: 2, displayName: "", avatar: "v1")) == nil)
        #expect(files == ["1-v1.jpg"])
    }

    /// A load of `userId`'s "v1" whose download is held until after `meanwhile`.
    private func lateLoad(_ userId: Int64, meanwhile: (Avatars) async -> Void) async -> Avatars {
        let stub = Stub(jpeg, held: true)
        let avatars = avatars(stub)
        let late = Task { await avatars.load(Profile(userId: userId, displayName: "", avatar: "v1")) }
        await untilRequested(stub, 1)
        await meanwhile(avatars)
        stub.open()
        _ = await late.value
        return avatars
    }

    @Test func aDownloadThatLandsAfterANewerPhotoARemovalOrAWipeIsNotKept() async {
        let newer = Profile(userId: 1, displayName: "", avatar: "v2")
        var avatars = await lateLoad(1) { await $0.put(jpeg, for: newer) }
        #expect(avatars.cached(newer) != nil)
        #expect(files == ["1-v2.jpg"])

        avatars = await lateLoad(2) { await $0.forget(2) }
        #expect(avatars.cached(Profile(userId: 2, displayName: "", avatar: "v1")) == nil)
        #expect(files == ["1-v2.jpg"])

        avatars = await lateLoad(3) { $0.wipe() }
        #expect(avatars.cached(Profile(userId: 3, displayName: "", avatar: "v1")) == nil)
        #expect(!FileManager.default.fileExists(atPath: dir.path))
    }
}
