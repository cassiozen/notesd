import NotesCore
import SwiftUI

/// The switcher between spaces (§7.7): the current space's icon and name, opening the switcher sheet; then its
/// traits, grouped in their own button to Space info (§7.8).
struct SpaceMenu: View {
    @Environment(AppGraph.self) private var graph
    var font: Font = .notesHeading
    var iconSize: CGFloat = 26
    var invites: [SpaceInvite] = []
    /// What changed lately, by space id.
    var changes: [String: String] = [:]
    var onJoin: (String) -> Void = { _ in }
    var onDecline: (String) -> Void = { _ in }
    /// After switching: the sidebar closes, onto the space's list.
    var onSwitched: () -> Void = {}
    /// Space info for a space, by id.
    let onInfo: (String) -> Void
    let onCreate: () -> Void

    @State private var open = false
    /// Run once the sheet has gone: another sheet can't open while this one is still on screen.
    @State private var afterClose: (() -> Void)?

    var body: some View {
        let name = graph.spaceName(graph.space)
        let space = graph.spaces.first { $0.id == graph.space }
        HStack(spacing: 10) {
            Button {
                open = true
            } label: {
                HStack(spacing: 6) {
                    SpaceIconImage(icon: space?.icon ?? "", size: iconSize).padding(.trailing, 2)
                    Text(name).font(font).foregroundStyle(Color.ink).lineLimit(1)
                    Image(systemName: "chevron.down")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(Color.ink3)
                }
                .contentShape(.rect)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Space: \(name)")
            TraitGroup(traits: space?.traits ?? [], onOpen: { onInfo(graph.space) })
        }
        .sheet(isPresented: $open, onDismiss: closed) {
            SpaceSwitcherSheet(
                invites: invites, changes: changes, onJoin: onJoin, onDecline: onDecline,
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
