import Foundation
import Testing

@testable import Notes
@testable import NotesCore

/// Shared spaces' words (§7.9): who edited, what changed, moving out, and errors.
struct SharingTests {
    // Midday UTC, so "a minute ago" is still today in the simulator's zone (CI runs in UTC).
    let now: Int64 = 1_767_268_800_000
    let family = Space(
        id: "fam", name: "Family", noteCount: 2,
        shared: SharedSpace(
            role: "member", members: [Member(id: 1, username: "Ana"), Member(id: 2, username: "Ben")]))

    @Test func namesAreListedAsTraitSummariesListThem() {
        #expect(Sharing.names(["Ana"]) == "Ana")
        #expect(Sharing.names(["Ana", "Ben"]) == "Ana and Ben")
        #expect(Sharing.names(["Ana", "Ben", "Cy"]) == "Ana, Ben and Cy")
        #expect(Sharing.names(["Ana", "Ben", "Cy", "Dee"]) == "Ana, Ben and 2 others")
    }

    @Test func howLongAgoInAFewCharactersThenADate() {
        #expect(Sharing.ago(now - 10_000, now: now) == "now")
        #expect(Sharing.ago(now - 5 * 60_000, now: now) == "5m")
        #expect(Sharing.ago(now - 2 * 3_600_000, now: now) == "2h")
        #expect(Sharing.ago(now - 3 * 86_400_000, now: now) == "3d")
        #expect(Sharing.ago(now + 60_000, now: now) == "now", "a clock ahead of this one isn't the future")
        let weekAgo = now - 8 * 86_400_000
        let date = Date(timeIntervalSince1970: TimeInterval(weekAgo) / 1000)
        let sameYear =
            Calendar.current.component(.year, from: date)
            == Calendar.current.component(.year, from: Date(timeIntervalSince1970: TimeInterval(now) / 1000))
        let expected =
            sameYear
            ? date.formatted(.dateTime.month(.abbreviated).day()) : date.formatted(date: .numeric, time: .omitted)
        #expect(Sharing.ago(weekAgo, now: now) == expected)
    }

    @Test func whatChangedCountsNotesAndNamesWho() {
        let at = now - 2 * 3_600_000
        let time = "2h"
        #expect(
            Sharing.newsLine(SpaceNews(count: 3, usernames: ["Ben"], latestAt: at), now: now)
                == "Ben edited 3 notes · \(time)")
        #expect(
            Sharing.newsLine(SpaceNews(count: 1, usernames: ["Ben", "Ana"], latestAt: at), now: now)
                == "Ben and Ana edited a note · \(time)")
        #expect(
            Sharing.newsLine(SpaceNews(count: 2, usernames: [], latestAt: at), now: now)
                == "Someone edited 2 notes · \(time)")
    }

    @Test func onlyAnotherMembersEditIsNamed() {
        #expect(Sharing.editor(1, in: family, me: 2) == "Ana")
        #expect(Sharing.editor(2, in: family, me: 2) == nil, "your own")
        #expect(Sharing.editor(nil, in: family, me: 2) == nil, "from before it was recorded")
        #expect(Sharing.editor(9, in: family, me: 2) == nil, "no longer in the space")
        #expect(Sharing.editor(1, in: Space(id: "w", name: "Work", noteCount: 0), me: 2) == nil, "not shared")
    }

    @Test func rowsAndTheEditorSayWho() {
        let today = now - 60_000
        #expect(Sharing.rowTime(today, by: "Ana", now: now) == "Ana · 1m")
        #expect(Sharing.rowTime(today, by: nil, now: now) == listTime(today, now: now))
        let yesterday = now - 86_400_000
        #expect(Sharing.editedLine("Ana", at: yesterday, now: now) == "Edited by Ana · 1d")
        #expect(Sharing.editedLine("Ana", at: today, now: now) == "Edited by Ana · 1m")
    }

    @Test func leavingWarnsOfChangesNotYetSynced() {
        #expect(Sharing.leaveWarning("Family", unsynced: 0) == "Its notes will be removed from this iPhone.")
        #expect(
            Sharing.leaveWarning("Family", unsynced: 1).hasSuffix(
                "1 note here has changes not yet synced: they’ll be kept as a copy in your own notes."))
        #expect(Sharing.leaveWarning("Family", unsynced: 3).contains("3 notes here have changes"))
    }

    @Test func refusalsAreInWords() {
        let locked: ApiResult<Void> = .conflict(code: "space_has_locked", message: "m", current: nil)
        #expect(Sharing.message(locked, locked: 3) == "Unlock its 3 locked notes to share it.")
        #expect(Sharing.message(locked, locked: 1) == "Unlock its locked note to share it.")
        #expect(Sharing.message(locked) == "Unlock its locked notes to share it.", "locked on another device")
        let gone: ApiResult<Void> = .rejected(status: 404, error: "not_found", message: "user not found", field: nil)
        #expect(Sharing.message(gone) == "That’s no longer there. It may have changed on another device.")
        let owner: ApiResult<Void> = .rejected(status: 403, error: "not_space_owner", message: "", field: nil)
        #expect(Sharing.message(owner) == "Only the space’s owner can do that.")
        let other: ApiResult<Void> = .rejected(status: 400, error: "invalid_request", message: "bad user", field: nil)
        #expect(Sharing.message(other) == "Bad user.")
        #expect(Sharing.message(ApiResult<Void>.ok(())) == nil)
    }

    @Test func movingOutOfASharedSpaceSaysWhoWillNoLongerSeeIt() {
        let shared = SpaceTrait(
            key: "shared", label: "Shared", summary: "With Ana and Ben", exposure: "Visible to Ana and Ben")
        let lost = Traits.newExposure(from: [], to: [shared]).compactMap(\.exposure)
        let notes = Space(id: "default", name: "Notes", noteCount: 0)
        let check = MoveCheck(space: notes, title: "Plans", exposure: [], lost: lost)
        #expect(check.message == "It will no longer be:\nVisible to Ana and Ben")
        let both = MoveCheck(space: notes, title: "Plans", exposure: ["Public at x"], lost: lost)
        #expect(
            both.message == "In Notes, this note will be:\nPublic at x\n\nIt will no longer be:\nVisible to Ana and Ben"
        )
    }

    @Test func anInvitationShowsWhoAndWhere() {
        let invite = SpaceInvite(
            RemoteInvite(
                id: "i", spaceId: "trip", spaceName: "Trip", spaceIcon: "list", from: Member(id: 1, username: "Ana")))
        #expect(invite == SpaceInvite(id: "i", spaceId: "trip", spaceName: "Trip", spaceIcon: "list", from: "Ana"))
    }
}
