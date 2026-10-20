import Foundation

/// How a cycle ended; the scheduler retries `offline` and `failed` with backoff.
public enum SyncOutcome: Hashable, Sendable { case done, notConnected, offline, signedOut, failed }

public enum SyncStatus: Hashable, Sendable {
    case notConnected
    case syncing
    case synced(at: Int64)
    case offline
    case signedOut
    case failed(String)
}

/// One sync cycle's outcome and what it told the user.
public struct SyncReport: Sendable {
    public var outcome: SyncOutcome
    public var events: [SyncEvent]
}

/// One sync cycle: push the outbox, pull the feed, then push again whatever
/// the pull queued (conflicted and recovered copies). One cycle at a time;
/// calling `sync()` while one runs waits for it and runs another. Decisions use
/// the server's revisions and cursor only, never the device clock.
public final class SyncEngine: Sendable {
    public let store: SyncStore
    /// The locked-notes keyring as last synced, for unlocking offline (§10.1).
    public let lockKeys: LockKeyStore
    /// The spaces, with this device's changes waiting to go (§7.7).
    public let spaces: SpaceStore
    private let api: @Sendable () async -> ApiClient?
    private let images: ImageStore?
    private let pageSize: Int
    private let lock = AsyncLock()
    private let eventFeed = Broadcast<SyncEvent>(buffer: 16)

    public let status = StateCell<SyncStatus>(.notConnected)

    public init(
        db: Database, api: @escaping @Sendable () async -> ApiClient?, images: ImageStore? = nil,
        pageSize: Int = 100, clock: @escaping Clock = systemClock
    ) {
        store = SyncStore(db: db, clock: clock)
        lockKeys = LockKeyStore(db: db)
        spaces = SpaceStore(db: db, clock: clock)
        self.api = api
        self.images = images
        self.pageSize = pageSize
    }

    /// What sync did that the user should hear about, as it happens.
    public func events() -> AsyncStream<SyncEvent> { eventFeed.stream() }

    @discardableResult
    public func sync() async -> SyncReport {
        await lock.withLock {
            guard let client = await self.api() else {
                self.status.set(.notConnected)
                return SyncReport(outcome: .notConnected, events: [])
            }
            self.status.set(.syncing)
            var events: [SyncEvent] = []
            var rejection: String?
            var stop: Stop?
            do {
                stop = try await self.cycle(client, &events, &rejection)
            } catch {
                stop = Stop(outcome: .failed, message: "This device's notes couldn't be updated (\(error)).")
            }
            // A dropped operation isn't retried, so the cycle still counts as done, but the user must hear why,
            // beside the failure's own when the cycle failed too: the next cycle won't say it again.
            let reasons = [rejection, stop?.message].compactMap { $0 }
            try? await self.store.finished(error: reasons.isEmpty ? nil : reasons.joined(separator: " "))
            let outcome = stop?.outcome ?? .done
            switch outcome {
            case .done: self.status.set(.synced(at: (try? await self.store.state().lastSyncAt) ?? 0))
            case .offline: self.status.set(.offline)
            case .signedOut: self.status.set(.signedOut)
            case .notConnected: self.status.set(.notConnected)
            case .failed: self.status.set(.failed(stop?.message ?? "Sync failed"))
            }
            return SyncReport(outcome: outcome, events: events)
        }
    }

    /// Runs `body` with no sync cycle in flight, and none starting until it's done.
    public func exclusive<T: Sendable>(_ body: @Sendable () async throws -> T) async rethrows -> T {
        try await lock.withLock(body)
    }

    private struct Stop {
        var outcome: SyncOutcome
        var message: String?
    }

