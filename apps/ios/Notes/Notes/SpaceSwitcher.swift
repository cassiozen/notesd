import NotesCore
import SwiftUI

/// An invitation to someone else's space (§7.9).
struct SpaceInvite: Identifiable, Hashable {
    let id: String
    var spaceId = ""
    let spaceName: String
    let spaceIcon: String
    /// The inviter's username.
    let from: String
}

extension SpaceInvite {
    init(_ remote: RemoteInvite) {
        self.init(
            id: remote.id, spaceId: remote.spaceId, spaceName: remote.spaceName, spaceIcon: remote.spaceIcon,
            from: remote.from.name)
    }
}

/// A line of the switcher: an optional glyph and its text, all in the danger colour for a problem.
struct SwitcherLine: Hashable {
    var symbol: String?
    var text: String
    var problem = false
}

/// A row's second line, only for something to look at.
enum SwitcherDetail: Equatable {
    /// What others changed there lately.
    case change(String)
    /// Its traits' problems, joined.
    case problems(String)
}

enum Switcher {
    /// The current space's card: a line per trait, in order, its problem or its label and summary. None for a
    /// space without traits: private is the default and goes unsaid.
    static func cardLines(_ space: Space) -> [SwitcherLine] {
        space.traits.map { t in
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

    /// What changed there, else what's wrong with its traits; nil for a row of one line.
    static func detail(_ space: Space, change: String?) -> SwitcherDetail? {
        if let change { return .change(change) }
        let problems = space.traits.compactMap(\.problem)
        return problems.isEmpty ? nil : .problems(problems.joined(separator: " · "))
    }

    /// What VoiceOver says for a row: its name, then what changed, else its traits (the glyphs, in words).
    static func spoken(_ space: Space, change: String?) -> String {
        if let change { return "\(space.name), \(change)" }
        return space.traits.isEmpty ? space.name : "\(space.name), \(Traits.spoken(space.traits))"
    }
}

/// The space switcher (§7.7), as a sheet: the current space, invitations, the spaces to switch to, New Space….
struct SpaceSwitcherSheet: View {
    @Environment(AppGraph.self) private var graph
    var invites: [SpaceInvite] = []
    /// What changed lately, by space id.
    var changes: [String: String] = [:]
    var onJoin: (SpaceInvite) -> Void = { _ in }
    var onDecline: (SpaceInvite) -> Void = { _ in }
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
        let lines = Switcher.cardLines(space)
        // Name and Info only: centred on the icon rather than hanging from its top.
        return HStack(alignment: lines.isEmpty ? .center : .top, spacing: 14) {
            SpaceIconImage(icon: space.icon, size: 40)
            VStack(alignment: .leading, spacing: 4) {
                Text(space.name).font(.notesRowTitle.weight(.semibold)).foregroundStyle(Color.ink)
                    .accessibilityAddTraits(.isHeader)
                ForEach(lines, id: \.self) { line in
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
                .frame(minWidth: 44, minHeight: 44, alignment: lines.isEmpty ? .trailing : .topTrailing)
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
                    Button("Join") { onJoin(invite) }
                        .buttonStyle(InviteButtonStyle(primary: true))
                        .accessibilityLabel("Join \(invite.spaceName)")
                    Button("Decline") { onDecline(invite) }
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

/// A space to switch to: one line, its icon, name and trait glyphs; a second only for what changed there or a
/// trait's problem. Tapping switches; holding opens its Space info without switching.
private struct SwitcherRow: View {
    let space: Space
    let change: String?
    let onSwitch: () -> Void
    let onInfo: () -> Void
    @State private var pressed = false

    var body: some View {
        let detail = Switcher.detail(space, change: change)
        HStack(spacing: 12) {
            SpaceIconImage(icon: space.icon, size: 20)
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    Text(space.name).foregroundStyle(Color.ink).lineLimit(1)
                    if !space.traits.isEmpty { TraitGlyphs(traits: space.traits) }
                    if change != nil { Circle().fill(Color.ink).frame(width: 7, height: 7) }
                }
                .font(.notesRowTitle)
                if let detail { line(detail) }
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 6)
        .frame(minHeight: 44)
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

    private func line(_ detail: SwitcherDetail) -> some View {
        let (text, color): (String, Color) =
            switch detail {
            case .change(let text): (text, .ink2)
            case .problems(let text): (text, .danger)
            }
        return Text(text)
            .font(.custom("InstrumentSans-Regular", size: 12.5, relativeTo: .caption))
            .foregroundStyle(color)
            .lineLimit(1)
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
    /// `--fake-switcher`: invitations, change lines and traits to look at the switcher with, without a second
    /// account. Keyed by space name, so a test makes the spaces and these follow.
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
