import SwiftUI

/// Every theme as a row: a small page in its own colours, fonts and corners, its name and mood,
/// and a checkmark on the picked one.
struct ThemePicker: View {
    @Environment(AppGraph.self) private var graph

    var body: some View {
        ForEach(Themes.all) { theme in
            let picked = theme.id == graph.theme.id
            Button {
                graph.theme = theme
            } label: {
                HStack(spacing: 14) {
                    ThemeSample(theme: theme)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(theme.name).font(.notesRowTitle).foregroundStyle(Color.ink)
                        Text(theme.mood).font(.notesSmall).foregroundStyle(Color.ink3)
                    }
                    Spacer(minLength: 8)
                    if picked {
                        Image(systemName: "checkmark").font(.body.weight(.semibold)).foregroundStyle(Color.notesTint)
                    }
                }
                .contentShape(.rect)
            }
            .buttonStyle(.plain)
            .accessibilityElement(children: .combine)
            .accessibilityAddTraits(picked ? [.isButton, .isSelected] : .isButton)
        }
    }
}

/// The window, with a note's page set in from the top left: a title and a ticked task.
private struct ThemeSample: View {
    let theme: NotesTheme

    var body: some View {
        let c = theme.colors
        let scale = theme.shape.radiusScale
        let sheet = UnevenRoundedRectangle(topLeadingRadius: 7 * scale, style: .continuous)
        let title = Typeface.title(19, theme: theme)
        ZStack(alignment: .topLeading) {
            Color(c.bg.uiColor)
            VStack(alignment: .leading, spacing: 5) {
                Text("Aa").font(Font(title as CTFont)).tracking(Typeface.titleKern(title, theme: theme))
                    .foregroundStyle(Color(c.ink.uiColor))
                HStack(spacing: 4) {
                    check
                    Capsule().fill(Color(c.ink3.uiColor)).frame(width: 22, height: 2.5)
                }
            }
            .padding(.leading, 9)
            .padding(.top, 6)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            .background(Color(c.paper.uiColor), in: sheet)
            .overlay(sheet.strokeBorder(Color(c.edge.uiColor)))
            .padding(.leading, 10)
            .padding(.top, 8)
        }
        .frame(width: 72, height: 54)
        .clipShape(.rect(cornerRadius: 10 * scale, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 10 * scale, style: .continuous).strokeBorder(Color(c.hair.uiColor)))
        .accessibilityHidden(true)
    }

    /// A ticked task's box, as the theme draws it.
    private var check: some View {
        let c = theme.colors
        let shape: AnyShape =
            switch theme.shape.checkCorner {
            case .circle: AnyShape(Circle())
            case .radius(let r): AnyShape(RoundedRectangle(cornerRadius: r * 9 / 18))
            }
        return shape.fill(Color(c.accent.uiColor)).frame(width: 9, height: 9)
            .overlay {
                if theme.shape.checkMark == .dot {
                    Circle().fill(Color(c.onAccent.uiColor)).frame(width: 3.5, height: 3.5)
                }
            }
    }
}
