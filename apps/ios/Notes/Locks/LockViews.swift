import NotesCore
import SwiftUI

/// Shortest lock password accepted; it's the only thing between the server and the notes.
let minLockPassword = 8

extension LockSession.Outcome {
    /// What a keyring change that needs the server says when it can't finish.
    var message: String? {
        switch self {
        case .done: nil
        case .wrongPassword: "Wrong password."
        case .unsupported: LockedError.unsupportedKdf.message
        case .notConnected: "Connect to your server first: it keeps the lock password for every device."
        case .offline: "Can’t reach the server. Try again when you’re online."
        case .failed: "Something went wrong. Try again."
        }
    }
}

extension LockSession {
    /// When this iPhone's copy of the lock password couldn't be read: not a wrong password.
    static let unreadKeyring = "The lock password couldn’t be checked on this iPhone. Try again."
}

/// A locked note while locked notes are locked: its title and the lock password field.
struct LockedNoteView: View {
    @Environment(AppGraph.self) private var graph
    @Environment(\.scenePhase) private var scenePhase
    let title: String
    /// Locked with a key that was reset: no password opens it.
    let unreadable: Bool
    /// Why it's unreadable, when not a reset.
    let reason: String?
    let locks: LockSession
    /// Ask for Face ID as soon as this shows: the note was opened, not just locked from its screen.
    let promptBiometrics: Bool
    /// Face ID was asked for since the app last came to the front: a dismissed prompt isn't asked again.
    @State private var prompted: Bool
    @State private var password = ""
    @State private var error: String?
    @State private var busy = false
    /// Counts wrong passwords: each one shakes the field.
    @State private var misses = 0
    @FocusState private var focused: Bool

    init(title: String, unreadable: Bool, locks: LockSession, promptBiometrics: Bool = true, reason: String? = nil) {
        self.title = title
        self.unreadable = unreadable
        self.reason = reason
        self.locks = locks
        self.promptBiometrics = promptBiometrics
        _prompted = State(initialValue: !promptBiometrics)
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 14) {
                Image(systemName: "lock.fill").font(.system(size: 34)).foregroundStyle(Color.accentDeep)
                    .accessibilityHidden(true)
                    .padding(.top, 48)
                Text(title.isEmpty ? "Locked note" : title).font(.notesTitle).foregroundStyle(Color.ink)
                    .multilineTextAlignment(.center)
                if unreadable {
                    Text(reason ?? "This note was locked with a password that was reset. It can’t be opened.")
                        .font(.notesBody).foregroundStyle(Color.ink2).multilineTextAlignment(.center)
                } else {
                    Text("This note is locked.").font(.notesSmall).foregroundStyle(Color.ink2)
                    VStack(alignment: .leading, spacing: 6) {
                        SecureField("Lock password", text: $password)
                            .textContentType(.password)
                            .submitLabel(.go)
                            .focused($focused)
                            .onSubmit(unlock)
                            // Typing clears the error; the field emptied after a wrong password doesn't.
                            .onChange(of: password) { _, typed in if !typed.isEmpty { error = nil } }
                            .padding(.horizontal, 14)
                            .frame(minHeight: 48)
                            .background(Color(Palette.field), in: .rect(cornerRadius: 12))
                            .overlay(RoundedRectangle(cornerRadius: 12).stroke(Color.hair))
                            .disabled(busy)
                        if let error {
                            Text(error).font(.notesSmall).foregroundStyle(Color.errInk)
                        }
                    }
                    .padding(.top, 8)
                    .keyframeAnimator(initialValue: 0.0, trigger: misses) { content, x in
                        content.offset(x: x)
                    } keyframes: { _ in
                        KeyframeTrack {
                            for x in [-10.0, 9, -6, 4, 0] { SpringKeyframe(x, duration: 0.07) }
                        }
                    }
                    .sensoryFeedback(.error, trigger: misses)
                    Button(action: unlock) {
                        HStack(spacing: 8) {
                            if busy { ProgressView().tint(Color.onAccent) }
                            Text(busy ? "Unlocking…" : "Unlock")
                        }
                        .font(.notesButton)
                        .foregroundStyle(Color.onAccent)
                        .frame(maxWidth: .infinity, minHeight: 50)
                        .background(Color.accent, in: .capsule)
                    }
                    .buttonStyle(PressScaleStyle(scale: 0.97))
                    .disabled(busy)
                    if graph.biometrics.enabled {
                        BiometricButton(kind: graph.biometry, action: useBiometrics).disabled(busy)
                    }
                }
            }
            .padding(.horizontal, 32)
            .frame(maxWidth: 480)
            .frame(maxWidth: .infinity)
        }
        .onAppear {
            // The keyboard waits while Face ID is about to ask.
            if !unreadable && (prompted || !graph.biometrics.enabled) { focused = true }
        }
        // Asked once the app is in front (Face ID can't ask before), and again each time it comes back.
        // Face ID's own prompt makes the app inactive for a moment; that isn't coming back.
        .task(id: scenePhase) {
            if scenePhase == .background { prompted = false }
            guard scenePhase == .active, !prompted, !unreadable, graph.biometrics.enabled else { return }
            prompted = true
            useBiometrics()
        }
    }

    private func unlock() {
        guard !busy, !password.isEmpty else { return }
        busy = true
        Task {
            let outcome = await locks.unlocking(password)
            busy = false
            switch outcome {
            case .done: graph.biometrics.offer()
            case .wrongPassword:
                error = "Wrong password."
                password = ""
                misses += 1
            case .unsupported: error = outcome.message
            // The password may be right: it stays, to try again.
            default: error = LockSession.unreadKeyring
            }
        }
    }

    private func useBiometrics() {
        guard !busy else { return }
        busy = true
        error = nil
        // The keyboard would show under the prompt.
        focused = false
        Task {
            let outcome = await graph.biometrics.unlock()
            busy = false
            if let message = outcome.message(graph.biometry) {
                error = message
            }
            if outcome != .done { focused = true }
        }
    }
}

