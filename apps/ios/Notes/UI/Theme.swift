import SwiftUI
import UIKit

/// The web app's design tokens (apps/web/src/styles/tokens.css and
/// space-themes.css) as sRGB. Each colour follows the system light/dark
/// appearance; the themed ones also follow the space shown ([SpaceThemeTrait],
/// their values in SpaceThemes.swift). The rest, the same in every theme, are
/// taken from Android's ui/Tokens.kt, which keeps the oklch source beside each.
nonisolated enum Palette {
    static let bg = themed { $0.bg }
    static let ink = themed { $0.ink }
    static let ink2 = themed { $0.ink2 }
    static let ink3 = themed { $0.ink3 }
    static let accent = themed { $0.accent }
    static let accentDeep = themed { $0.accentDeep }
    static let onAccent = themed { $0.onAccent }
    /// The caret.
    static let tint = themed { $0.tint }
    static let link = themed { $0.link }
    static let danger = pair(0xFFAC_312C, 0xFFFB_8276)
    static let solid = themed { $0.solid }
    static let onSolid = themed { $0.onSolid }
    static let hair = themed { $0.hair }
    static let hairStrong = themed { $0.hairStrong }
    static let hover = themed { $0.hover }
    /// A selected row's fill.
    static let on = themed { $0.on }
    static let edge = pair(0xD9FF_FFFF, 0x14FF_FFFF)
    static let dangerBg = pair(0x1AD2_4D45, 0x29D2_4D45)
    /// The glow behind the list and the sidebar, at its full strength (web: --blob-opacity).
    static let blob = UIColor { traits in
        let dark = traits.userInterfaceStyle == .dark
        return color(SpaceThemes.colors(traits.spaceTheme, dark: dark).blob1).withAlphaComponent(dark ? 0.2 : 0.18)
    }
    static let field = pair(0xC7FF_FFFF, 0x0FFF_FFFF)
    static let chip = pair(0x9EFF_FFFF, 0x12FF_FFFF)
    static let good = pair(0xFF0E_492A, 0xFF9E_E0B4)
    static let goodBg = pair(0xBFC0_EACD, 0x8024_5337)
    static let errBg = pair(0xD9FF_E8E3, 0xB24D_2620)
    static let errEdge = pair(0xFFFD_CAC0, 0xFF72_3B32)
    static let errInk = pair(0xFF78_2B21, 0xFFFD_CAC0)
    static let warnBg = pair(0xE0FF_EAD3, 0xBF48_2F1A)
    static let warnEdge = pair(0xFFF6_CBA5, 0xFF72_4B2B)
    static let warnInk = pair(0xFF62_3512, 0xFFF8_D8B4)

    /// Behind #tags (web: .prose [data-tag]).
    static let tagBg = themed { $0.tag }

    // A code fence is dark in both themes, as on the web (--code, --code-ink, --code-dim).
    static let code = pair(0xFF24_211F, 0xFF0B_0C0E)
    static let codeInk = pair(0xFFD8_D4CD, 0xFFD8_D4CD)
    static let codeDim = pair(0xFF8F_8C85, 0xFF8F_8C85)
    static let codeKeyword = pair(0xFFC3_A5F9, 0xFFC3_A5F9)
    static let codeString = pair(0xFF8B_D28D, 0xFF8B_D28D)
    static let codeNumber = pair(0xFFFB_A962, 0xFFFB_A962)

    private static func pair(_ light: UInt32, _ dark: UInt32) -> UIColor {
        UIColor { $0.userInterfaceStyle == .dark ? color(dark) : color(light) }
    }

    private static func themed(_ token: @escaping @Sendable (SpaceColors) -> UInt32) -> UIColor {
        UIColor { traits in
            color(token(SpaceThemes.colors(traits.spaceTheme, dark: traits.userInterfaceStyle == .dark)))
        }
    }

    private static func color(_ argb: UInt32) -> UIColor {
        UIColor(
            red: CGFloat((argb >> 16) & 0xFF) / 255, green: CGFloat((argb >> 8) & 0xFF) / 255,
            blue: CGFloat(argb & 0xFF) / 255, alpha: CGFloat((argb >> 24) & 0xFF) / 255)
    }
}

/// The theme of the space shown (§7.7), a key from [SpaceThemes.key]: set on the
/// window, so every [Palette] colour resolves in it, in UIKit and SwiftUI alike.
nonisolated struct SpaceThemeTrait: UITraitDefinition {
    static let defaultValue = ""
    static let affectsColorAppearance = true
    static let name = "SpaceTheme"
}

