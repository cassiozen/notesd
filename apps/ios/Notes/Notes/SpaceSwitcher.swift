import NotesCore
import SwiftUI

/// An invitation to someone else's space. Nothing provides these yet: the slot is for sharing.
struct SpaceInvite: Identifiable, Hashable {
    let id: String
    let spaceName: String
    let spaceIcon: String
    /// The inviter's display name.
    let from: String
}

/// A line of the switcher: an optional glyph and its text, all in the danger colour for a problem.
struct SwitcherLine: Hashable {
    var symbol: String?
    var text: String
    var problem = false
}

/// A row's second line.
enum SwitcherDetail: Equatable {
    /// What changed there lately, after the trait glyphs.
    case change(glyphs: [SwitcherLine], text: String)
    case traits([SwitcherLine])
    case onlyYou
}

enum Switcher {
    static let onlyYou = "Only you"

    /// The current space's card: a line per trait, in order, its problem or its label and summary.
    static func cardLines(_ space: Space) -> [SwitcherLine] {
        guard !space.traits.isEmpty else { return [SwitcherLine(text: onlyYou)] }
        return space.traits.map { t in
            let symbol = Traits.symbol(t.key)
            if let problem = t.problem { return SwitcherLine(symbol: symbol, text: problem, problem: true) }
            let label = Traits.label(t)
            return SwitcherLine(symbol: symbol, text: t.summary.isEmpty ? label : "\(label) · \(t.summary)")
        }
    }

    /// The spaces to switch to: every one but the current, in the list's order.
    static func others(_ spaces: [Space], current: String) -> [Space] {
        spaces.filter { $0.id != current }
    }

    static func detail(_ space: Space, change: String?) -> SwitcherDetail {
        let glyphs = space.traits.map {
            SwitcherLine(symbol: Traits.symbol($0.key), text: "", problem: $0.problem != nil)
        }
        if let change { return .change(glyphs: glyphs, text: change) }
        if space.traits.isEmpty { return .onlyYou }
        return .traits(
            space.traits.map { t in
                SwitcherLine(
                    symbol: Traits.symbol(t.key), text: t.problem ?? Traits.label(t), problem: t.problem != nil)
            })
    }

    /// What VoiceOver says for a row: its name, then what changed, its traits, or "only you".
    static func spoken(_ space: Space, change: String?) -> String {
        switch detail(space, change: change) {
        case .change(_, let text): "\(space.name), \(text)"
        case .traits(let lines): "\(space.name), " + lines.map(\.text).joined(separator: ", ")
        case .onlyYou: "\(space.name), only you"
        }
    }
}

/// The space switcher (§7.7), as a sheet: the current space, invitations, the spaces to switch to, New Space….
struct SpaceSwitcherSheet: View {
    @Environment(AppGraph.self) private var graph
    var invites: [SpaceInvite] = []
    /// What changed lately, by space id.
    var changes: [String: String] = [:]
    var onJoin: (String) -> Void = { _ in }
    var onDecline: (String) -> Void = { _ in }
    let onSwitch: (String) -> Void
    /// Space info for a space, by id.
    let onInfo: (String) -> Void
    let onCreate: () -> Void

    @State private var contentHeight: CGFloat?
    @State private var bottomInset: CGFloat = 0

