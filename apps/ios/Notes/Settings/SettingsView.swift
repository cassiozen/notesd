import NotesCore
import SwiftUI

/// Like the system's settings (docs/settings.md): who you're signed in as at the top, then each
/// category with its state on the right, under a header that says where its settings are kept.
struct SettingsView: View {
    @Environment(AppGraph.self) private var graph
    @State private var pendingNotes = 0

    var body: some View {
        Form {
            Section {
                NavigationLink(value: Route.settingsSection(.server)) { AccountRow(account: graph.account) }
            }
            Section {
                // With no lock password, creating one needs the server: signed out, there's nothing to offer.
                if graph.lockState != .none || graph.account.isSignedIn {
                    row(.security, security)
                }
                row(.spaces, "\(graph.spaces.count)")
                if graph.account.isSignedIn {
                    row(.integrations, graph.email.settings.map { $0.configured ? "Email" : "Off" } ?? "")
                }
            } header: {
                SectionHeader(graph.account == .none ? "Synced once you connect" : "Synced to your account")
            }
            Section {
                row(.appearance, graph.appearance.label)
                row(.device, sync)
                row(.about, appVersion)
            } header: {
                SectionHeader("This iPhone only")
            }
        }
        .settingsForm(title: "Settings")
        .navigationBarTitleDisplayMode(.large)
        .task(id: graph.syncStatus) { pendingNotes = (try? await graph.store.pendingNotes()) ?? 0 }
        .task(id: graph.account) { if graph.account.isSignedIn { await graph.email.refresh() } }
    }

    private func row(_ section: SettingsSection, _ value: String) -> some View {
        NavigationLink(value: Route.settingsSection(section)) {
            LabeledContent {
                Text(value)
            } label: {
                Label(section.title, systemImage: section.symbol)
            }
        }
        // Named by the title, with its state as the value, as the system's settings rows are.
        .accessibilityLabel(section.title)
        .accessibilityValue(value)
    }

    private var security: String {
        switch graph.lockState {
        case .none: "Off"
        case .locked: "Locked"
        case .unlocked: "Unlocked"
        }
    }

    /// The sync status in a word or two; nothing to say before there's a server.
    private var sync: String {
        let waiting = pendingNotes > 0 ? "\(pendingNotes) waiting" : nil
        return switch graph.syncStatus {
        case .notConnected: waiting ?? ""
        case .syncing: "Syncing…"
        case .synced: waiting ?? "Up to date"
        case .offline: "Offline"
        case .signedOut: "Signed out"
        case .failed: "Sync problem"
        }
    }
}

/// The account at the top of Settings: an initial, the username and the server.
private struct AccountRow: View {
    let account: Account

    var body: some View {
        HStack(spacing: 14) {
            ZStack {
                Circle().fill(Color.accent)
                if let name {
                    Text(name.prefix(1).uppercased()).font(.notesRowTitle).foregroundStyle(Color.ink)
                } else {
                    Image(systemName: "icloud.slash").foregroundStyle(Color.ink)
                }
            }
            .frame(width: 44, height: 44)
            .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(name ?? "Not connected").font(.notesRowTitle).foregroundStyle(Color.ink)
                Text(detail).font(.notesSmall).foregroundStyle(Color.ink2).lineLimit(1)
            }
        }
        .padding(.vertical, 4)
        .accessibilityElement(children: .combine)
    }

    private var name: String? {
        switch account {
        case .signedIn(_, let username), .signedOut(_, let username): username
        case .none: nil
        }
    }

    private var detail: String {
        switch account {
        case .signedIn(let server, _): host(server)
        case .signedOut(let server, _): "Signed out · \(host(server))"
        case .none: "Notes are on this iPhone only"
        }
    }

    private func host(_ server: String) -> String { URL(string: server)?.host() ?? server }
}

/// A category's own page.
struct SettingsSectionView: View {
    let section: SettingsSection
    @Environment(Navigator.self) private var nav
    @Environment(AppGraph.self) private var graph
    @State private var connecting = false
    @State private var confirmSignOut = false
    @State private var confirmRemove = false
    @State private var pendingNotes = 0
    @State private var lastError: String?
    @State private var lastSync: Int64?

