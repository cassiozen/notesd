import Foundation
import NotesCore

/// Shared spaces' words (§7.9): who edited, what changed, and errors from the membership calls.
enum Sharing {
    /// Names as trait summaries list them: "Ana", "Ana and Ben", "Ana, Ben and Cy", "Ana, Ben and 2 others".
    static func names(_ names: [String]) -> String {
        switch names.count {
        case 0: ""
        case 1: names[0]
        case 2, 3: names.dropLast().joined(separator: ", ") + " and " + names[names.count - 1]
        default: "\(names[0]), \(names[1]) and \(names.count - 2) others"
        }
    }

    /// How long ago another member's change was, in a few characters: "now", "5m", "2h", "3d", then a date.
    static func ago(
        _ ts: Int64, now: Int64 = systemNow(), calendar: Calendar = .current, locale: Locale = .current
    ) -> String {
        let minute: Int64 = 60_000
        let hour = 60 * minute
        let day = 24 * hour
        let d = max(0, now - ts)
        if d < minute { return "now" }
        if d < hour { return "\(d / minute)m" }
        if d < day { return "\(d / hour)h" }
        if d < 7 * day { return "\(d / day)d" }
        let at = Date(timeIntervalSince1970: TimeInterval(ts) / 1000)
        let sameYear =
            calendar.component(.year, from: at)
            == calendar.component(.year, from: Date(timeIntervalSince1970: TimeInterval(now) / 1000))
        if sameYear {
            return at.formatted(
                Date.FormatStyle(locale: locale, calendar: calendar, timeZone: calendar.timeZone)
                    .month(.abbreviated).day())
        }
        return at.formatted(
            Date.FormatStyle(
                date: .numeric, time: .omitted, locale: locale, calendar: calendar, timeZone: calendar.timeZone))
    }

    /// The switcher's line for a space others changed: "Ben edited 3 notes · 2h".
    static func newsLine(_ news: SpaceNews, now: Int64 = systemNow()) -> String {
        let who = news.usernames.isEmpty ? "Someone" : names(news.usernames)
        let what = news.count == 1 ? "a note" : "\(news.count) notes"
        return "\(who) edited \(what) · \(ago(news.latestAt, now: now))"
    }

    /// Who last edited a note, by username, when it was another member of `space`; nil for this account's own
    /// edits, a space that isn't shared, or someone no longer in it.
    static func editor(_ updatedBy: Int64?, in space: Space?, me: Int64?) -> String? {
        guard let updatedBy, updatedBy != me, let members = space?.shared?.members else { return nil }
        return members.first { $0.id == updatedBy }?.username
    }

    /// A note row's time: another member's name and how long ago when they made the last edit ("Ana · 2h").
    static func rowTime(_ updatedAt: Int64, by editor: String?, now: Int64 = systemNow()) -> String {
        editor.map { "\($0) · \(ago(updatedAt, now: now))" } ?? listTime(updatedAt, now: now)
    }

    /// The editor's line for another member's edit: "Edited by Ana · 2h".
    static func editedLine(_ editor: String, at: Int64, now: Int64 = systemNow()) -> String {
        "Edited by \(editor) · \(ago(at, now: now))"
    }

    /// What leaving warns: the notes go; unsynced changes stay, as copies in the account's own notes.
    static func leaveWarning(_ space: String, unsynced: Int) -> String {
        let gone = "Its notes will be removed from this iPhone."
        switch unsynced {
        case 0: return gone
        case 1: return gone + " 1 note here has changes not yet synced: they’ll be kept as a copy in your own notes."
        default:
            return gone + " \(unsynced) notes here have changes not yet synced: they’ll be kept as copies in your own "
                + "notes."
        }
    }

    /// Who of `people` can be invited to `space`: not `me`, nor anyone in it or invited to it already.
    static func invitable(_ people: [Member], to space: Space, me: Int64?) -> [Member] {
        let taken = Set((space.shared?.members ?? []).map(\.id) + (space.shared?.invites ?? []).map(\.userId))
        return people.filter { $0.id != me && !taken.contains($0.id) }
    }

    /// A member's part in a space, as People lists it: "Owner", "You", "Owner · You"; "" for anyone else.
    static func role(of member: Member, in shared: SharedSpace, me: Int64?) -> String {
        let owner = member.id == shared.members.first?.id ? "Owner" : nil
        let you = member.id == me ? "You" : nil
        return [owner, you].compactMap { $0 }.joined(separator: " · ")
    }

    static let lockedInShared = "Locked notes can’t be in a shared space."

