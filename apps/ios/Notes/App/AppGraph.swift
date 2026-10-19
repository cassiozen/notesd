import Foundation
import NotesCore
import Observation
import UIKit

/// The app's long-lived objects, wired by hand. One per process, owned by `NotesApp`.
@MainActor
@Observable
final class AppGraph {
    let database: Database
    let store: NoteStore
    let accounts: AccountStore
    let engine: SyncEngine
    let images: ImageStore
    let locks: LockSession
    /// Face ID unlock for locked notes, and what this iPhone calls it.
    let biometrics: BiometricUnlock
    let biometry: BiometryKind
    let mailer: NoteMailer
    let email: EmailSetup
    @ObservationIgnored private(set) lazy var scheduler = SyncScheduler(graph: self)

    /// The account as the screens show it, kept in step with the store.
    private(set) var account: Account
    private(set) var syncStatus: SyncStatus = .notConnected
    private(set) var lockState: LockSession.State = .none

    /// What the list shows. Kept so the app reopens on it, unless it was chosen in another space:
    /// the app opens in the default one (§7.7).
    var listFilter: NoteFilter? {
        didSet {
            Self.defaults.set(try? JSONEncoder().encode(listFilter), forKey: "listFilter")
            Self.defaults.set(space, forKey: "listFilterSpace")
        }
    }

    /// Every space (§7.7), the default first, kept current with every write.
    private(set) var spaces: [Space] = []

    /// The space the list, tags and search show: this iPhone's own, never synced, the default at a cold start.
    private(set) var space = Spaces.defaultId

    /// A note is open: coming back after a while doesn't move the list from under it.
    @ObservationIgnored var editorOpen = false
    @ObservationIgnored private var backgroundSince: Date?

    /// Back after this long away with no note open, the list starts over in the default space.
    static let spaceReset: TimeInterval = 30 * 60

    /// Shows another space, from All notes.
    func switchSpace(_ id: String) {
        guard space != id else { return }
        space = id
        listFilter = nil
    }

    func enteredBackground() {
        backgroundSince = Date()
    }

    func enteredForeground() {
        guard let since = backgroundSince else { return }
        backgroundSince = nil
        if Date().timeIntervalSince(since) > Self.spaceReset && !editorOpen { switchSpace(Spaces.defaultId) }
    }

    var appearance: Appearance {
        didSet { Self.defaults.set(appearance.rawValue, forKey: "appearance") }
    }

    /// Something sync did that the user should hear about, until they've seen it.
    var notice: String?

    /// The note about to open, read before its screen is pushed so it opens with its text.
    @ObservationIgnored private var preloaded: Note?

    /// Reads note `id` ahead of opening it.
    func preload(_ id: String) async {
        preloaded = try? await store.get(id)
    }

    /// The note `preload` read, if it was `id`.
    func opening(_ id: String) -> Note? {
        preloaded?.id == id ? preloaded : nil
    }

    private static let defaults = UserDefaults.standard

    /// UI tests start from nothing: a new database and Keychain item each launch, and no saved preferences.
    static let uiTesting = ProcessInfo.processInfo.arguments.contains("--ui-testing")

    init(
        databasePath: String = AppGraph.defaultDatabasePath(),
        accounts: AccountStore = AccountStore(
            keychain: Keychain(service: uiTesting ? "notesd.account.uitests" : "notesd.account"))
    ) {
        if Self.uiTesting {
            accounts.clear()
            for key in [
                "listFilter", "listFilterSpace", "appearance", "openTags", BiometricUnlock.bound,
                BiometricUnlock.declined,
            ] {
                Self.defaults.removeObject(forKey: key)
            }
        }
        database = Database(path: databasePath)
        store = NoteStore(db: database)
        self.accounts = accounts
        let dirs = Self.imageDirectories()
        images = ImageStore(
            db: database, pendingDir: dirs.pending, cacheDir: dirs.cache, api: { [accounts] in accounts.client() })
        engine = SyncEngine(db: database, api: { [accounts] in accounts.client() }, images: images)
        let locks = LockSession(keys: engine.lockKeys, api: { [accounts] in accounts.client() })
        self.locks = locks
        let kind = Self.uiTesting ? BiometryKind.faceID : BiometryKind.current()
        biometry = kind
        biometrics = BiometricUnlock(
            locks: locks,
            biometrics: Self.uiTesting
                ? FakeBiometrics(arguments: ProcessInfo.processInfo.arguments)
                : KeychainBiometrics(service: "notesd.locks.biometric", kind: kind))
        let loader = ImageLoader(store: images) { sealed in
            locks.key().flatMap { try? LockedNotes.decryptImage($0, sealed) }
        }
        ImageLoader.shared = loader
        mailer = NoteMailer(store: store, engine: engine, api: { [accounts] in accounts.client() })
        email = EmailSetup(api: { [accounts] in accounts.client() })
        account = accounts.account.value
        appearance = Appearance(rawValue: Self.defaults.string(forKey: "appearance") ?? "") ?? .system
        let savedIn = Self.defaults.string(forKey: "listFilterSpace") ?? Spaces.defaultId
        listFilter =
            savedIn == Spaces.defaultId
            ? Self.defaults.data(forKey: "listFilter").flatMap { try? JSONDecoder().decode(NoteFilter?.self, from: $0) }
                ?? nil
            : nil
        Task { [weak self, store, engine] in
            // Subscribed before the first read, so a change landing in between isn't missed.
            let changes = store.changes()
            await self?.reloadSpaces(engine)
            for await _ in changes { await self?.reloadSpaces(engine) }
        }
        Task { [weak self, accounts] in
            for await account in accounts.account.stream() { self?.account = account }
        }
        Task { [weak self, engine, locks] in
            for await status in engine.status.stream() {
                self?.syncStatus = status
                // The keyring came with the cycle: a password changed or reset elsewhere locks here too.
                if case .synced = status { await locks.refresh() }
            }
        }
        Task { [weak self, locks] in
            for await state in locks.state.stream() {
                self?.lockState = state
                // Decrypted pictures go with the key.
                if state != .unlocked { loader.forgetLocked() }
            }
        }
        Task { await locks.refresh() }
        Task { [weak self, engine] in
            for await event in engine.events() { self?.notice = Self.describe(event) }
        }
    }