    var body: some View {
        @Bindable var graph = graph
        Form {
            switch section {
            case .server: server
            case .security: LockedNotesSection()
            // Back to the list, to show the new space.
            case .spaces: SpacesSection { nav.closeAll() }
            case .integrations: EmailSection()
            case .appearance:
                Section {
                    Picker("Theme", selection: $graph.appearance) {
                        ForEach(Appearance.allCases) { Text($0.label).tag($0) }
                    }
                } footer: {
                    Footnote("Only on this iPhone.")
                }
            case .device: device
            case .about:
                Section {
                    LabeledContent("Version", value: appVersion)
                }
            }
        }
        .settingsForm(title: section.title)
        .navigationBarTitleDisplayMode(.inline)
        .task(id: graph.syncStatus) {
            pendingNotes = (try? await graph.store.pendingNotes()) ?? 0
            let state = try? await graph.engine.store.state()
            lastSync = state?.lastSyncAt
            lastError = state?.lastError
        }
        .sheet(isPresented: $connecting) {
            OnboardingView(
                model: OnboardingModel(
                    accounts: graph.accounts, deviceName: graph.deviceName, lockedServer: graph.account.server,
                    username: username))
        }
        .confirmationDialog("Sign out of this server?", isPresented: $confirmSignOut, titleVisibility: .visible) {
            Button("Sign out", role: .destructive) { Task { await graph.signOut() } }
        } message: {
            Text(
                pendingNotes > 0
                    ? "\(pendingNotes) notes haven’t synced yet. They stay on this iPhone and sync when you sign in again."
                    : "Your notes stay on this iPhone.")
        }
        .confirmationDialog("Remove all data?", isPresented: $confirmRemove, titleVisibility: .visible) {
            Button("Remove all data", role: .destructive) { Task { await graph.removeAllData() } }
        } message: {
            Text(
                pendingNotes > 0
                    ? "\(pendingNotes) notes haven’t synced and will be lost."
                    : "Notes on this iPhone are deleted. The server keeps its copy.")
        }
    }

    @ViewBuilder private var server: some View {
        Section {
            switch graph.account {
            case .none:
                Text("Notes are on this iPhone only.").foregroundStyle(Color.ink2)
                Button("Connect to a server") { connecting = true }
            case .signedIn(let server, let username):
                LabeledContent("Server") { Text(server).font(.notesMono).lineLimit(1) }
                LabeledContent("Signed in as", value: username)
                Button("Sign out", role: .destructive) { confirmSignOut = true }.foregroundStyle(Color.danger)
            case .signedOut(let server, let username):
                LabeledContent("Server") { Text(server).font(.notesMono).lineLimit(1) }
                LabeledContent("Account", value: username)
                Button("Sign in again") { connecting = true }
            }
        }
    }

    @ViewBuilder private var device: some View {
        if graph.account != .none {
            Section {
                LabeledContent("Status") { SyncLabel(status: graph.syncStatus, pending: pendingNotes) }
                if let lastSync { LabeledContent("Last synced", value: listTime(lastSync)) }
                if let lastError { Text(lastError).font(.notesSmall).foregroundStyle(Color.errInk) }
                Button("Sync now") { graph.scheduler.request() }
            } header: {
                SectionHeader("Sync")
            }
        }
        Section {
            Button("Remove all data from this iPhone", role: .destructive) { confirmRemove = true }
                .foregroundStyle(Color.danger)
        } footer: {
            Footnote(
                "Deletes the notes on this iPhone and forgets the server. Notes already synced stay on the server.")
        }
    }

    private var username: String {
        switch graph.account {
        case .signedIn(_, let u), .signedOut(_, let u): u
        case .none: ""
        }
    }
}

/// A section's small print.
struct Footnote: View {
    let text: String

    init(_ text: String) { self.text = text }

    var body: some View {
        Text(text).font(.notesSmall).foregroundStyle(Color.ink2)
    }
}

private var appVersion: String {
    Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? ""
}

extension View {
    /// Settings' forms: the app's fonts and paper, and a tint that reads on white rows.
    fileprivate func settingsForm(title: String) -> some View {
        font(.notesBody)
            // The tint is too light for text on white rows; link brown reads as a button and passes contrast.
            .tint(Color(Palette.link))
            .scrollContentBackground(.hidden)
            // Beside the list on a wide window, the rows keep a phone's width.
            .frame(maxWidth: 640)
            .frame(maxWidth: .infinity)
            .background(Color.notesBg)
            .navigationTitle(title)
    }
}