    /// A membership call's failure in words. `locked`: the space's locked notes here, for a refused share; 0 when
    /// this iPhone has none yet (locked on another device), so no count.
    static func message<T>(_ result: ApiResult<T>, locked: Int = 0) -> String? {
        switch result {
        case .ok: return nil
        case .conflict("space_has_locked", _, _):
            switch locked {
            case 0: return "Unlock its locked notes to share it."
            case 1: return "Unlock its locked note to share it."
            default: return "Unlock its \(locked) locked notes to share it."
            }
        case .conflict("space_default", _, _): return "The default space can’t be shared."
        case .conflict("owner_cannot_leave", _, _):
            return "Make someone else the owner first, or stop sharing."
        case .conflict("already_member", _, _): return "They’re already in this space."
        case .rejected(_, "not_space_owner", _, _): return "Only the space’s owner can do that."
        // The space, the invitation or the person invited: the answer doesn't say which.
        case .rejected(_, "not_found", _, _): return "That’s no longer there. It may have changed on another device."
        case .unreachable, .rateLimited: return "Your server can’t be reached. Try again when you’re online."
        case .unauthenticated: return "You’re signed out. Sign in again to share."
        case .upgradeRequired: return SyncMessages.upgradeRequired
        case .unexpected(let status): return SyncMessages.unexpected(status)
        case .conflict(_, let message, _), .rejected(_, _, let message, _):
            return message.isEmpty ? "Something went wrong. Try again." : sentence(message)
        }
    }

    private static func sentence(_ s: String) -> String {
        let c = s.prefix(1).uppercased() + s.dropFirst()
        return c.hasSuffix(".") ? c : c + "."
    }
}

/// The membership calls (§7.9). Each answers nil when done, or what went wrong in words; done, a sync brings
/// the list as it is now.
extension AppGraph {
    /// Joins, then shows the space once the list has it.
    func join(_ invite: SpaceInvite) async -> String? {
        guard let client = accounts.client() else { return Self.signInToShare }
        if let problem = Sharing.message(await client.acceptInvite(invite.id)) { return problem }
        await scheduler.syncNow()
        await reloadSpaces()
        if spaces.contains(where: { $0.id == invite.spaceId }) { switchSpace(invite.spaceId) }
        return nil
    }

    func decline(_ invite: SpaceInvite) async -> String? {
        await call { await $0.declineInvite(invite.id) }
    }

    /// Everyone with an account on the server, for an owner to invite (`Sharing.invitable` leaves out who's in).
    func people() async -> Result<[Member], SharingError> {
        guard let client = accounts.client() else { return .failure(SharingError(Self.signInToShare)) }
        let result = await client.people()
        guard case .ok(let people) = result else { return .failure(SharingError(Sharing.message(result) ?? "")) }
        return .success(people)
    }

    /// The first invitation shares the space: its notes move to a file of their own.
    func invite(_ userId: Int64, to space: Space) async -> String? {
        guard let client = accounts.client() else { return Self.signInToShare }
        let result = await client.inviteToSpace(space.id, userId: userId)
        if case .conflict("space_has_locked", _, _) = result {
            return Sharing.message(result, locked: (try? await store.lockedCount(space: space.id)) ?? 0)
        }
        if let problem = Sharing.message(result) { return problem }
        await scheduler.syncNow()
        return nil
    }

    func cancelInvite(_ inviteId: String, in space: String) async -> String? {
        await call { await $0.cancelInvite(space, inviteId: inviteId) }
    }

    func removeMember(_ userId: Int64, from space: String) async -> String? {
        await call { await $0.removeMember(space, userId: userId) }
    }

    /// This account leaves; the sync after takes the space's notes off this iPhone.
    func leave(_ space: String) async -> String? {
        guard let me = userId else { return Self.signInToShare }
        return await removeMember(me, from: space)
    }

    func transfer(_ space: String, to userId: Int64) async -> String? {
        await call { await $0.transferSpace(space, userId: userId) }
    }

    /// Every member removed and every invitation cancelled: the space stays, private again.
    func stopSharing(_ space: Space) async -> String? {
        guard let client = accounts.client() else { return Self.signInToShare }
        func calls() async -> String? {
            for invite in space.shared?.invites ?? [] {
                if let problem = Sharing.message(await client.cancelInvite(space.id, inviteId: invite.id)) {
                    return problem
                }
            }
            // The list has the owner first: this account, whose id may not have loaded yet.
            for member in (space.shared?.members ?? []).dropFirst() {
                if let problem = Sharing.message(await client.removeMember(space.id, userId: member.id)) {
                    return problem
                }
            }
            return nil
        }
        let problem = await calls()
        // Even after a failure: the calls before it went through, and the lists should show them.
        await scheduler.syncNow()
        return problem
    }

    private func call(_ request: (ApiClient) async -> ApiResult<Void>) async -> String? {
        guard let client = accounts.client() else { return Self.signInToShare }
        if let problem = Sharing.message(await request(client)) { return problem }
        await scheduler.syncNow()
        return nil
    }

    private static let signInToShare = "Sign in to your server to share spaces."
}

struct SharingError: Error {
    let message: String

    init(_ message: String) { self.message = message }
}
