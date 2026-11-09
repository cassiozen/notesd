import NotesCore

/// A space as a public website (§7.10): what the calls' refusals say.
enum Publishing {
    static let key = "published"

    /// Until the server says, a published space has no address: only that it's public.
    static let publishing = SpaceTrait(
        key: key, label: "Published", summary: "Publishing…", exposure: "Public", audience: ["public"])

    /// `space` as `on` (expected; nil for nothing) shows it.
    static func shown(_ space: Space, _ on: Bool?) -> Space {
        let published = space.traits.first { $0.key == key }
        var shown = space
        if on == true, published == nil {
            // After sharing, before the rest: the server's order (spaces.TraitOrder).
            let at = space.traits.firstIndex { $0.key != "shared" } ?? space.traits.count
            shown.traits.insert(publishing, at: at)
        } else if on == false, let published {
            shown.traits.removeAll { $0.key == key }
            shown.leaving.append(published)
        }
        return shown
    }

    static let handleTaken = "Another space has that address. Try another."
    static let handleRules = "Use 1–64 lowercase letters, digits and hyphens, not starting or ending with a hyphen."

    /// Why a space with locked notes can't be published: the count when this iPhone knows it.
    static func locked(_ count: Int) -> String {
        switch count {
        case 0: "Unlock its locked notes to publish it."
        case 1: "Unlock its locked note to publish it."
        default: "Unlock its \(count) locked notes to publish it."
        }
    }

    /// A publishing call's failure in words; nil when it went through.
    static func message<T>(_ result: ApiResult<T>, locked: Int = 0) -> String? {
        switch result {
        case .ok: return nil
        case .conflict("handle_taken", _, _): return handleTaken
        case .conflict("space_has_locked", _, _): return Self.locked(locked)
        case .conflict("space_default", _, _): return "The default space can’t be published."
        case .rejected(_, "invalid_request", _, "handle"): return handleRules
        case .rejected(_, "not_published", _, _): return "This space isn’t published."
        case .unauthenticated: return "You’re signed out. Sign in again to publish."
        default: return Sharing.message(result)
        }
    }

    /// The address without its scheme: "notes.example.com/@garden".
    static func shown(_ settings: PublishSettings) -> String {
        "\(host(settings))/@\(settings.handle)"
    }

    static func host(_ settings: PublishSettings) -> String {
        settings.origin.components(separatedBy: "://").last ?? settings.origin
    }
}

/// The publishing calls (§7.10). Each that changes something syncs after, so the space's `published` trait, and
/// the lock rules that come with it, reach this iPhone.
extension AppGraph {
    /// How the space is published; nil if it never was.
    func publishSettings(_ spaceId: String) async -> Result<PublishSettings?, SharingError> {
        guard let client = accounts.client() else { return .failure(SharingError(Self.signInToPublish)) }
        let result = await client.publishSettings(spaceId)
        switch result {
        case .ok(let settings): return .success(settings)
        case .rejected(_, "not_published", _, _): return .success(nil)
        default: return .failure(SharingError(Publishing.message(result) ?? ""))
        }
    }

    /// Publishes at an address from the space's name, built from this server's address as the app reaches it.
    func publish(_ space: Space) async -> Result<PublishSettings, SharingError> {
        guard let client = accounts.client() else { return .failure(SharingError(Self.signInToPublish)) }
        // Said here with the count: the server only knows the notes that reached it.
        let locked = (try? await store.lockedCount(space: space.id)) ?? 0
        if locked > 0 { return .failure(SharingError(Publishing.locked(locked))) }
        let call = expect(space.id, true)
        let result = await changed(client.publish(space.id, origin: client.origin))
        if case .failure = result { unexpect(space.id, call) }
        return result
    }

    /// Moves the site to `handle`; the old address stops working.
    func changeHandle(_ spaceId: String, to handle: String) async -> Result<PublishSettings, SharingError> {
        guard let client = accounts.client() else { return .failure(SharingError(Self.signInToPublish)) }
        return await changed(client.publish(spaceId, handle: handle))
    }

    /// Takes the site down; its address stays the space's.
    func stopPublishing(_ spaceId: String) async -> String? {
        guard let client = accounts.client() else { return Self.signInToPublish }
        let call = expect(spaceId, false)
        if let problem = Publishing.message(await client.unpublish(spaceId)) {
            unexpect(spaceId, call)
            return problem
        }
        await scheduler.syncNow()
        return nil
    }

    /// Expects `on` for the space, as the call it returns: a call that fails clears only its own, not a newer one's.
    private func expect(_ spaceId: String, _ on: Bool) -> Int {
        expectations += 1
        expectedBy[spaceId] = expectations
        expected[spaceId] = on
        return expectations
    }

    private func unexpect(_ spaceId: String, _ call: Int) {
        guard expectedBy[spaceId] == call else { return }
        expectedBy[spaceId] = nil
        expected[spaceId] = nil
    }

    private func changed(_ result: ApiResult<PublishSettings>) async -> Result<PublishSettings, SharingError> {
        guard case .ok(let settings) = result else {
            return .failure(SharingError(Publishing.message(result) ?? ""))
        }
        await scheduler.syncNow()
        return .success(settings)
    }

    private static let signInToPublish = "Sign in to your server to publish spaces."
}