nonisolated extension UITraitCollection {
    var spaceTheme: String { self[SpaceThemeTrait.self] }
}

nonisolated extension UIMutableTraits {
    var spaceTheme: String {
        get { self[SpaceThemeTrait.self] }
        set { self[SpaceThemeTrait.self] = newValue }
    }
}

/// [SpaceThemeTrait] in SwiftUI: read, and set on a subtree (a preview of another theme).
nonisolated struct SpaceThemeKey: EnvironmentKey, UITraitBridgedEnvironmentKey {
    static let defaultValue = ""

    static func read(from traitCollection: UITraitCollection) -> String { traitCollection.spaceTheme }

    static func write(to mutableTraits: inout UIMutableTraits, value: String) { mutableTraits.spaceTheme = value }
}

extension EnvironmentValues {
    var spaceTheme: String {
        get { self[SpaceThemeKey.self] }
        set { self[SpaceThemeKey.self] = newValue }
    }
}

extension Color {
    static let notesBg = Color(Palette.bg)
    static let ink = Color(Palette.ink)
    static let ink2 = Color(Palette.ink2)
    static let ink3 = Color(Palette.ink3)
    static let accent = Color(Palette.accent)
    static let accentDeep = Color(Palette.accentDeep)
    static let onAccent = Color(Palette.onAccent)
    static let notesTint = Color(Palette.tint)
    static let danger = Color(Palette.danger)
    static let hair = Color(Palette.hair)
    static let hairStrong = Color(Palette.hairStrong)
    static let hover = Color(Palette.hover)
    static let on = Color(Palette.on)
    static let edge = Color(Palette.edge)
    static let dangerBg = Color(Palette.dangerBg)
    static let blob = Color(Palette.blob)
    static let field = Color(Palette.field)
    static let chip = Color(Palette.chip)
    static let solid = Color(Palette.solid)
    static let onSolid = Color(Palette.onSolid)
    static let tag = Color(Palette.tagBg)
    static let errBg = Color(Palette.errBg)
    static let errInk = Color(Palette.errInk)
    static let warnBg = Color(Palette.warnBg)
    static let warnInk = Color(Palette.warnInk)
    static let goodBg = Color(Palette.goodBg)
    static let good = Color(Palette.good)
}

/// The web app's three faces (apps/web/src/styles/fonts.css), bundled so the app looks the same offline.
nonisolated enum Typeface {
    /// Instrument Sans: everything that isn't a title. A variable font, 400–700.
    static func sans(_ size: CGFloat, weight: CGFloat = 400, italic: Bool = false) -> UIFont {
        variable(italic ? "InstrumentSans-Italic" : "InstrumentSans-Regular", size, weight)
    }

    /// Instrument Serif: headings, titles, quotes. One weight.
    static func serif(_ size: CGFloat, italic: Bool = false) -> UIFont {
        UIFont(name: italic ? "InstrumentSerif-Italic" : "InstrumentSerif-Regular", size: size)
            ?? .systemFont(ofSize: size)
    }

    /// JetBrains Mono: server addresses, code, raw Markdown.
    static func mono(_ size: CGFloat, weight: CGFloat = 400) -> UIFont {
        variable("JetBrainsMono-Regular", size, weight)
    }

    private static let weightAxis = 0x7767_6874  // 'wght'

    private static func variable(_ name: String, _ size: CGFloat, _ weight: CGFloat) -> UIFont {
        guard let base = UIFont(name: name, size: size) else { return .systemFont(ofSize: size) }
        if weight == 400 { return base }
        let key = UIFontDescriptor.AttributeName(rawValue: kCTFontVariationAttribute as String)
        return UIFont(descriptor: base.fontDescriptor.addingAttributes([key: [weightAxis: weight]]), size: size)
    }

    /// Scaled with the user's text size.
    static func scaled(_ font: UIFont, as style: UIFont.TextStyle = .body) -> UIFont {
        UIFontMetrics(forTextStyle: style).scaledFont(for: font)
    }
}

