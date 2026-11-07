import NotesCore
import SwiftUI

/// The Integrations row's value in the list: off, or what's connected.
func integrationsValue(_ email: EmailSettings?) -> String {
    guard let email else { return "" }
    return email.configured ? "Resend" : "Off"
}

/// What's connected, then what can be; each connects in its own sheet. Resend is the only one so far.
struct IntegrationsSection: View {
    @Environment(AppGraph.self) private var graph
    @State private var failed = false
    @State private var connecting = false
    @State private var managing = false

    var body: some View {
        Group {
            if let saved = graph.email.settings {
                Section {
                    // One row either way: connecting changes it from Connect to Manage, and its sheet (the
                    // connect flow's last step) must stay up through that.
                    row(saved)
                } header: {
                    SectionHeader(saved.configured ? "Connected" : "Available")
                } footer: {
                    if !saved.editable && !saved.configured { Footnote("An admin can connect it.") }
                }
            } else if failed {
                Section {
                    Text("Couldn’t load integrations.").foregroundStyle(Color.errInk)
                    Button("Try again") { Task { await load() } }
                }
            } else {
                Section { Text("Loading…").foregroundStyle(Color.ink2) }
            }
        }
        .task { await load() }
    }

    private func load() async {
        failed = false
        failed = await !graph.email.refresh() && graph.email.settings == nil
    }

    @ViewBuilder
    private func row(_ saved: EmailSettings) -> some View {
        let detail =
            saved.configured ? "Sending as \(saved.from)" : "Email notes to anyone, from an address on your own domain."
        let action = saved.configured ? "Manage" : "Connect"
        let content = HStack(spacing: 14) {
            ResendMark()
            VStack(alignment: .leading, spacing: 2) {
                Text("Resend").font(.notesRowTitle).foregroundStyle(Color.ink)
                Text(detail).font(.notesSmall).foregroundStyle(Color.ink2)
            }
            Spacer(minLength: 8)
            if saved.editable {
                Text(action).font(.notesButton).foregroundStyle(Color(Palette.link))
            } else if saved.configured {
                Text("On").font(.notesLabel).foregroundStyle(Color.good)
                    .padding(.horizontal, 10).padding(.vertical, 4)
                    .background(Color.goodBg, in: .capsule)
            }
        }
        .padding(.vertical, 4)
        if saved.editable {
            // On the row, not the section: a sheet on a Form's section doesn't present.
            Button {
                if saved.configured { managing = true } else { connecting = true }
            } label: {
                content
            }
            .accessibilityLabel("\(action) Resend")
            .accessibilityHint(detail)
            .sheet(isPresented: $connecting) { ResendConnect() }
            .sheet(isPresented: $managing) {
                if let saved = graph.email.settings { ResendManage(saved: saved) }
            }
        } else {
            content.accessibilityElement(children: .combine)
        }
    }
}

private struct ResendMark: View {
    var body: some View {
        RoundedRectangle(cornerRadius: 11)
            .fill(Color.ink)
            .frame(width: 40, height: 40)
            .overlay { Image(systemName: "envelope").foregroundStyle(Color.notesBg) }
            .accessibilityHidden(true)
    }
}

private let apiKeys = URL(string: "https://resend.com/api-keys")!

/// The server's refusal, on the field it's about: "apiKey" or "from"; anything else is the form's.
private func refusal<T>(_ result: ApiResult<T>) -> [String: String] {
    switch result {
    case .rejected(400, _, let text, let field?) where field == "apiKey" || field == "from":
        let s = text.prefix(1).uppercased() + text.dropFirst()
        return [field: s.hasSuffix(".") ? s : s + "."]
    case .unreachable: return ["form": "Can’t reach the server."]
    default: return ["form": "Something went wrong. Try again."]
    }
}

/// Key, then sender, then done. Nothing's saved until the sender's chosen.
private struct ResendConnect: View {
    private enum Step { case key, sender, done }

    @Environment(AppGraph.self) private var graph
    @Environment(\.dismiss) private var dismiss
    @State private var step = Step.key
    @State private var apiKey = ""
    @State private var name = ""
    @State private var address = ""
    @State private var errors: [String: String] = [:]
    @State private var busy = false
    @State private var landed = false
    @FocusState private var keyFocused: Bool