    private func reloadSpaces(_ engine: SyncEngine) async {
        guard let list = try? await engine.spaces.list() else { return }
        spaces = list
        // The space shown was deleted, here or on another device: back to the default.
        if !list.isEmpty && !list.contains(where: { $0.id == space }) { switchSpace(Spaces.defaultId) }
    }

    /// The space's name, or the default's while the list loads.
    func spaceName(_ id: String) -> String {
        spaces.first { $0.id == id }?.name ?? Spaces.defaultName
    }

    /// The database lives in Application Support, readable after the first unlock so background sync can open it.
    nonisolated static func defaultDatabasePath() -> String {
        let fm = FileManager.default
        if ProcessInfo.processInfo.arguments.contains("--ui-testing") {
            return fm.temporaryDirectory.appending(path: "uitest-\(UUID().uuidString).db").path
        }
        let dir = fm.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? fm.createDirectory(
            at: dir, withIntermediateDirectories: true,
            attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication])
        return dir.appending(path: "notes.db").path
    }

    /// Pictures waiting to upload live with the database (never purged); fetched ones in Caches, which iOS may clear.
    nonisolated static func imageDirectories() -> (pending: URL, cache: URL) {
        let fm = FileManager.default
        if ProcessInfo.processInfo.arguments.contains("--ui-testing") {
            let root = fm.temporaryDirectory.appending(path: "uitest-images-\(UUID().uuidString)")
            return (root.appending(path: "pending"), root.appending(path: "cache"))
        }
        let support = fm.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        let caches = fm.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        return (support.appending(path: "images"), caches.appending(path: "images"))
    }

    /// How this phone appears in the server's list of signed-in devices.
    var deviceName: String { UIDevice.current.name }

    /// Ends the session on the server if it answers quickly, then forgets the
    /// tokens. Notes and the server address stay: signing in again resumes.
    func signOut() async {
        if let client = accounts.client() {
            _ = await withTimeout(seconds: 5) { await client.logout() }
        }
        accounts.signOut()
        email.forget()
        await locks.forget()
        biometrics.disable()
    }

    /// Back to a fresh install: no notes, no account, working offline.
    func removeAllData() async {
        if case .signedIn = account, let client = accounts.client() {
            _ = await withTimeout(seconds: 5) { await client.logout() }
        }
        // Signed out first, so a cycle that starts after the wipe finds no session; the wipe itself waits
        // for any running cycle, which would otherwise write pulled notes back into the emptied database.
        accounts.clear()
        try? await engine.exclusive { [store, images, engine] in
            try await store.wipe()
            try await images.wipe()
            // Through the key store, which moves its generation on: a keyring fetch still in flight can't put it back.
            try await engine.lockKeys.replace([])
        }
        email.forget()
        await locks.forget()
        biometrics.disable()
        listFilter = nil
        switchSpace(Spaces.defaultId)
    }

    private static func describe(_ event: SyncEvent) -> String {
        switch event {
        case .conflictedCopy(_, _, let title):
            "This note changed on another device too. Your version is kept as “\(title)”."
        case .recoveredCopy(_, let title):
            "A note you edited was deleted on another device. Your edits are kept as “\(title)”."
        case .restored(_, let title): "“\(title)” changed on another device, so it’s back."
        }
    }
}

enum Appearance: String, CaseIterable, Identifiable {
    case system, light, dark

    var id: String { rawValue }

    var label: String {
        switch self {
        case .system: "Automatic"
        case .light: "Light"
        case .dark: "Dark"
        }
    }

    var style: UIUserInterfaceStyle {
        switch self {
        case .system: .unspecified
        case .light: .light
        case .dark: .dark
        }
    }
}

/// `body`'s result, or nil if it takes longer than `seconds`.
func withTimeout<T: Sendable>(seconds: Double, _ body: @escaping @Sendable () async -> T) async -> T? {
    await withTaskGroup(of: T?.self) { group in
        group.addTask { await body() }
        group.addTask {
            try? await Task.sleep(for: .seconds(seconds))
            return nil
        }
        let first = await group.next() ?? nil
        group.cancelAll()
        return first
    }
}