    /// Spaces made or renamed here go before the notes, so the server knows the space a note
    /// names. Deleted ones are tried first, to free their names, and again after the notes
    /// moved out of them have gone (§7.7).
    private func cycle(_ api: ApiClient, _ events: inout [SyncEvent], _ rejection: inout String?) async throws -> Stop?
    {
        // Spaces sent under a name a space deleted here still holds on the server: stored there
        // with a number for now, and sent again, to take the name, once the delete has gone.
        var parked = Set<String>()
        if let stop = try await pushSpaces(api, deletes: true, final: false, &rejection, &parked) { return stop }
        if let stop = try await pushSpaces(api, deletes: false, &rejection, &parked) { return stop }
        if let stop = try await push(api, &events, &rejection, &parked) { return stop }
        if let stop = try await pushSpaces(api, deletes: true, &rejection, &parked) { return stop }
        // The names those deletes freed, for the spaces made here meanwhile.
        if !parked.isEmpty {
            parked.removeAll()
            if let stop = try await pushSpaces(api, deletes: false, &rejection, &parked) { return stop }
        }
        if let stop = try await pull(api, &events) { return stop }
        if let stop = try await fetchSpaces(api) { return stop }
        if let stop = try await keyring(api) { return stop }
        return try await store.nextOperation() != nil ? try await push(api, &events, &rejection, &parked) : nil
    }

    /// Sends the spaces created or renamed here, or (`deletes`) the ones deleted here. Not
    /// `final`, a delete refused for holding notes stays pending: notes moved out of it here
    /// may not have gone yet. One refused for good is dropped, and `rejection` says why.
    private func pushSpaces(
        _ api: ApiClient, deletes: Bool, final: Bool = true, _ rejection: inout String?, _ parked: inout Set<String>
    ) async throws -> Stop? {
        let pending = try await spaces.pending()
        for change in pending where change.delete == deletes {
            if change.delete {
                switch await api.deleteSpace(change.id) {
                case .ok: try await spaces.deleted(change)
                // Notes reached it from another device (or it's the default): it stays.
                case .conflict:
                    guard final else { continue }
                    try await spaces.dropped(change)
                    rejection = "“\(change.name)” wasn’t deleted: notes reached it from another device."
                case .rejected(let status, let error, let message, _) where status < 500:
                    try await spaces.dropped(change)
                    rejection = "“\(change.name)” wasn’t deleted: \(message.isEmpty ? error : message)"
                case let other: return stop(other)
                }
            } else {
                // Deleted here, its name not yet free on the server (notes moved out of it go later):
                // sent now so notes can name it, but kept waiting to take the name after the delete.
                let held = pending.contains { $0.delete && $0.name.lowercased() == change.name.lowercased() }
                if held && parked.contains(change.id) { continue }
                switch await api.putSpace(change.id, name: change.name, icon: change.icon) {
                case .ok(let stored):
                    if held { parked.insert(change.id) } else { try await spaces.sent(change, stored: stored) }
                case .rejected(let status, let error, let message, _) where status < 500:
                    try await spaces.dropped(change)
                    rejection = "The space “\(change.name)” wasn’t saved: \(message.isEmpty ? error : message)"
                case let other: return stop(other)
                }
            }
        }
        return nil
    }

    /// The list is small and has no change feed: fetched whole every cycle, like the keyring.
    private func fetchSpaces(_ api: ApiClient) async throws -> Stop? {
        switch await api.spaces() {
        case .ok(let list):
            try await spaces.replace(list)
            return nil
        case let other:
            return stop(other)
        }
    }

    /// The keyring is tiny and has no change feed: fetched whole every cycle.
    /// A change made on this device while the fetch was out is newer; the
    /// fetched copy is dropped, and the next cycle brings the server's.
    private func keyring(_ api: ApiClient) async throws -> Stop? {
        let since = lockKeys.generation()
        switch await api.lockKeys() {
        case .ok(let keys):
            _ = try await lockKeys.replaceIfUnchanged(keys, generation: since)
            return nil
        case let other:
            return stop(other)
        }
    }

    /// Uploads every image waiting on this device; a Stop if one couldn't go.
    private func uploadImages(_ api: ApiClient) async throws -> Stop? {
        guard let images else { return nil }
        switch try await images.upload(api) {
        case .ok: return nil
        case .rejected(let status, _, _, _):
            return Stop(outcome: .failed, message: "The server had a problem (\(status)).")
        case let other: return stop(other)
        }
    }