extension BiometricUnlock.Outcome {
    /// What the unlock screen says when Face ID didn't open locked notes; nil when there's nothing to say.
    func message(_ kind: BiometryKind) -> String? {
        switch self {
        case .done, .cancelled: nil
        case .stale:
            "\(kind.name) unlock is off: the lock password or your \(kind.name) changed. Use the password."
        case .needsUnlock, .failed: "\(kind.name) didn’t work. Use the password."
        }
    }
}

/// "Use Face ID": the second way in, under Unlock.
struct BiometricButton: View {
    let kind: BiometryKind
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Label("Use \(kind.name)", systemImage: kind.symbol)
                .font(.notesButton)
                .foregroundStyle(Color(Palette.link))
                .frame(maxWidth: .infinity, minHeight: 48)
                .contentShape(.capsule)
        }
        .buttonStyle(PressScaleStyle(scale: 0.97))
    }
}

/// Locking a note while locked notes are locked (or there's no lock password
/// yet): unlock with the password (or Face ID), or create one. Settings uses
/// it to unlock before turning Face ID on (`unlockFor`). `done` runs once
/// unlocked; `typed` says it was with the password, so Face ID can be offered.
struct LockSheet: View {
    @Environment(AppGraph.self) private var graph
    let locks: LockSession
    /// Whether a lock password exists already.
    let exists: Bool
    /// Unlocking for something other than locking a note: the sheet's title.
    var unlockFor: String?
    let done: (_ typed: Bool) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var password = ""
    @State private var confirm = ""
    @State private var errors: [String: String] = [:]
    @State private var busy = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    SecureField("Lock password", text: $password)
                        .textContentType(exists ? .password : .newPassword)
                        .frame(minHeight: 44)
                        .contentShape(.rect)
                        .onChange(of: password) { errors = [:] }
                    if let e = errors["password"] { Text(e).font(.notesSmall).foregroundStyle(Color.errInk) }
                    if exists && unlockFor == nil && graph.biometrics.enabled {
                        Button("Use \(graph.biometry.name)", systemImage: graph.biometry.symbol, action: useBiometrics)
                            .frame(minHeight: 44)
                            .disabled(busy)
                    }
                    if !exists {
                        SecureField("Confirm password", text: $confirm)
                            .textContentType(.newPassword)
                            .frame(minHeight: 44)
                            .contentShape(.rect)
                            .onChange(of: confirm) { errors["confirm"] = nil }
                        if let e = errors["confirm"] { Text(e).font(.notesSmall).foregroundStyle(Color.errInk) }
                    }
                } footer: {
                    Text(
                        unlockFor != nil
                            ? "Enter the lock password your locked notes share."
                            : exists
                                ? "Locked notes share one lock password, made the first time a note was locked on any of your devices. Enter it to lock this note."
                                : "Locked notes are encrypted on your devices; the server can’t read them. Every locked note uses this password. If you forget it, those notes can’t be recovered."
                    )
                    .font(.notesSmall).foregroundStyle(Color.ink2)
                }
                if let e = errors["form"] {
                    Text(e).font(.notesSmall).foregroundStyle(Color.errInk)
                }
            }
            .font(.notesBody)
            .scrollContentBackground(.hidden)
            .background(Color.notesBg)
            .navigationTitle(unlockFor ?? (exists ? "Lock this note" : "Create a lock password"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(role: .cancel) { dismiss() }.disabled(busy)
                }
                ToolbarItem(placement: .confirmationAction) {
                    if busy {
                        ProgressView().accessibilityLabel(exists ? "Unlocking…" : "Creating…")
                    } else {
                        Button(
                            unlockFor != nil ? "Unlock" : exists ? "Lock" : "Create and lock",
                            systemImage: unlockFor != nil ? "lock.open" : "lock", action: submit
                        )
                        .buttonStyle(.glassProminent)
                    }
                }
            }
        }
        .tint(Color(Palette.link))
        .interactiveDismissDisabled(busy)
    }

    private func submit() {
        if exists {
            busy = true
            Task {
                let outcome = await locks.unlocking(password)
                busy = false
                switch outcome {
                case .done:
                    dismiss()
                    done(true)
                case .wrongPassword: errors = ["password": "Wrong password."]
                case .unsupported: errors = ["password": outcome.message ?? ""]
                default: errors = ["password": LockSession.unreadKeyring]
                }
            }
            return
        }
        var found: [String: String] = [:]
        if password.count < minLockPassword {
            found["password"] = "Use at least \(minLockPassword) characters."
        }
        if confirm != password { found["confirm"] = "The passwords don’t match." }
        errors = found
        guard found.isEmpty else { return }
        busy = true
        Task {
            let outcome = await locks.setup(password)
            busy = false
            if outcome == .done {
                dismiss()
                done(true)
            } else {
                errors = ["form": outcome.message ?? ""]
            }
        }
    }

    private func useBiometrics() {
        busy = true
        errors = [:]
        Task {
            let outcome = await graph.biometrics.unlock()
            busy = false
            if outcome == .done {
                dismiss()
                done(false)
            } else if let message = outcome.message(graph.biometry) {
                errors = ["password": message]
            }
        }
    }
}

/// Covers a locked note while the app isn't in front, so the app switcher's snapshot doesn't show it.
struct PrivacyCover: View {
    var body: some View {
        ZStack {
            Color.notesBg
            Image(systemName: "lock.fill").font(.system(size: 34)).foregroundStyle(Color.accentDeep)
        }
        .ignoresSafeArea()
        .accessibilityHidden(true)
    }
}

/// The lock sheet to show: unlock with the existing password, or create one.
struct LockPrompt: Identifiable {
    var exists: Bool

    var id: Bool { exists }
}
