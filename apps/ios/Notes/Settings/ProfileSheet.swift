import NotesCore
import PhotosUI
import SwiftUI

/// Someone's photo, or the first letter of `name` without one. A photo that can't be had (offline, never downloaded)
/// shows the letter. `preview`: a photo not yet saved.
struct AvatarView: View {
    let profile: Profile
    let name: String
    var preview: UIImage?
    var size: CGFloat = 44
    @Environment(AppGraph.self) private var graph
    @State private var photo: UIImage?

    var body: some View {
        ZStack {
            Circle().fill(Color.on)
            if let image = preview ?? photo ?? graph.avatars.cached(profile) {
                Image(uiImage: image).resizable().scaledToFill()
            } else {
                Text(initial).font(.system(size: size * 0.42, weight: .medium)).foregroundStyle(Color.ink)
            }
        }
        .frame(width: size, height: size)
        .clipShape(Circle())
        .accessibilityHidden(true)
        .task(id: profile) { photo = await graph.avatars.load(profile) }
    }

    private var initial: String {
        name.trimmingCharacters(in: .whitespaces).first.map { String($0).uppercased() } ?? "?"
    }
}

/// Name and photo: optional, edited from Server and never asked for anywhere else once signed in.
struct ProfileSheet: View {
    let server: String
    let username: String
    @Environment(AppGraph.self) private var graph
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    /// What the draft started from: a refresh while open mustn't turn an untouched name into a change.
    @State private var startName = ""
    @State private var preparing = false
    @State private var picked: PhotosPickerItem?
    @State private var photo: Photo?
    @State private var nameError: String?
    @State private var photoError: String?
    @State private var formError: String?
    @State private var busy = false

    /// A photo chosen here, not yet saved; or the saved one to be removed.
    private enum Photo {
        case chosen(Data, UIImage)
        case remove
    }

    var body: some View {
        let profile = graph.profile
        KeyringSheet(
            title: "Name and Photo", action: "Save", busy: busy, waiting: preparing,
            footer: "Both are optional. Admins on this server see them when they manage people. Your username, "
                + "\(username), is still what you sign in with.",
            formError: formError, submit: { save(profile) }
        ) {
            HStack(spacing: 16) {
                AvatarView(
                    profile: removing ? Profile(userId: profile.userId) : profile,
                    name: name.trimmingCharacters(in: .whitespaces).isEmpty ? username : name,
                    preview: chosenImage, size: 72)
                VStack(alignment: .leading, spacing: 10) {
                    PhotosPicker(
                        hasPhoto(profile) ? "Change Photo…" : "Choose Photo…", selection: $picked, matching: .images,
                        preferredItemEncoding: .current)
                    if hasPhoto(profile) {
                        Button("Remove Photo", role: .destructive) { photo = profile.avatar.isEmpty ? nil : .remove }
                            .foregroundStyle(Color.danger)
                    }
                }
                // Each its own target: in a Form row, any tap would otherwise press them all.
                .buttonStyle(.borderless)
                .disabled(busy || preparing)
            }
            .padding(.vertical, 4)
            if let photoError { Text(photoError).font(.notesSmall).foregroundStyle(Color.errInk) }
            TextField("Name", text: $name)
                .textContentType(.name)
                .disabled(busy)
                .accessibilityLabel("Name")
            if let nameError { Text(nameError).font(.notesSmall).foregroundStyle(Color.errInk) }
        }
        .onAppear {
            startName = graph.profile.displayName
            name = startName
        }
        .onChange(of: picked) { _, item in
            guard let item else { return }
            picked = nil
            photoError = nil
            preparing = true
            Task {
                defer { preparing = false }
                do throws(ImageError) {
                    guard let data = try? await item.loadTransferable(type: Data.self) else {
                        throw ImageError("That picture couldn’t be read.")
                    }
                    let jpeg = try PrepareImage.photo(data)
                    guard let image = UIImage(data: jpeg) else { throw ImageError("That picture couldn’t be read.") }
                    photo = .chosen(jpeg, image)
                } catch {
                    photoError = error.message
                }
            }
        }
    }

    private var removing: Bool {
        if case .remove = photo { true } else { false }
    }

    private var chosenImage: UIImage? {
        if case .chosen(_, let image) = photo { image } else { nil }
    }

    private func hasPhoto(_ profile: Profile) -> Bool {
        switch photo {
        case .chosen: true
        case .remove: false
        case nil: !profile.avatar.isEmpty
        }
    }

    private func save(_ before: Profile) {
        guard !busy, !preparing, let client = graph.accounts.client() else { return }
        busy = true
        nameError = nil
        photoError = nil
        formError = nil
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let photo = photo
        Task {
            // What was saved before a later step failed still shows.
            var saved: Profile?
            var failed: ApiResult<Profile>?
            func step(_ result: ApiResult<Profile>) {
                if case .ok(let profile) = result { saved = profile } else { failed = result }
            }
            if trimmed != startName { step(await client.setDisplayName(trimmed)) }
            if failed == nil {
                switch photo {
                case .chosen(let jpeg, _):
                    step(await client.setAvatar(jpeg))
                    if failed == nil, let saved { await graph.avatars.put(jpeg, for: saved) }
                case .remove:
                    step(await client.removeAvatar())
                    if failed == nil { await graph.avatars.forget(before.userId) }
                case nil: break
                }
            }
            if let saved { graph.accounts.profileChanged(saved, server: server, username: username) }
            busy = false
            switch failed {
            case nil: dismiss()
            case .rejected(400, _, _, "displayName"?): nameError = "Use at most 64 characters, on one line."
            case .rejected(400, _, _, "avatar"?), .rejected(413, _, _, _), .rejected(415, _, _, _):
                photoError = "The server didn’t take that photo. Try another."
            case .unreachable: formError = "Can’t reach the server."
            default: formError = "Something went wrong. Try again."
            }
        }
    }
}
