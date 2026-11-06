import SwiftUI
import UIKit

/// The web app's Amber theme (shared/themes) as sRGB, taken from Android's ui/Tokens.kt, which
/// keeps the oklch source beside each value. Each colour follows the system light/dark appearance.
/// Themes.generated.swift has every theme; adopting it replaces this (docs/contributing/themes.md).
nonisolated enum Palette {
    static let bg = pair(0xFFF5_F3F1, 0xFF12_1416)
    static let ink = pair(0xFF21_1F1C, 0xFFE9_EBEE)
    static let ink2 = pair(0xFF50_4C4A, 0xFFB2_B4B7)
    static let ink3 = pair(0xFF66_6260, 0xFF93_9598)
    static let accent = pair(0xFFEF_B146, 0xFFEF_B146)
    static let accentDeep = pair(0xFFDD_9314, 0xFFDD_9314)
    static let onAccent = pair(0xFF1F_1915, 0xFF1F_1915)
    static let tint = pair(0xFFBE_7200, 0xFFF0_BA59)
    static let link = pair(0xFF87_4E00, 0xFFEF_BA64)
    static let danger = pair(0xFFAC_312C, 0xFFFB_8276)
    static let solid = pair(0xFF24_211F, 0xFFE6_E8EA)
    static let onSolid = pair(0xFFF9_F8F6, 0xFF14_1618)
    static let hair = pair(0x172D_2823, 0x14FF_FFFF)
    static let hairStrong = pair(0x292D_2823, 0x29FF_FFFF)
    static let hover = pair(0x122D_2823, 0x12FF_FFFF)
    /// A selected row's fill.
    static let on = pair(0x1C2D_2823, 0x21FF_FFFF)
    static let edge = pair(0xD9FF_FFFF, 0x14FF_FFFF)
    static let dangerBg = pair(0x1AD2_4D45, 0x29D2_4D45)
    /// The warm glow behind the list and the sidebar, at its full strength.
    static let blob = pair(0x2EF4_D9A8, 0x3362_4821)
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
    static let tagBg = pair(0x4DEF_B146, 0x4DEF_B146)

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

    private static func color(_ argb: UInt32) -> UIColor {
        UIColor(
            red: CGFloat((argb >> 16) & 0xFF) / 255, green: CGFloat((argb >> 8) & 0xFF) / 255,
            blue: CGFloat(argb & 0xFF) / 255, alpha: CGFloat((argb >> 24) & 0xFF) / 255)
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

/// The web's backdrop: a warm amber glow from the top left corner, fading
/// into the paper. Fixed behind the content; it doesn't scroll.
struct Backdrop: View {
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        // Drawn into one bitmap: the paper and the glow over it, as the eye sees them.
        Canvas { context, size in
            let traits = UITraitCollection(userInterfaceStyle: scheme == .dark ? .dark : .light)
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

/// The big amber compose button (web: the mobile footer's pencil).
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
