import NotesCore
import SwiftUI

/// The switcher between spaces (§7.7): the current space's icon and name,
/// opening a menu of every space and, last, New Space….
struct SpaceMenu: View {
    @Environment(AppGraph.self) private var graph
    var font: Font = .notesHeading
    var iconSize: CGFloat = 26
    /// After switching: the sidebar closes, onto the space's list.
    var onSwitched: () -> Void = {}
    let onCreate: () -> Void

    var body: some View {
        let name = graph.spaceName(graph.space)
        let icon = graph.spaces.first { $0.id == graph.space }?.icon ?? ""
        let selection = Binding(
            get: { graph.space },
            set: { id in
                graph.switchSpace(id)
                onSwitched()
            })
        Menu {
            Picker("Space", selection: selection) {
                ForEach(graph.spaces) { space in
                    Label {
                        Text(space.name)
                    } icon: {
                        SpaceIcon.named(space.icon).image
                    }
                    .tag(space.id)
                }
            }
            Divider()
            Button("New Space…", systemImage: "plus", action: onCreate)
        } label: {
            HStack(spacing: 6) {
                SpaceIconImage(icon: icon, size: iconSize).padding(.trailing, 2)
                Text(name).font(font).foregroundStyle(Color.ink).lineLimit(1)
                Image(systemName: "chevron.down")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(Color.ink3)
            }
            .contentShape(.rect)
        }
        .accessibilityLabel("Space: \(name)")
    }
}