    private func push(
        _ api: ApiClient, _ events: inout [SyncEvent], _ rejection: inout String?, _ parked: inout Set<String>
    ) async throws -> Stop? {
        // Every waiting image goes, whether or not a note operation follows.
        if let stop = try await uploadImages(api) { return stop }
        while true {
            guard let next = try await store.nextOperation() else { return nil }
            guard let op = try await store.prepare(next) else { continue }
            // For each operation, once it's in hand: an image's pending row is written
            // before the note showing it is saved, so this sends every image the
            // operation can show, including ones added while an earlier one was in flight.
            if let stop = try await uploadImages(api) { return stop }
            // Likewise a space made since the cycle began: the server files a note naming an
            // unknown space in the default.
            if let stop = try await pushSpaces(api, deletes: false, &rejection, &parked) { return stop }
            switch await send(api, op) {
            case .ok(let result):
                try await store.sent(op, result)
            case .conflict(_, let message, let current):
                if let current {
                    // Unreadable: keep the operation for a later cycle rather than drop the user's change.
                    guard let note = try? JSONDecoder().decode(RemoteNote.self, from: current) else {
                        return Stop(outcome: .failed, message: "The server's answer wasn't understood.")
                    }
                    emit(try await store.conflict(op, current: note), into: &events)
                } else {
                    try await store.rejected(op, message: message)
                    rejection = message
                }
            case .rejected(let status, let error, let message, _):
                if status >= 500 { return Stop(outcome: .failed, message: "The server had a problem (\(status)).") }
                let reason = message.isEmpty ? error : message
                try await store.rejected(op, message: reason)
                rejection = reason
            case let other:
                return stop(other)
            }
        }
    }

    private func send(_ api: ApiClient, _ op: Operation) async -> ApiResult<MutationResult> {
        let p = op.payload
        switch op.type {
        case createOp:
            return await api.createNote(
                NoteRequest(
                    operationId: op.operationId, id: op.noteId, title: p.title, content: p.content,
                    createdAt: p.createdAt, updatedAt: p.updatedAt, conflictOf: p.conflictOf, spaceId: p.spaceId,
                    imageIds: p.imageIds))
        case updateOp:
            return await api.updateNote(
                op.noteId,
                NoteRequest(
                    operationId: op.operationId, baseRevision: op.baseRevision, title: p.title, content: p.content,
                    updatedAt: p.updatedAt, spaceId: p.spaceId, imageIds: p.imageIds))
        default:
            return await api.deleteNote(
                op.noteId, NoteRequest(operationId: op.operationId, baseRevision: op.baseRevision))
        }
    }

    private func pull(_ api: ApiClient, _ events: inout [SyncEvent]) async throws -> Stop? {
        var restarted = false
        while true {
            let state = try await store.state()
            switch await api.sync(since: state.cursor, limit: pageSize, generation: state.generation) {
            case .ok(let page):
                emit(try await store.applyPage(page), into: &events)
                if !page.hasMore { return nil }
            case .conflict(let code, let message, _) where code == "generation_mismatch" || code == "cursor_ahead":
                // Restored from a backup. Once per cycle: a second time means something else is wrong.
                if restarted { return Stop(outcome: .failed, message: message) }
                restarted = true
                try await store.startFullSync()
            case .rejected(let status, let error, let message, _) where status < 500:
                return Stop(outcome: .failed, message: message.isEmpty ? error : message)
            case let other:
                return stop(other)
            }
        }
    }

    private func stop<T>(_ result: ApiResult<T>) -> Stop {
        switch result {
        case .unauthenticated: Stop(outcome: .signedOut)
        case .unreachable, .rateLimited: Stop(outcome: .offline)
        case .rejected(let status, _, _, _): Stop(outcome: .failed, message: "The server had a problem (\(status)).")
        case .conflict(_, let message, _): Stop(outcome: .failed, message: message)
        case .ok: preconditionFailure("not a failure")
        }
    }

    private func emit(_ new: [SyncEvent], into events: inout [SyncEvent]) {
        for event in new {
            events.append(event)
            eventFeed.send(event)
        }
    }
}
