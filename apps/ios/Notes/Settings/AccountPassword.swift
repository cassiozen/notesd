import NotesCore
import SwiftUI

/// The server's own minimum, checked here first.
private let minAccountPassword = 8

/// The account's password. The server signs out every other session; this iPhone stays signed in.
struct ChangeAccountPassword: View {
    let api: () -> ApiClient?
    let done: () -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var current = ""
    @State private var next = ""
    @State private var confirm = ""
    @State private var errors: [String: String] = [:]
    @State private var busy = false

    var body: some View {
        KeyringSheet(
            title: "Change Password", action: "Change", busy: busy,
            footer: "Every other browser and device is signed out and needs the new password.",
            formError: errors["form"], submit: submit
        ) {
            PasswordField(label: "Current password", text: $current, error: errors["current"])
            PasswordField(label: "New password", text: $next, error: errors["next"], content: .newPassword)
            PasswordField(
                label: "Confirm new password", text: $confirm, error: errors["confirm"], content: .newPassword)
        }
    }

    private func submit() {
        var found: [String: String] = [:]
        if current.isEmpty { found["current"] = "Enter your current password." }
        if next.count < minAccountPassword {
            found["next"] = "Use at least \(minAccountPassword) characters."
        } else if confirm != next {
            found["confirm"] = "The passwords don’t match."
        }
        errors = found
        guard found.isEmpty, let client = api() else { return }
        busy = true
        Task {
            let result = await client.changePassword(current: current, new: next)
            busy = false
            switch result {
            case .ok:
                done()
                dismiss()
            case .rejected(_, "invalid_credentials", _, _): errors = ["current": "That isn’t your current password."]
            case .rejected(400, _, let text, "password"?):
                errors = ["next": text.prefix(1).uppercased() + text.dropFirst() + "."]
            case .unreachable: errors = ["form": "Can’t reach the server."]
            default: errors = ["form": "Something went wrong. Try again."]
            }
        }
    }
}