    var body: some View {
        NavigationStack {
            Form {
                switch step {
                case .key: key
                case .sender:
                    SenderFields(name: $name, address: $address, error: errors["from"], busy: busy)
                    if let e = errors["form"] { Text(e).font(.notesSmall).foregroundStyle(Color.errInk) }
                case .done: done
                }
            }
            .font(.notesBody)
            .scrollContentBackground(.hidden)
            .background(Color.notesBg)
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { toolbar }
        }
        .tint(Color(Palette.link))
        .interactiveDismissDisabled(busy)
        .onAppear { keyFocused = true }
    }

    private var title: String {
        switch step {
        case .key: "Connect Resend"
        case .sender: "Who It’s From"
        case .done: "Ready to Send"
        }
    }

    @ViewBuilder private var key: some View {
        Section {
            SecureField("API key", text: $apiKey, prompt: Text(verbatim: "re_…"))
                .font(.notesMono)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .focused($keyFocused)
                .frame(minHeight: 44)
                .accessibilityLabel("API key")
                .onChange(of: apiKey) { old, new in
                    errors = [:]
                    // A whole key pasted in needs no Continue.
                    if old.isEmpty, new.count > 1, Sender.keyProblem(new.trimmed) == nil { step = .sender }
                }
            if let e = errors["apiKey"] { Text(e).font(.notesSmall).foregroundStyle(Color.errInk) }
        } header: {
            SectionHeader("Step 1 of 2")
        } footer: {
            VStack(alignment: .leading, spacing: 6) {
                Footnote("Notes are sent through your Resend account, with an API key from it that has sending access.")
                Link("Create an API key in Resend", destination: apiKeys).font(.notesSmall)
            }
        }
    }

    @ViewBuilder private var done: some View {
        Section {
            VStack(spacing: 14) {
                Image(systemName: "paperplane.fill")
                    .font(.system(size: 30))
                    .foregroundStyle(Color.good)
                    .frame(width: 76, height: 76)
                    .background(Color.goodBg, in: .circle)
                    .symbolEffect(.bounce, value: landed)
                    .accessibilityHidden(true)
                Text("Anyone on this server can now email a note: choose Email… from its ••• menu.")
                    .multilineTextAlignment(.center)
                    .foregroundStyle(Color.ink2)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 12)
            .onAppear { landed = true }
        }
    }

    @ToolbarContentBuilder private var toolbar: some ToolbarContent {
        ToolbarItem(placement: .cancellationAction) {
            switch step {
            case .key: Button("Cancel", role: .cancel) { dismiss() }
            case .sender: Button("Back") { step = .key }.disabled(busy)
            case .done: EmptyView()
            }
        }
        ToolbarItem(placement: .confirmationAction) {
            switch step {
            case .key: Button("Continue", action: next)
            case .sender:
                if busy { ProgressView() } else { Button("Connect", action: connect).buttonStyle(.glassProminent) }
            case .done: Button("Done") { dismiss() }.buttonStyle(.glassProminent)
            }
        }
    }

    private func next() {
        let problem = Sender.keyProblem(apiKey.trimmed)
        errors = problem.map { ["apiKey": $0] } ?? [:]
        if problem == nil { step = .sender }
    }

    private func connect() {
        guard !address.trimmed.isEmpty else {
            errors = ["from": "Enter the address emails come from."]
            return
        }
        errors = [:]
        busy = true
        Task {
            let result = await graph.email.save(
                from: Sender.format(name: name, address: address), apiKey: apiKey.trimmed)
            busy = false
            if case .ok = result {
                step = .done
            } else {
                errors = refusal(result)
                if errors["apiKey"] != nil { step = .key }
            }
        }
    }
}

/// The sender and key as saved; a new key replaces the old one, an empty field keeps it.
private struct ResendManage: View {
    let saved: EmailSettings
    @Environment(AppGraph.self) private var graph
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var address = ""
    @State private var apiKey = ""
    @State private var errors: [String: String] = [:]
    @State private var busy = false
    @State private var confirmDisconnect = false

