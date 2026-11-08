import NotesCore
import SwiftUI

/// What this app knows of a trait (§7.8): its glyph, and what Space info offers for it.
struct TraitKind: Identifiable {
    let key: String
    /// Its name while it's off; on, the server's label is shown.
    let label: String
    /// An SF Symbol, drawn in one colour.
    let symbol: String
    /// Its settings, opened from its Space info row while it's on; nil for none.
    var settings: (@MainActor (Space) -> AnyView)?
    /// Turns it on from Space info; nil while the feature can't be.
    var turnOn: (@MainActor (Space) -> Void)?

    var id: String { key }
}

/// A row of Space info's traits section.
enum TraitRow: Identifiable {
    /// On: the server's trait, with what this app knows of it (nil for a key it doesn't).
    case on(SpaceTrait, TraitKind?)
    /// Off, and the feature can be turned on.
    case off(TraitKind)

    var id: String {
        switch self {
        case .on(let trait, _): trait.key
        case .off(let kind): kind.key
        }
    }
}

enum Traits {
    /// Every key this app knows, in the fixed order (§7.8): reserved here before its feature ships.
    static let registry = [
        TraitKind(key: "shared", label: "Shared", symbol: "person.2"),
        TraitKind(key: "published", label: "Published", symbol: "globe"),
        TraitKind(key: "agent", label: "Agent", symbol: "sparkles"),
    ]

    /// For a key a newer server sends that this app doesn't know.
    static let unknownSymbol = "puzzlepiece.extension"

    static func kind(_ key: String, in registry: [TraitKind] = registry) -> TraitKind? {
        registry.first { $0.key == key }
    }

    static func symbol(_ key: String) -> String { kind(key)?.symbol ?? unknownSymbol }

    static func label(_ trait: SpaceTrait) -> String {
        if !trait.label.isEmpty { return trait.label }
        return kind(trait.key)?.label ?? trait.key
    }

    /// What the glyphs say, for VoiceOver: "Shared, Published: Publishing failed".
    static func spoken(_ traits: [SpaceTrait]) -> String {
        traits.map { t in t.problem.map { "\(label(t)): \($0)" } ?? label(t) }.joined(separator: ", ")
    }

    /// Space info's rows: each known trait in order, on, or off when it can be turned on; then those on that
    /// this app doesn't know, as the server sent them.
    static func rows(_ traits: [SpaceTrait], registry: [TraitKind] = registry) -> [TraitRow] {
        let known: [TraitRow] = registry.compactMap { kind in
            if let on = traits.first(where: { $0.key == kind.key }) { return .on(on, kind) }
            return kind.turnOn == nil ? nil : .off(kind)
        }
        let unknown = traits.filter { t in !registry.contains { $0.key == t.key } }.map { TraitRow.on($0, nil) }
        return known + unknown
    }

    /// What moving a note from `current` into `destination` would newly expose it to: the destination's traits
    /// reaching someone the current space doesn't, once per sentence. Empty: the move needn't ask.
    static func newExposure(from current: [SpaceTrait], to destination: [SpaceTrait]) -> [SpaceTrait] {
        let reached = Set(current.flatMap { $0.audience ?? [] })
        // A trait without an audience (one this app predates) can only be compared by its words.
        let said = Set(current.compactMap(\.exposure))
        var seen = Set<String>()
        return destination.filter { t in
            guard let sentence = t.exposure, !seen.contains(sentence) else { return false }
            let audience = t.audience ?? []
            let fresh = audience.isEmpty ? !said.contains(sentence) : audience.contains { !reached.contains($0) }
            if fresh { seen.insert(sentence) }
            return fresh
        }
    }
}

/// A space's trait glyphs, packed in the server's order: tertiary ink, the danger colour for one with a problem.
/// Sized from the font around them, a step smaller than the name.
struct TraitGlyphs: View {
    let traits: [SpaceTrait]
    var spacing: CGFloat = 5

    var body: some View {
        HStack(spacing: spacing) {
            ForEach(traits, id: \.key) { trait in
                Image(systemName: Traits.symbol(trait.key))
                    .foregroundStyle(trait.problem == nil ? Color.ink3 : Color.danger)
            }
        }
        .imageScale(.small)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Traits.spoken(traits))
    }
}

/// Beside the switcher and the list's title: the glyphs grouped in one filled button, to Space info. Nothing
/// for a space without traits.
struct TraitGroup: View {
    let traits: [SpaceTrait]
    let onOpen: () -> Void

    var body: some View {
        if !traits.isEmpty {
            Button(action: onOpen) {
                TraitGlyphs(traits: traits, spacing: 6)
                    .font(.title3)
                    .padding(.vertical, 6)
                    .padding(.horizontal, 8)
                    .background(Color.hover, in: .rect(cornerRadius: 10, style: .continuous))
                    // Drawn smaller than a finger: the tap area keeps the full height.
                    .frame(minHeight: 44)
                    .contentShape(.rect)
            }
            .buttonStyle(PressScaleStyle())
            .accessibilityLabel("Space info: \(Traits.spoken(traits))")
        }
    }
}

/// A space's identity (§7.8): its icon, its name, and its traits' glyphs right after the name. With no
/// traits, the icon and name as they've always been.
struct SpaceLabel: View {
    let space: Space
    var font: Font = .notesRowTitle
    var iconSize: CGFloat = 24
    /// A line under the name.
    var detail: String?

    var body: some View {
        HStack(spacing: 12) {
            SpaceIconImage(icon: space.icon, size: iconSize)
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    Text(space.name).foregroundStyle(Color.ink).lineLimit(1)
                    if !space.traits.isEmpty { TraitGlyphs(traits: space.traits) }
                }
                .font(font)
                if let detail { Text(detail).font(.notesSmall).foregroundStyle(Color.ink2) }
            }
        }
        .accessibilityElement(children: .combine)
    }
}

extension Space {
    var noteCountLabel: String { noteCount == 1 ? "1 note" : "\(noteCount) notes" }

    /// A menu row's subtitle (Move to Space): its traits in words. Menus draw one image per row, so the glyphs can't
    /// follow the name.
    var menuSubtitle: Text? {
        traits.isEmpty ? nil : Text(verbatim: traits.map(Traits.label).joined(separator: ", "))
    }
}
