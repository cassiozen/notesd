import NotesCore
import SwiftUI

/// The switcher between spaces (§7.7): the current space's name, opening a
/// menu of every space. Only shown once there is more than one.
struct SpaceMenu: View {
    @Environment(AppGraph.self) private var graph
    var font: Font = .notesHeading

    var body: some View {
        let name = graph.spaceName(graph.space)
        Menu {
            Picker("Space", selection: Binding(get: { graph.space }, set: { graph.switchSpace($0) })) {
                ForEach(graph.spaces) { space in
                    Text(space.name).tag(space.id)
                }
            }
        } label: {
            HStack(spacing: 6) {
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
