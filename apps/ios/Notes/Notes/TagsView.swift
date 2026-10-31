import NotesCore
import SwiftUI

/// The sidebar: All notes, Untagged, Locked and the tag tree with counts, and
/// Settings in its header. Choosing one filters the list and search, and
/// closes the sidebar.
struct TagsView: View {
    @Environment(AppGraph.self) private var graph
    @Environment(Drawer.self) private var drawer
    @Environment(Navigator.self) private var nav
    let openSettings: () -> Void

    @State private var tags: [TagCount] = []
    @State private var lockedCount = 0
    @State private var open: Set<String> = Self.savedOpen
    @State private var creating = false

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 2) {
                HStack {
                    // The space, always: its menu makes new ones too.
                    SpaceMenu(
                        font: .custom("InstrumentSerif-Regular", size: 27, relativeTo: .title), iconSize: 31,
                        onSwitched: { switched() }
                    ) {
                        creating = true
                    }
                    .accessibilityAddTraits(.isHeader)
                    Spacer()
                    Button(action: openSettings) {
                        Image(systemName: "gearshape")
                            .font(.system(size: 17, weight: .medium))
                            .foregroundStyle(Color.ink)
                            .frame(width: 40, height: 40)
                            .background(Color.chip, in: .rect(cornerRadius: 12, style: .continuous))
                            .overlay(
                                RoundedRectangle(cornerRadius: 12, style: .continuous).strokeBorder(Color.edge)
                            )
                            .frame(width: 48, height: 48)
                            .contentShape(.rect)
                    }
                    .buttonStyle(PressScaleStyle())
                    .accessibilityLabel("Settings")
                }
                .padding(.leading, 20)
                .padding(.trailing, 8)
                .padding(.bottom, 10)

                row("All notes", filter: nil)
                row("Untagged", filter: .untagged)
                // Kept while chosen, so unlocking the last one doesn't pull the row from under the list.
                if lockedCount > 0 || graph.listFilter == .locked {
                    row("Locked", icon: "lock", filter: .locked)
                }

                Text("Tags")
                    .font(.notesKicker)
                    .foregroundStyle(Color.ink3)
                    .padding(.leading, 62)
                    .padding(.top, 18)
                    .padding(.bottom, 4)
                    .accessibilityAddTraits(.isHeader)
                ForEach(visibleTags, id: \.key) { tag in
                    tagRow(tag)
                        .transition(.opacity.combined(with: .move(edge: .top)))
                }
                if tags.isEmpty {
                    Text("Type #tag in a note to group it here. #parent/child nests.")
                        .font(.notesSmall)
                        .foregroundStyle(Color.ink3)
                        .padding(.leading, 62)
                        .padding(.trailing, 20)
                        .padding(.top, 6)
                }
            }
            .padding(.top, 8)
            .padding(.bottom, 24)
        }
        .scrollIndicators(.hidden)
        .background { Backdrop() }
        .sheet(isPresented: $creating) {
            SpaceSheet { id in
                creating = false
                graph.switchSpace(id)
                // To the new space's list, empty and ready for a note.
                switched()
            }
        }
        .task(id: graph.space) {
            let changes = graph.store.changes()
            await reload()
            for await _ in changes { await reload() }
        }
    }

    /// The space's tags (§7.7).
    private func reload() async {
        let next = (try? await graph.store.tags(space: graph.space)) ?? tags
        let locked = (try? await graph.store.lockedCount(space: graph.space)) ?? 0
        withAnimation(.snappy) {
            tags = next
            lockedCount = locked
        }
    }

    /// Onto the space's list, without the note from the last space that was open beside it.
    private func switched() {
        nav.leaveNote()
        drawer.close()
    }

    /// Tags whose every parent is open.
    private var visibleTags: [TagCount] {
        tags.filter { tag in
            let levels = tag.key.split(separator: "/")
            return (1..<max(levels.count, 1)).allSatisfy { open.contains(levels.prefix($0).joined(separator: "/")) }
        }
    }

    private func hasChildren(_ tag: TagCount) -> Bool {
        tags.contains { $0.key.hasPrefix(tag.key + "/") }
    }

    /// All notes, Untagged, Locked: no expander, so they line up with the tags' names.
    private func row(_ title: String, icon: String? = nil, filter: NoteFilter?) -> some View {
        let selected = graph.listFilter == filter
        return HStack(spacing: 0) {
            Spacer().frame(width: 48)
            Button {
                choose(filter)
            } label: {
                HStack(spacing: 8) {
                    if let icon {
                        Image(systemName: icon).font(.system(size: 13, weight: .medium)).foregroundStyle(Color.ink3)
                    }
                    Text(title)
                        .font(.notesLede.weight(selected ? .semibold : .regular))
                        .foregroundStyle(Color.ink)
                    Spacer(minLength: 0)
                }
                .padding(.horizontal, 12)
                .frame(minHeight: 48)
            }
            .buttonStyle(RowButtonStyle(selected: selected, radius: 12))
            .accessibilityAddTraits(selected ? .isSelected : [])
        }
        .padding(.leading, 4)
        .padding(.trailing, 8)
    }

    private func tagRow(_ tag: TagCount) -> some View {
        let depth = tag.key.split(separator: "/").count - 1
        let leaf = depth == 0 ? tag.name : tag.name.split(separator: "/").last.map(String.init) ?? tag.name
        let filter = NoteFilter.tag(key: tag.key, name: tag.name)
        let selected = graph.listFilter == filter
        let isOpen = open.contains(tag.key)
        return HStack(spacing: 0) {
            if hasChildren(tag) {
                Button {
                    toggle(tag.key)
                } label: {
                    Image(systemName: "chevron.right")
                        .font(.system(size: 11, weight: .bold))
                        .foregroundStyle(Color.ink3)
                        .rotationEffect(.degrees(isOpen ? 90 : 0))
                        .frame(width: 48, height: 48)
                        .contentShape(.rect)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(isOpen ? "Collapse \(leaf)" : "Expand \(leaf)")
            } else {
                Spacer().frame(width: 48)
            }
            Button {
                choose(filter)
            } label: {
                HStack(spacing: 8) {
                    Text("#").font(.notesBody).foregroundStyle(Color.ink3)
                    Text(leaf)
                        .font(.notesLede.weight(selected ? .semibold : .regular))
                        .foregroundStyle(Color.ink)
                        .lineLimit(1)
                    Spacer(minLength: 8)
                    Text("\(tag.count)").font(.notesSmall).foregroundStyle(Color.ink3).monospacedDigit()
                }
                .padding(.horizontal, 12)
                .frame(minHeight: 48)
            }
            .buttonStyle(RowButtonStyle(selected: selected, radius: 12))
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("#\(tag.name), \(tag.count == 1 ? "1 note" : "\(tag.count) notes")")
            .accessibilityAddTraits(selected ? [.isButton, .isSelected] : .isButton)
        }
        .padding(.leading, 4 + CGFloat(depth) * 16)
        .padding(.trailing, 8)
    }

    private func choose(_ filter: NoteFilter?) {
        graph.listFilter = filter
        drawer.close()
    }

    private func toggle(_ key: String) {
        withAnimation(.snappy) {
            if open.contains(key) { open.remove(key) } else { open.insert(key) }
        }
        UserDefaults.standard.set(Array(open), forKey: "openTags")
    }

    private static var savedOpen: Set<String> {
        Set(UserDefaults.standard.stringArray(forKey: "openTags") ?? [])
    }
}
