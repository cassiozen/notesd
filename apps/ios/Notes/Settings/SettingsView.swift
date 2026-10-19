import NotesCore
import SwiftUI

/// The server and account, sync, appearance, and removing everything.
struct SettingsView: View {
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
            } header: {
                SectionHeader("Server")
            }
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
            // With no lock password, creating one needs the server: signed out, there's nothing to offer.
            if graph.lockState != .none || graph.account.isSignedIn {
                LockedNotesSection()
            }
            if case .signedIn = graph.account {
                EmailSection()
            }
            SpacesSection()
            Section {
                Picker("Theme", selection: $graph.appearance) {
                    ForEach(Appearance.allCases) { Text($0.label).tag($0) }
                }
            } header: {
                SectionHeader("Appearance")
            }
            Section {
                Button("Remove all data from this iPhone", role: .destructive) { confirmRemove = true }
                    .foregroundStyle(Color.danger)
            } footer: {
                Text(
                    "Deletes the notes on this iPhone and forgets the server. Notes already synced stay on the server."
                )
                .font(.notesSmall).foregroundStyle(Color.ink2)
            }
            Section {
                LabeledContent(
                    "Version",
                    value: Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "")
            }
        }
        .font(.notesBody)
        // The tint is too light for text on white rows; link brown reads as a button and passes contrast.
        .tint(Color(Palette.link))
        .scrollContentBackground(.hidden)
        .background(Color.notesBg)
        .navigationTitle("Settings")
        .navigationBarTitleDisplayMode(.large)
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

    private var username: String {
        switch graph.account {
        case .signedIn(_, let u), .signedOut(_, let u): u
        case .none: ""
        }
    }
}