    var body: some View {
        let others = Switcher.others(graph.spaces, current: graph.space)
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                if let current = graph.spaces.first(where: { $0.id == graph.space }) { card(current) }
                ForEach(invites) { invite(_: $0) }
                if !others.isEmpty {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Switch to")
                            .textCase(.uppercase)
                            .font(.notesKicker)
                            .foregroundStyle(Color.ink3)
                            .padding(.horizontal, 12)
                            .padding(.top, 4)
                            .padding(.bottom, 2)
                            .accessibilityAddTraits(.isHeader)
                        ForEach(others) { space in
                            SwitcherRow(
                                space: space, change: changes[space.id], onSwitch: { onSwitch(space.id) },
                                onInfo: { onInfo(space.id) })
                        }
                    }
                }
                Divider().overlay(Color.hair)
                Button(action: onCreate) {
                    Label("New Space…", systemImage: "plus")
                        .font(.notesLede)
                        .foregroundStyle(Color.ink)
                        .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                        .padding(.horizontal, 12)
                        .contentShape(.rect)
                }
                .buttonStyle(RowButtonStyle(radius: 10))
            }
            .padding(.horizontal, 16)
            .padding(.top, 24)
            .padding(.bottom, 4)
            .onGeometryChange(for: CGFloat.self) {
                $0.size.height
            } action: {
                contentHeight = $0
            }
        }
        .scrollBounceBehavior(.basedOnSize)
        .onGeometryChange(for: CGFloat.self) {
            $0.safeAreaInsets.bottom
        } action: {
            bottomInset = $0
        }
        // As tall as what's in it (the system caps a height detent at large); large only until it's measured.
        .presentationDetents(contentHeight.map { [.height($0 + bottomInset)] } ?? [.large])
        .presentationDragIndicator(.visible)
    }

    private func card(_ space: Space) -> some View {
        HStack(alignment: .top, spacing: 14) {
            SpaceIconImage(icon: space.icon, size: 40)
            VStack(alignment: .leading, spacing: 4) {
                Text(space.name).font(.notesRowTitle.weight(.semibold)).foregroundStyle(Color.ink)
                    .accessibilityAddTraits(.isHeader)
                ForEach(Switcher.cardLines(space), id: \.self) { line in
                    HStack(alignment: .firstTextBaseline, spacing: 6) {
                        if let symbol = line.symbol {
                            Image(systemName: symbol)
                                .font(.system(size: 13))
                                .foregroundStyle(line.problem ? Color.danger : Color.ink3)
                                .accessibilityHidden(true)
                        }
                        Text(line.text)
                            .font(.notesSmall)
                            .foregroundStyle(line.problem ? Color.danger : Color.ink2)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityElement(children: .combine)
            Button {
                onInfo(space.id)
            } label: {
                HStack(spacing: 3) {
                    Text("Info")
                    Image(systemName: "chevron.right").font(.system(size: 10, weight: .semibold))
                }
                .font(.notesLabel)
                .foregroundStyle(Color.ink2)
                .padding(.horizontal, 9)
                .padding(.vertical, 4)
                .background(Color.on, in: .rect(cornerRadius: 8, style: .continuous))
                .frame(minWidth: 44, minHeight: 44, alignment: .topTrailing)
                .contentShape(.rect)
            }
            .buttonStyle(PressScaleStyle())
            .accessibilityLabel("Space info")
        }
        .padding(.leading, 14)
        .padding(.trailing, 6)
        .padding(.vertical, 12)
        .background(Color.hover, in: .rect(cornerRadius: 12, style: .continuous))
    }

    private func invite(_ invite: SpaceInvite) -> some View {
        HStack(alignment: .top, spacing: 12) {
            SpaceIconImage(icon: invite.spaceIcon, size: 22)
            VStack(alignment: .leading, spacing: 8) {
                Text("\(Text(invite.from).bold()) invited you to \(Text(invite.spaceName).bold())")
                    .font(.notesSmall)
                    .foregroundStyle(Color.ink)
                HStack(spacing: 8) {
                    Button("Join") { onJoin(invite.id) }
                        .buttonStyle(InviteButtonStyle(primary: true))
                        .accessibilityLabel("Join \(invite.spaceName)")
                    Button("Decline") { onDecline(invite.id) }
                        .buttonStyle(InviteButtonStyle(primary: false))
                        .accessibilityLabel("Decline \(invite.spaceName)")
                }
            }
            Spacer(minLength: 0)
        }
        .padding(12)
        // Neutral: a space's accent is kept for the caret, checked tasks, the main button and links.
        .background(Color.chip, in: .rect(cornerRadius: 12, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous).strokeBorder(Color.hair))
    }
}

/// A space to switch to. Tapping switches; holding opens its Space info without switching.
private struct SwitcherRow: View {
    let space: Space
    let change: String?
    let onSwitch: () -> Void
    let onInfo: () -> Void
    @State private var pressed = false

    var body: some View {
        HStack(spacing: 12) {
            SpaceIconImage(icon: space.icon, size: 20)
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    Text(space.name).font(.notesRowTitle).foregroundStyle(Color.ink).lineLimit(1)
                    if change != nil { Circle().fill(Color.ink).frame(width: 7, height: 7) }
                }
                detail.lineLimit(1)
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 12)
        .frame(minHeight: 52)
        .background(pressed ? Color.on : .clear, in: .rect(cornerRadius: 10, style: .continuous))
        .contentShape(.rect)
        .onTapGesture(perform: onSwitch)
        // Not a Button: one would also switch when the hold ends.
        .onLongPressGesture(minimumDuration: 0.4, perform: onInfo) { pressed = $0 }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Switcher.spoken(space, change: change))
        .accessibilityAddTraits(.isButton)
        .accessibilityAction { onSwitch() }
        .accessibilityAction(named: "Space info") { onInfo() }
    }

    private var detail: Text {
        let small = Font.custom("InstrumentSans-Regular", size: 12.5, relativeTo: .caption)
        switch Switcher.detail(space, change: change) {
        case .change(let glyphs, let text):
            let marks = glyphs.reduce(Text(verbatim: "")) { all, g in Text("\(all)\(glyph(g)) ") }
            return Text("\(marks)\(Text(text).foregroundStyle(Color.ink2))").font(small)
        case .traits(let lines):
            let parts = lines.enumerated().map { i, line in
                Text("\(i == 0 ? "" : " · ")\(glyph(line)) \(Text(line.text))")
                    .foregroundStyle(line.problem ? Color.danger : Color.ink3)
            }
            return parts.reduce(Text(verbatim: "")) { all, part in Text("\(all)\(part)") }.font(small)
        case .onlyYou:
            return Text(Switcher.onlyYou).font(small).foregroundStyle(Color.ink3)
        }
    }

    private func glyph(_ line: SwitcherLine) -> Text {
        Text(Image(systemName: line.symbol ?? Traits.unknownSymbol))
            .foregroundStyle(line.problem ? Color.danger : Color.ink3)
    }
}

