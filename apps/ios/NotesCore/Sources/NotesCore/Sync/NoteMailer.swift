import Foundation

public enum EmailOutcome: Hashable, Sendable {
    case sent
    /// The server couldn't be reached, so it doesn't have this note's latest text.
    case offline
    /// Reached the server, but this note's changes didn't go up (signed out, a conflict to resolve).
    case notSynced
    /// The server or Resend refused; `message` says why, for the person sending.
    case failed(String, field: String? = nil)
}

/// Emails a note through notesd. The server sends its own copy, so this syncs
/// first and passes the revision the note is at here: the email is exactly
/// what's on screen. A note changed elsewhere in between is synced and sent
/// again, once.
public final class NoteMailer: Sendable {
    private let store: NoteStore
    private let engine: SyncEngine
    private let api: @Sendable () async -> ApiClient?

    public init(store: NoteStore, engine: SyncEngine, api: @escaping @Sendable () async -> ApiClient?) {
        self.store = store
        self.engine = engine
        self.api = api
    }

    public func send(_ id: String, to: [String]) async -> EmailOutcome {
        for _ in 0..<2 {
            switch await engine.sync().outcome {
            case .offline: return .offline
            case .done, .failed: break
            case .notConnected, .signedOut: return .notSynced
            case .upgradeRequired: return .failed(SyncMessages.upgradeRequired)
            }
            guard let note = try? await store.get(id), note.deletedAt == nil else {
                return .failed("This note was deleted.")
            }
            guard !note.dirty, let revision = note.serverRevision else { return .notSynced }
            guard let client = await api() else { return .notSynced }
            // A shared space's file has its notes (§7.9); not knowing which file, asking the account's would answer
            // that the note doesn't exist.
            guard let feeds = try? await engine.store.feeds() else { return .notSynced }
            let shared = feeds.contains(note.spaceId)
            switch await client.emailNote(id, to: to, revision: revision, spaceId: shared ? note.spaceId : nil) {
            case .ok: return .sent
            case .conflict(let code, let message, _, _):
                if code != "stale_revision" { return .failed(sentence(message)) }
            case .unreachable: return .offline
            case .unauthenticated: return .notSynced
            case .rateLimited: return .failed("Too many attempts. Try again in a minute.")
            case .unexpected(let status): return .failed(SyncMessages.unexpected(status))
            case .upgradeRequired: return .failed(SyncMessages.upgradeRequired)
            case .rejected(_, let error, let message, let field):
                let text =
                    switch error {
                    case "email_failed": "Resend didn't send it: \(message)"
                    case "not_found": "This note was deleted."
                    default: sentence(message)
                    }
                return .failed(text, field: field)
            }
        }
        return .failed("This note keeps changing on another device. Try again in a moment.")
    }

    private func sentence(_ s: String) -> String {
        let c = s.prefix(1).uppercased() + s.dropFirst()
        return c.hasSuffix(".") ? c : c + "."
    }
}
