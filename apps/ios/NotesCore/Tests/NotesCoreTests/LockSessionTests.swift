import Foundation
import Synchronization
import Testing

@testable import NotesCore

struct LockSessionTests {
    let dir = FileManager.default.temporaryDirectory.appending(path: "notes-locks-\(UUID().uuidString)")
    let keys: LockKeyStore
    let session: LockSession

    init() throws {
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let db = Database(path: dir.appending(path: "notes.db").path)
        keys = LockKeyStore(db: db)
        session = LockSession(keys: keys, api: { nil }, iterations: 10_000)
    }

    @Test func aNewPasswordOrAResetSyncedFromElsewhereLocksAnUnlockedSession() async throws {
        let created = Keyring.create("lock pass 123", iterations: 10_000)
        try await keys.replace([created.entry])
        await session.refresh()
        #expect(session.state.value == .locked)
        #expect(!(await session.unlock("wrong")))
        #expect(await session.unlock("lock pass 123"))
        #expect(session.key() == created.key)

        // Nothing changed: still unlocked.
        await session.refresh()
        #expect(session.state.value == .unlocked)

        try await keys.replace([Keyring.create("another one", iterations: 10_000).entry])
        await session.refresh()
        #expect(session.state.value == .locked)
        #expect(session.key() == nil)

        #expect(await session.unlock("another one"))
        try await keys.replace([])
        await session.refresh()
        #expect(session.state.value == .none)
        #expect(session.key() == nil)
    }

    @Test func aSaveThatFailsKeepsTheKeyUnlessTheKeyringMovedOn() async throws {
        let created = Keyring.create("lock pass 123", iterations: 10_000)
        try await keys.replace([created.entry])
        await session.refresh()
        #expect(await session.unlock("lock pass 123"))
        let saves = Counter()
        let unhook = session.onBeforeLock {
            saves.increment()
            return false
        }

        #expect(!(await session.lock()))
        #expect(session.state.value == .unlocked)
        #expect(session.key() != nil)

        try await keys.replace([Keyring.create("another one", iterations: 10_000).entry])
        await session.refresh()
        #expect(session.state.value == .locked)
        #expect(saves.count == 2)
        unhook()
    }

    @Test(.timeLimit(.minutes(1))) func idleLocks() async throws {
        let short = LockSession(keys: keys, api: { nil }, iterations: 10_000, idle: .milliseconds(100))
        try await keys.replace([Keyring.create("lock pass 123", iterations: 10_000).entry])
        #expect(await short.unlock("lock pass 123"))
        // Not a fixed sleep: with every suite deriving keys at once, the idle task can run late.
        for await state in short.state.stream() where state == .locked { break }
        #expect(short.key() == nil)
    }
}
