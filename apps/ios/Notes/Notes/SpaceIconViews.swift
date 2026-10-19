import SwiftUI

extension SpaceIcon {
    private static let byKey = Dictionary(uniqueKeysWithValues: all.map { ($0.key, $0) })

    /// The icon for `key`: the default one for "" or a key this app doesn't have.
    static func named(_ key: String) -> SpaceIcon {
        byKey[key] ?? byKey[defaultKey] ?? all[0]
    }
}

/// A space's icon (§7.7), in its own colours. Decorative.
struct SpaceIconImage: View {
    let icon: String
    var size: CGFloat = 24

    var body: some View {
        Image(SpaceIcon.named(icon).asset)
            .resizable()
            .scaledToFit()
            .frame(width: size, height: size)
            .accessibilityHidden(true)
    }
}

/// Every icon to choose from, the chosen one ringed.
struct SpaceIconPicker: View {
    @Binding var selection: String?

    private let columns = [GridItem(.adaptive(minimum: 44), spacing: 4)]

    var body: some View {
        LazyVGrid(columns: columns, spacing: 4) {
            ForEach(SpaceIcon.all) { icon in
                let on = icon.key == selection
                Button {
                    selection = icon.key
                } label: {
                    Image(icon.asset)
                        .resizable()
                        .scaledToFit()
                        .frame(width: 30, height: 30)
                        .frame(maxWidth: .infinity, minHeight: 44)
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
