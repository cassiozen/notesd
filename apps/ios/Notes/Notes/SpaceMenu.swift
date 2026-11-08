import NotesCore
import SwiftUI

/// The switcher between spaces (§7.7): the current space's icon and name, opening the switcher sheet; then its
/// traits, grouped in their own button to Space info (§7.8).
struct SpaceMenu: View {
    @Environment(AppGraph.self) private var graph
    var iconSize: CGFloat = 31
    var invites: [SpaceInvite] = []
    /// What changed lately, by space id.
    var changes: [String: String] = [:]
    /// Joining switches to the space: the sheet closes first.
    var onJoin: (SpaceInvite) -> Void = { _ in }
    var onDecline: (SpaceInvite) -> Void = { _ in }
    /// After switching: the sidebar closes, onto the space's list.
    var onSwitched: () -> Void = {}
    /// Space info for a space, by id.
    let onInfo: (String) -> Void
    let onCreate: () -> Void

    /// -0.015em of the name.
    @ScaledMetric(relativeTo: .title) private var tracking: CGFloat = -0.39
    @State private var open = false
    /// Run once the sheet has gone: another sheet can't open while this one is still on screen.
    @State private var afterClose: (() -> Void)?

    var body: some View {
        let name = graph.spaceName(graph.space)
        let space = graph.spaces.first { $0.id == graph.space }
        HStack(spacing: 6) {
            Button {
                open = true
            } label: {
                HStack(spacing: 8) {
                    SpaceIconImage(icon: space?.icon ?? "", size: iconSize)
                    Text(name).font(.notesSpaceName).tracking(tracking).foregroundStyle(Color.ink).lineLimit(1)
                    Image(systemName: "chevron.down")
                        .font(.system(size: 11, weight: .bold))
                        .foregroundStyle(Color.ink2)
                }
                .padding(.vertical, 4)
                .padding(.leading, 6)
                .padding(.trailing, 8)
                .contentShape(.rect)
            }
            .buttonStyle(HighlightStyle())
            .accessibilityLabel("Space: \(name)")
            TraitGroup(traits: space?.traits ?? [], onOpen: { onInfo(graph.space) })
        }
        .sheet(isPresented: $open, onDismiss: closed) {
            SpaceSwitcherSheet(
                invites: invites, changes: changes,
                onJoin: { invite in close(then: { onJoin(invite) }) }, onDecline: onDecline,
                onSwitch: { id in
                    open = false
                    graph.switchSpace(id)
                    onSwitched()
                },
                onInfo: { id in close(then: { onInfo(id) }) },
                onCreate: { close(then: onCreate) })
        }
    }

    private func closed() {
        afterClose?()
        afterClose = nil
    }

    private func close(then action: @escaping () -> Void) {
        afterClose = action
        open = false
    }
}

/// Pressed, a highlight behind the trigger: the traits' pill beside it is drawn to the same height and radius.
private struct HighlightStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .background(
                configuration.isPressed ? Color.hover : .clear, in: .rect(cornerRadius: 10, style: .continuous))
    }
}