    var body: some View {
        NavigationStack {
            Form {
                SenderFields(name: $name, address: $address, error: errors["from"], busy: busy)
                Section {
                    SecureField(
                        "API key", text: $apiKey, prompt: Text(verbatim: "Saved · …\(saved.keyHint)")
                    )
                    .font(.notesMono)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .frame(minHeight: 44)
                    .accessibilityLabel("API key")
                    .onChange(of: apiKey) { errors["apiKey"] = nil }
                    if let e = errors["apiKey"] { Text(e).font(.notesSmall).foregroundStyle(Color.errInk) }
                } footer: {
                    Footnote("Paste a new key to replace the saved one.")
                }
                if let e = errors["form"] { Text(e).font(.notesSmall).foregroundStyle(Color.errInk) }
                Section {
                    Button("Disconnect Resend", role: .destructive) { confirmDisconnect = true }
                        .foregroundStyle(Color.danger)
                        .disabled(busy)
                }
            }
            .font(.notesBody)
            .scrollContentBackground(.hidden)
            .background(Color.notesBg)
            .navigationTitle("Resend")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel", role: .cancel) { dismiss() }.disabled(busy)
                }
                ToolbarItem(placement: .confirmationAction) {
                    if busy { ProgressView() } else { Button("Save", action: save).buttonStyle(.glassProminent) }
                }
            }
            .confirmationDialog("Disconnect Resend?", isPresented: $confirmDisconnect, titleVisibility: .visible) {
                Button("Disconnect", role: .destructive, action: disconnect)
            } message: {
                Text("Nobody on this server can email notes until it’s connected again.")
            }
        }
        .tint(Color(Palette.link))
        .interactiveDismissDisabled(busy)
        .onAppear {
            let parts = Sender.parse(saved.from)
            name = parts.name
            address = parts.address
        }
    }

    private func save() {
        var found: [String: String] = [:]
        if !apiKey.trimmed.isEmpty, let problem = Sender.keyProblem(apiKey.trimmed) { found["apiKey"] = problem }
        if address.trimmed.isEmpty { found["from"] = "Enter the address emails come from." }
        errors = found
        guard found.isEmpty else { return }
        busy = true
        Task {
            let key = apiKey.trimmed
            let result = await graph.email.save(
                from: Sender.format(name: name, address: address), apiKey: key.isEmpty ? nil : key)
            busy = false
            if case .ok = result { dismiss() } else { errors = refusal(result) }
        }
    }

    private func disconnect() {
        busy = true
        Task {
            let result = await graph.email.remove()
            busy = false
            if case .ok = result { dismiss() } else { errors = refusal(result) }
        }
    }
}

/// Name and address, and how they'll look in a recipient's inbox as they're typed.
private struct SenderFields: View {
    @Binding var name: String
    @Binding var address: String
    let error: String?
    let busy: Bool

    var body: some View {
        Section {
            InboxPreview(name: name.trimmed, address: address.trimmed)
        } header: {
            SectionHeader("In their inbox")
        }
        Section {
            TextField("Name", text: $name, prompt: Text(verbatim: "Your name"))
                .textContentType(.name)
                .frame(minHeight: 44)
                .accessibilityLabel("Name")
            VStack(alignment: .leading, spacing: 4) {
                TextField("Address", text: $address, prompt: Text(verbatim: "notes@example.com"))
                    .keyboardType(.emailAddress)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .frame(minHeight: 44)
                    .accessibilityLabel("Address")
                if let error { Text(error).font(.notesSmall).foregroundStyle(Color.errInk) }
            }
        } footer: {
            Footnote("The name is optional. The address must be on a domain you’ve verified in Resend.")
        }
        .disabled(busy)
    }
}

private struct InboxPreview: View {
    let name: String
    let address: String

    var body: some View {
        let shown = name.isEmpty ? (address.isEmpty ? "Your name" : address) : name
        HStack(alignment: .top, spacing: 12) {
            Text(shown.prefix(1).uppercased())
                .font(.notesTitle)
                .foregroundStyle(Color.ink)
                .frame(width: 36, height: 36)
                .background(Color.on, in: .circle)
            VStack(alignment: .leading, spacing: 2) {
                HStack(alignment: .firstTextBaseline, spacing: 6) {
                    Text(shown).font(.notesLabel).fontWeight(.semibold).foregroundStyle(Color.ink).lineLimit(1)
                    if !name.isEmpty, !address.isEmpty {
                        Text(address).font(.notesSmall).foregroundStyle(Color.ink3).lineLimit(1)
                    }
                    Spacer(minLength: 4)
                    Text("now").font(.notesSmall).foregroundStyle(Color.ink3)
                }
                Text("Saturday market").font(.notesLabel).foregroundStyle(Color.ink)
                Text("Sourdough, ripe tomatoes and something green. Ask about the late plums.")
                    .font(.notesSmall).foregroundStyle(Color.ink3).lineLimit(1)
            }
        }
        .padding(.vertical, 6)
        // A picture of what's typed below; the fields say it already.
        .accessibilityHidden(true)
    }
}
