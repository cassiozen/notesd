import SwiftUI

extension SpaceIcon {
    /// The app's own icon: the default space's to begin with, and shown for a
    /// key this app doesn't have.
    static let app = SpaceIcon(key: "", name: "App icon")

    /// What the picker offers: the app's icon, then the set.
    static let choices = [app] + all

    private static let byKey = Dictionary(uniqueKeysWithValues: all.map { ($0.key, $0) })

    static func named(_ key: String) -> SpaceIcon { byKey[key] ?? app }

    /// The picture: the app's mark (inset to sit among the others), or the asset `space-<key>`.
    var image: Image { Image(key.isEmpty ? "space-app" : asset) }
}

/// A space's icon (§7.7), in its own colours. Decorative.
struct SpaceIconImage: View {
    let icon: String
    var size: CGFloat = 24

    var body: some View {
        SpaceIcon.named(icon).image
            .resizable()
            .interpolation(.high)
            .scaledToFit()
            .frame(width: size, height: size)
            .accessibilityHidden(true)
    }
}

/// Every icon to choose from, the chosen one ringed.
struct SpaceIconPicker: View {
    @Binding var selection: String?

    private let columns = [GridItem(.adaptive(minimum: 54), spacing: 4)]

    var body: some View {
        LazyVGrid(columns: columns, spacing: 4) {
            ForEach(SpaceIcon.choices) { icon in
                let on = icon.key == selection
                Button {
                    selection = icon.key
                } label: {
                    icon.image
                        .resizable()
                        .interpolation(.high)
                        .scaledToFit()
                        .frame(width: 38, height: 38)
                        .frame(maxWidth: .infinity, minHeight: 54)
                        .background(on ? Color.chip : .clear, in: .rect(cornerRadius: 10))
                        .overlay {
                            if on { RoundedRectangle(cornerRadius: 10).strokeBorder(Color.notesTint, lineWidth: 2) }
                        }
                        .contentShape(.rect)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(icon.name)
                .accessibilityAddTraits(on ? .isSelected : [])
            }
        }
        .padding(.vertical, 4)
    }
}
