import NotesCore
import SwiftUI
import UIKit

extension View {
    /// Whether the window's bars run down its side: the iPhone Duo folded, or open in landscape.
    func readsSideBar(_ sideBar: Binding<Bool>) -> some View {
        modifier(ReadsSideBar(sideBar: sideBar))
    }
}

private struct ReadsSideBar: ViewModifier {
    @Binding var sideBar: Bool

    func body(content: Content) -> some View {
        // `#available` can't hide a symbol an older SDK lacks (SwiftUI 8.0.85 is the iOS 27.1 SDK's).
        #if canImport(SwiftUI, _version: 8.0.85)
            if #available(iOS 27.1, *) {
                content.background(SideBarProbe(sideBar: $sideBar))
            } else {
                content
            }
        #else
            content
        #endif
    }
}

#if canImport(SwiftUI, _version: 8.0.85)
    @available(iOS 27.1, *)
    private struct SideBarProbe: View {
        @Environment(\.toolbarVerticalEdge) private var edge
        @Binding var sideBar: Bool

        var body: some View {
            Color.clear.onChange(of: edge != nil, initial: true) { sideBar = $1 }
        }
    }

    /// The formatting bar as side-bar items. Folded with the keyboard up the side bar has room for one,
    /// so Aa holds every block style; checklist and picture are shortcuts for when there's more room.
    @available(iOS 27.1, *)
    struct SideFormattingTools: ToolbarContent {
        let controller: EditorController

        private var state: FormatState { controller.format }

        var body: some ToolbarContent {
            if state.selecting {
                tool("Bold", Image(systemName: "bold"), on: state.bold) { $0.toggleBold() }
                    .visibilityPriority(.high)
                tool("Italic", Image(systemName: "italic"), on: state.italic) { $0.toggleItalic() }
                    .visibilityPriority(.high)
                tool("Strikethrough", Image(systemName: "strikethrough"), on: state.strike) { $0.toggleStrike() }
                ToolbarItem(placement: .bottomBar) {
                    SideTool(title: "Link", icon: Image(systemName: "link"), on: state.link) { controller.editLink() }
                }
            } else {
                ToolbarItem(placement: .bottomBar) { styleMenu }
                    .visibilityPriority(.high)
                tool("Checklist", Image(systemName: "checklist"), on: state.list == .task) { $0.toggleList(.task) }
                ToolbarItem(placement: .bottomBar) {
                    Button("Add picture", systemImage: "photo") { controller.pickingImage = true }
                        .disabled(state.inTitle)
                }
            }
        }

        private func tool(
            _ title: String, _ icon: Image, on: Bool, _ action: @escaping (NoteTextView) -> Void
        ) -> some ToolbarContent {
            ToolbarItem(placement: .bottomBar) {
                SideTool(title: title, icon: icon, on: on) { controller.run(action) }
                    .disabled(state.inTitle)
            }
        }

        private var styleMenu: some View {
            Menu {
                Section {
                    ForEach(1...3, id: \.self) { level in
                        Toggle(isOn: toggling(state.heading == level) { $0.toggleHeading(level) }) {
                            Label {
                                Text("Heading \(level)")
                            } icon: {
                                HeadingGlyph.image(level)
                            }
                        }
                    }
                    Toggle("Quote", systemImage: "text.quote", isOn: toggling(state.quote) { $0.toggleQuote() })
                }
                Section {
                    listToggle("Bulleted list", "list.bullet", .bullet)
                    listToggle("Numbered list", "list.number", .ordered)
                    listToggle("Checklist", "checklist", .task)
                }
                if state.canIndent || state.canOutdent {
                    Section {
                        Button("Indent", systemImage: "increase.indent") { controller.run { $0.indent() } }
                            .disabled(!state.canIndent)
                        Button("Outdent", systemImage: "decrease.indent") { controller.run { $0.outdent() } }
                            .disabled(!state.canOutdent)
                    }
                }
            } label: {
                Label {
                    Text("Format")
                } icon: {
                    styleIcon
                }
            }
            .tint(state.heading > 0 || state.quote || state.list != nil ? Color.accent : nil)
            .disabled(state.inTitle)
        }

        /// Shows the cursor's block style, so what applies is visible without opening the menu.
        @ViewBuilder private var styleIcon: some View {
            if state.heading > 0 {
                HeadingGlyph.image(state.heading)
            } else if state.quote {
                Image(systemName: "text.quote")
            } else {
                switch state.list {
                case .bullet: Image(systemName: "list.bullet")
                case .ordered: Image(systemName: "list.number")
                case .task: Image(systemName: "checklist")
                case nil: Image(systemName: "textformat")
                }
            }
        }

        private func listToggle(_ title: String, _ symbol: String, _ kind: ListKind) -> some View {
            Toggle(title, systemImage: symbol, isOn: toggling(state.list == kind) { $0.toggleList(kind) })
        }

        /// A menu's checkmark for a formatting toggle: on as the cursor's block is, and choosing it toggles.
        private func toggling(_ on: Bool, _ action: @escaping (NoteTextView) -> Void) -> Binding<Bool> {
            Binding(get: { on }, set: { _ in controller.run(action) })
        }
    }

    /// A formatting toggle in the side bar: filled amber while on, as in the bar above the keyboard.
    @available(iOS 27.1, *)
    private struct SideTool: View {
        let title: String
        let icon: Image
        let on: Bool
        let action: () -> Void

        var body: some View {
            let button = Button(action: action) {
                Label {
                    Text(title)
                } icon: {
                    icon
                }
            }
            .symbolEffect(.bounce, value: on)
            .accessibilityAddTraits(on ? .isSelected : [])
            if on {
                button.buttonStyle(.glassProminent).tint(Color.accent).foregroundStyle(Color.onAccent)
            } else {
                button
            }
        }
    }
#endif

/// H1, H2 and H3 as icons, in the faces the headings have in the note. Toolbar items need an image:
/// in a side bar, a text-only item isn't shown.
@MainActor
enum HeadingGlyph {
    private static var cache: [Int: UIImage] = [:]

    static func image(_ level: Int) -> Image {
        if cache[level] == nil {
            let renderer = ImageRenderer(content: Text("H\(level)").font(.heading(level)))
            renderer.scale = 3
            cache[level] = renderer.uiImage?.withRenderingMode(.alwaysTemplate)
        }
        return cache[level].map { Image(uiImage: $0) } ?? Image(systemName: "textformat")
    }
}