/// Join (solid) and Decline (quiet), on the invitation's tint.
private struct InviteButtonStyle: ButtonStyle {
    let primary: Bool

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.notesLabel.weight(.semibold))
            .foregroundStyle(primary ? Color(Palette.onSolid) : Color.ink)
            .padding(.horizontal, 14)
            .frame(minHeight: 32)
            .background(primary ? Color(Palette.solid) : Color.on, in: .rect(cornerRadius: 8, style: .continuous))
            .opacity(configuration.isPressed ? 0.7 : 1)
            .frame(minHeight: 44)
            .contentShape(.rect)
    }
}

#if DEBUG
    /// `--fake-switcher`: invitations, change lines and traits to look at the switcher with, while nothing provides
    /// them. Keyed by space name, so a test makes the spaces and these follow.
    enum FakeSwitcher {
        static let on = ProcessInfo.processInfo.arguments.contains("--fake-switcher")
        static let invites = [SpaceInvite(id: "invite", spaceName: "Book club", spaceIcon: "list", from: "Ana")]

        static func changes(_ spaces: [Space]) -> [String: String] {
            let work = spaces.filter { $0.name == "Work" }
            return Dictionary(uniqueKeysWithValues: work.map { ($0.id, "Ben edited 3 notes · 2h") })
        }

        static func traits(_ name: String) -> [SpaceTrait] {
            switch name {
            case "Family":
                [
                    SpaceTrait(
                        key: "shared", label: "Shared", summary: "With Ana and Ben", exposure: "Visible to Ana and Ben"),
                    SpaceTrait(key: "published", label: "Published", summary: "", problem: "Publishing failed"),
                ]
            case "Journal": [SpaceTrait(key: "agent", label: "Agent", summary: "On this iPhone")]
            case "Work": [SpaceTrait(key: "shared", label: "Shared", summary: "With Ben", exposure: "Visible to Ben")]
            default: []
            }
        }
    }
#endif
