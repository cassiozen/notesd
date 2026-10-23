import SwiftUI

/// Connecting to a server: its address, then a new account (a fresh server)
/// or a sign-in. A sheet: the app works without a server until then.
struct OnboardingView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var model: OnboardingModel

    init(model: OnboardingModel) {
        _model = State(initialValue: model)
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    header
                    fields.disabled(model.busy)
                    if let error = model.error {
                        Text(error).font(.notesSmall).foregroundStyle(Color.errInk)
                            .padding(12)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .background(Color.errBg, in: .rect(cornerRadius: 12))
                    }
                    Button(action: submit) {
                        HStack {
                            Spacer()
                            if model.busy { ProgressView().tint(Color.onAccent) } else { Text(buttonTitle) }
                            Spacer()
                        }
                        .font(.notesButton)
                        .foregroundStyle(Color.onAccent)
                        .frame(minHeight: 50)
                        .background(Color.accent, in: .capsule)
                    }
                    .buttonStyle(PressScaleStyle(scale: 0.97))
                    .disabled(model.busy)
                }
                .padding(24)
            }
            .scrollDismissesKeyboard(.interactively)
            .background(Color.notesBg)
            .toolbar {
                if model.step != .server && (model.lockedServer == nil || model.step != .signIn) {
                    ToolbarItem(placement: .topBarLeading) {
                        Button("Back", systemImage: "chevron.left") { _ = model.back() }.disabled(model.busy)
                    }
                }
                ToolbarItem(placement: .cancellationAction) { Button(role: .cancel) { dismiss() } }
            }
            .onChange(of: model.done) { _, done in if done { dismiss() } }
            .onDisappear { model.cancel() }
        }
        // The accent is too light for text; link brown passes contrast, in the bar too.
        .tint(Color(Palette.link))
    }

    @ViewBuilder private var header: some View {
        VStack(alignment: .leading, spacing: 8) {
            if model.step == .server {
                HStack(spacing: 10) {
                    BrandMark(size: 36)
                    Text("notesd").font(.notesBrand).foregroundStyle(Color.ink)
                }
                .padding(.bottom, 12)
            }
            Text(heading).font(.notesHeading).foregroundStyle(Color.ink)
            Text(lede).font(.notesLede).foregroundStyle(Color.ink2)
        }
    }

    private var heading: String {
        switch model.step {
        case .server: "Connect to your server"
        case .account: "Create your account"
        case .signIn: model.lockedServer == nil ? "Sign in" : "Sign in again"
        }
    }

    private var lede: String {
        switch model.step {
        case .server: "Your notes live on this iPhone and sync with your notesd server whenever it can reach it."
        case .account: "\(model.server) has no account yet. Yours will be the first."
        case .signIn: "To \(model.server)."
        }
    }

    private var buttonTitle: String {
        switch model.step {
        case .server: "Continue"
        case .account: "Create account"
        case .signIn: "Sign in"
        }
    }

    @ViewBuilder private var fields: some View {
        switch model.step {
        case .server:
            LabeledField(label: "Server address", error: model.fieldErrors[.server]) {
                TextField("notes.example.com", text: $model.server)
                    .keyboardType(.URL)
                    .textContentType(.URL)
                    .font(.notesMono)
                    .onSubmit(submit)
            }
        case .account:
            LabeledField(label: "Username", error: model.fieldErrors[.username]) {
                TextField("Username", text: $model.username).textContentType(.username)
                    .disabled(model.lockedUsername != nil)
            }
            LabeledField(label: "Password", error: model.fieldErrors[.password]) {
                SecureField("At least 8 characters", text: $model.password).textContentType(.newPassword)
            }
            LabeledField(label: "Confirm password", error: model.fieldErrors[.confirm]) {
                SecureField("Again", text: $model.confirm).textContentType(.newPassword).onSubmit(submit)
            }
        case .signIn:
            LabeledField(label: "Username", error: model.fieldErrors[.username]) {
                // These notes are this person's; someone else removes all data first.
                TextField("Username", text: $model.username).textContentType(.username)
                    .disabled(model.lockedUsername != nil)
            }
            LabeledField(label: "Password", error: model.fieldErrors[.password]) {
                SecureField("Password", text: $model.password).textContentType(.password).onSubmit(submit)
            }
        }
    }

    private func submit() {
        switch model.step {
        case .server: model.submitServer()
        case .account: model.submitAccount()
        case .signIn: model.submitSignIn()
        }
    }
}

private struct LabeledField<Content: View>: View {
    let label: String
    let error: String?
    @ViewBuilder let content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(label).font(.notesLabel).foregroundStyle(Color.ink2)
            content
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .font(.notesBody)
                .padding(.horizontal, 14)
                .frame(minHeight: 48)
                .background(Color.field, in: .rect(cornerRadius: 12))
                .overlay(RoundedRectangle(cornerRadius: 12).stroke(error == nil ? Color.hair : Color.danger))
                .accessibilityLabel(label)
            if let error { Text(error).font(.notesSmall).foregroundStyle(Color.danger) }
        }
    }
}