extension Font {
    /// Named styles, sized for phones (the web app's mobile sizes), scaling with Dynamic Type.
    static let notesDisplay = Font.custom("InstrumentSerif-Regular", size: 34, relativeTo: .largeTitle)
    static let notesHeading = Font.custom("InstrumentSerif-Regular", size: 30, relativeTo: .title)
    static let notesTitle = Font.custom("InstrumentSerif-Regular", size: 22, relativeTo: .title2)
    static let notesBody = Font.custom("InstrumentSans-Regular", size: 15, relativeTo: .body)
    static let notesLede = Font.custom("InstrumentSans-Regular", size: 16, relativeTo: .body)
    static let notesRowTitle = Font.custom("InstrumentSans-Regular", size: 16, relativeTo: .body).weight(.medium)
    static let notesRowMeta = Font.custom("InstrumentSans-Regular", size: 14, relativeTo: .subheadline)
    /// A small quiet label above a heading or a group (web: Kicker).
    static let notesKicker = Font.custom("InstrumentSans-Regular", size: 13, relativeTo: .footnote).weight(.semibold)
    static let notesLabel = Font.custom("InstrumentSans-Regular", size: 13, relativeTo: .footnote).weight(.medium)
    static let notesSmall = Font.custom("InstrumentSans-Regular", size: 13, relativeTo: .footnote)
    static let notesButton = Font.custom("InstrumentSans-Regular", size: 15, relativeTo: .body).weight(.semibold)
    static let notesMono = Font.custom("JetBrainsMono-Regular", size: 14, relativeTo: .body)
    /// The wordmark: lowercase and monospaced, like the daemon it's named after.
    static let notesBrand = Font.custom("JetBrainsMono-Regular", size: 20, relativeTo: .title3).weight(.medium)
}

/// A list section's title, in ink2: the system's secondary grey is too faint on the paper background.
struct SectionHeader: View {
    let title: String

    init(_ title: String) {
        self.title = title
    }

    var body: some View {
        Text(title).font(.notesLabel).foregroundStyle(Color.ink2).textCase(nil)
    }
}

/// The web's backdrop: a glow in the theme's hue from the top left corner,
/// fading into the paper. Fixed behind the content; it doesn't scroll.
struct Backdrop: View {
    @Environment(\.colorScheme) private var scheme
    @Environment(\.spaceTheme) private var theme

    var body: some View {
        // Drawn into one bitmap: the paper and the glow over it, as the eye sees them.
        Canvas { context, size in
            let traits = UITraitCollection { traits in
                traits.userInterfaceStyle = scheme == .dark ? .dark : .light
                traits.spaceTheme = theme
            }
            let paper = Color(Palette.bg.resolvedColor(with: traits))
            let glow = Color(Palette.blob.resolvedColor(with: traits))
            let rect = CGRect(origin: .zero, size: size)
            context.fill(Path(rect), with: .color(paper))
            let radius = max(size.width, 1)
            let center = CGPoint(x: size.width * 0.1, y: -size.height * 0.02)
            context.fill(
                Path(rect),
                with: .radialGradient(
                    Gradient(stops: [
                        .init(color: glow, location: 0),
                        .init(color: glow.opacity(0.55), location: 0.45),
                        .init(color: glow.opacity(0), location: 1),
                    ]),
                    center: center, startRadius: 0, endRadius: radius))
        }
        .ignoresSafeArea()
        .accessibilityHidden(true)
    }
}

/// The app's mark: the yellow notepad.
struct BrandMark: View {
    var size: CGFloat = 26

    var body: some View {
        Image("BrandMark").resizable().interpolation(.high).frame(width: size, height: size)
            .accessibilityHidden(true)
    }
}

/// A row that fills softly while pressed, as Android's ripple and the web's hover do.
struct RowButtonStyle: ButtonStyle {
    var selected = false
    var radius: CGFloat = 16

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .background(
                configuration.isPressed ? Color.on : selected ? Color.on : .clear, in: .rect(cornerRadius: radius)
            )
            .contentShape(.rect(cornerRadius: radius))
            .animation(.easeOut(duration: configuration.isPressed ? 0.05 : 0.25), value: configuration.isPressed)
    }
}

/// Shrinks a little while pressed: for the compose button and other solid controls.
struct PressScaleStyle: ButtonStyle {
    var scale: CGFloat = 0.92

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? scale : 1)
            .animation(.spring(response: 0.25, dampingFraction: 0.6), value: configuration.isPressed)
    }
}

/// The big compose button in the space's accent (web: the mobile footer's pencil).
struct ComposeButton: View {
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: "square.and.pencil")
                .font(.system(size: 21, weight: .semibold))
                .foregroundStyle(Color.onAccent)
                .frame(width: 52, height: 52)
                .background {
                    RoundedRectangle(cornerRadius: 16, style: .continuous).fill(Color.accent)
                        .overlay(alignment: .bottom) {
                            Rectangle().fill(Color.accentDeep).frame(height: 2.5)
                        }
                        .clipShape(.rect(cornerRadius: 16, style: .continuous))
                }
                .shadow(color: Color.accentDeep.opacity(0.35), radius: 10, y: 4)
        }
        .buttonStyle(PressScaleStyle())
        .accessibilityLabel("New note")
    }
}
