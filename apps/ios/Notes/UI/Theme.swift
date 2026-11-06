import SwiftUI
import UIKit

/// The picked theme (shared/themes, generated into Themes.generated.swift). Colours resolve against
/// it as they're drawn; fonts and corners are read as screens are built, so picking another
/// rebuilds them (NotesApp gives the screens the theme's id).
nonisolated enum CurrentTheme {
    // Written on the main thread, before the screens that read it are rebuilt.
    nonisolated(unsafe) private(set) static var theme = Themes.fallback

    @MainActor static func set(_ theme: NotesTheme) {
        self.theme = theme
        styleBars()
    }

    /// Titles in the theme's title face, as on the web and Android; bar buttons in its tint. Bars
    /// made from here on take it.
    @MainActor static func styleBars() {
        let bar = UINavigationBar.appearance()
        bar.tintColor = Palette.tint
        bar.largeTitleTextAttributes = titleAttributes(Typeface.scaled(Typeface.title(36), as: .largeTitle))
        bar.titleTextAttributes = titleAttributes(Typeface.scaled(Typeface.title(21), as: .headline))
    }

    private static func titleAttributes(_ font: UIFont) -> [NSAttributedString.Key: Any] {
        var attributes: [NSAttributedString.Key: Any] = [.font: font, .foregroundColor: Palette.ink]
        if theme.title.tracking != 0 { attributes[.kern] = theme.title.tracking * font.pointSize }
        return attributes
    }
}

/// A corner radius as the theme rounds corners. Pills stay pills.
nonisolated func corner(_ radius: CGFloat) -> CGFloat {
    radius * CurrentTheme.theme.shape.radiusScale
}

/// The picked theme's colours, each following the light/dark appearance.
nonisolated enum Palette {
    static let bg = role { $0.bg }
    static let paper = role { $0.paper }
    static let ink = role { $0.ink }
    static let ink2 = role { $0.ink2 }
    static let ink3 = role { $0.ink3 }
    static let accent = role { $0.accent }
    static let accentDeep = role { $0.accentDeep }
    static let onAccent = role { $0.onAccent }
    static let tint = role { $0.tint }
    static let link = role { $0.link }
    static let danger = role { $0.danger }
    static let solid = role { $0.solid }
    static let onSolid = role { $0.onSolid }
    static let hair = role { $0.hair }
    static let hairStrong = role { $0.hairStrong }
    static let hover = role { $0.hover }
    /// A selected row's fill.
    static let on = role { $0.on }
    static let edge = role { $0.edge }
    /// A floating panel drawn solid, in a style without blur.
    static let glassHi = role { $0.glassHi }
    static let dangerBg = role { $0.dangerBg }
    /// The glow behind the list and the sidebar, at its full strength; clear in a style without one.
    static let blob = optional { $0.wash }
    static let field = role { $0.field }
    static let chip = role { $0.chip }
    static let good = role { $0.good }
    static let goodBg = role { $0.goodBg }
    static let errBg = role { $0.errBg }
    static let errEdge = role { $0.errEdge }
    static let errInk = role { $0.errInk }
    static let warnBg = role { $0.warnBg }
    static let warnEdge = role { $0.warnEdge }
    static let warnInk = role { $0.warnInk }

    /// Behind #tags (web: .prose [data-tag]); clear in a theme that colours their text instead.
    static let tagBg = optional { $0.tagBackground }
    /// #tags' text, in a theme that sets it.
    static var tagInk: UIColor? {
        CurrentTheme.theme.colors.tagInk == nil ? nil : optional { $0.tagInk }
    }

    // A code fence is dark in every theme, as on the web (--code, --code-ink, --code-dim). Opaque:
    // the web's see-through block lets its backdrop glow show; on a phone's page it only washes it out.
    static let code = role(opaque: true) { $0.code }
    static let codeInk = role { $0.codeInk }
    static let codeDim = role { $0.codeDim }
    static let codeKeyword = role { $0.syntaxKeyword }
    static let codeString = role { $0.syntaxString }
    static let codeNumber = role { $0.syntaxNumber }

    private static func role(opaque: Bool = false, _ key: @escaping @Sendable (ThemeColors) -> ThemeColor) -> UIColor {
        UIColor { traits in
            let pair = key(CurrentTheme.theme.colors)
            let argb = traits.userInterfaceStyle == .dark ? pair.dark : pair.light
            return color(opaque ? argb | 0xFF00_0000 : argb)
        }
    }

    private static func optional(_ key: @escaping @Sendable (ThemeColors) -> ThemeColor?) -> UIColor {
        UIColor { traits in
            guard let pair = key(CurrentTheme.theme.colors) else { return .clear }
            return color(traits.userInterfaceStyle == .dark ? pair.dark : pair.light)
        }
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

/// The picked theme's faces, and JetBrains Mono for code, all bundled so the app looks the same offline.
nonisolated enum Typeface {
    /// The theme's body face: everything that isn't a title.
    static func sans(_ size: CGFloat, weight: CGFloat = 400, italic: Bool = false) -> UIFont {
        face(CurrentTheme.theme.body, size, weight: weight, italic: italic)
    }

    /// A title at a size the rules give for Instrument Serif, as `theme` sets titles: its scale,
    /// weight, style and axes. `italic` turns its style over (an italic mark). Tracking is
    /// `titleKern`, for the caller to set.
    static func title(_ size: CGFloat, italic: Bool = false, theme: NotesTheme = CurrentTheme.theme) -> UIFont {
        let t = theme.title
        return face(t.typeface, size * t.scale, weight: t.weight, italic: t.italic != italic, axes: t.axes)
    }

    /// A quote: the title face, lighter, and in the theme's quote style.
    static func quote(_ size: CGFloat, italic: Bool = false) -> UIFont {
        let t = CurrentTheme.theme.title
        return face(t.typeface, size * t.scale, weight: t.quoteWeight, italic: t.quoteItalic != italic, axes: t.axes)
    }

    /// The theme's title tracking for a title font, in points.
    static func titleKern(_ font: UIFont, theme: NotesTheme = CurrentTheme.theme) -> CGFloat {
        theme.title.tracking * font.pointSize
    }

    /// JetBrains Mono: server addresses, code, raw Markdown. The same in every theme.
    static func mono(_ size: CGFloat, weight: CGFloat = 400) -> UIFont {
        guard let base = UIFont(name: "JetBrainsMono-Regular", size: size) else { return .systemFont(ofSize: size) }
        if weight == 400 { return base }
        return UIFont(
            descriptor: base.fontDescriptor.addingAttributes([variationKey: [axis("wght"): weight]]), size: size)
    }

    /// The face of `typeface` that has `weight` (or the closest), in the style asked for.
    static func nearest(_ typeface: ThemeTypeface, _ weight: CGFloat, italic: Bool) -> ThemeFontFace {
        let slanted = typeface.faces.filter(\.italic)
        let upright = typeface.faces.filter { !$0.italic }
        let pool = italic && !slanted.isEmpty ? slanted : upright.isEmpty ? typeface.faces : upright
        func distance(_ face: ThemeFontFace) -> CGFloat {
            max(face.weights.lowerBound - weight, weight - face.weights.upperBound, 0)
        }
        return pool.min { distance($0) < distance($1) } ?? typeface.faces[0]
    }

    /// Whether `face` is a variable font with a weight axis.
    static func isVariable(_ face: ThemeFontFace) -> Bool {
        face.weights.lowerBound < face.weights.upperBound
    }

    // Fonts are made per run as the editor styles text; making one with variations isn't free.
    nonisolated(unsafe) private static let cache = NSCache<NSString, UIFont>()

    /// A theme face: the weight set on a variable face (400 is the files' own), the title's axes,
    /// and, for a face with optical sizes, opsz at the point size, as a browser sets it.
    static func face(
        _ typeface: ThemeTypeface, _ size: CGFloat, weight: CGFloat, italic: Bool, axes: [String: CGFloat] = [:]
    ) -> UIFont {
        let key = "\(typeface.name) \(size) \(weight) \(italic) \(axes.sorted { $0.key < $1.key })" as NSString
        if let font = cache.object(forKey: key) { return font }
        let face = nearest(typeface, weight, italic: italic)
        guard let base = UIFont(name: face.postScriptName, size: size) else { return .systemFont(ofSize: size) }
        var variations: [Int: CGFloat] = [:]
        if isVariable(face) && weight != 400 {
            variations[axis("wght")] = min(max(weight, face.weights.lowerBound), face.weights.upperBound)
        }
        for (name, value) in axes { variations[axis(name)] = value }
        if typeface.opticalSizing { variations[axis("opsz")] = size }
        // No italic face: slanted, as a browser makes one.
        let slant = italic && !face.italic
        var font = base
        if !variations.isEmpty || slant {
            var descriptor = base.fontDescriptor
            if !variations.isEmpty { descriptor = descriptor.addingAttributes([variationKey: variations]) }
            if slant { descriptor = descriptor.withMatrix(CGAffineTransform(a: 1, b: 0, c: 0.2, d: 1, tx: 0, ty: 0)) }
            font = UIFont(descriptor: descriptor, size: size)
        }
        cache.setObject(font, forKey: key)
        return font
    }

    private static let variationKey = UIFontDescriptor.AttributeName(rawValue: kCTFontVariationAttribute as String)

    /// A variation axis's tag ("wght") as Core Text names it: its four letters as one number.
    private static func axis(_ tag: String) -> Int {
        tag.utf8.reduce(0) { $0 << 8 | Int($1) }
    }

    /// Scaled with the user's text size.
    static func scaled(_ font: UIFont, as style: UIFont.TextStyle = .body) -> UIFont {
        UIFontMetrics(forTextStyle: style).scaledFont(for: font)
    }
}

extension Font {
    /// Named styles in the theme's body face, sized for phones (the web app's mobile sizes),
    /// scaling with Dynamic Type. Titles are `titleFont`: SwiftUI's own fonts can't carry a
    /// face's axes.
    static var notesBody: Font { sans(15, .body) }
    static var notesLede: Font { sans(16, .body) }
    static var notesRowTitle: Font { sans(16, .body, weight: 500) }
    static var notesRowMeta: Font { sans(14, .subheadline) }
    /// A small quiet label above a heading or a group (web: Kicker).
    static var notesKicker: Font { sans(13, .footnote, weight: 600) }
    static var notesLabel: Font { sans(13, .footnote, weight: 500) }
    static var notesSmall: Font { sans(13, .footnote) }
    static var notesButton: Font { sans(15, .body, weight: 600) }
    static let notesMono = Font.custom("JetBrainsMono-Regular", size: 14, relativeTo: .body)
    /// The wordmark: lowercase and monospaced, like the daemon it's named after.
    static let notesBrand = Font.custom("JetBrainsMono-Regular", size: 20, relativeTo: .title3).weight(.medium)

    /// The theme's body face at a weight: a variable face takes it as its weight, a family of
    /// single-weight files as the file that has it.
    static func sans(_ size: CGFloat, _ style: Font.TextStyle, weight: CGFloat = 400, theme: NotesTheme? = nil)
        -> Font
    {
        let face = Typeface.nearest((theme ?? CurrentTheme.theme).body, weight, italic: false)
        let font = Font.custom(face.postScriptName, size: size, relativeTo: style)
        guard Typeface.isVariable(face), weight != 400 else { return font }
        return font.weight(Font.Weight(weight))
    }
}

extension Font.Weight {
    fileprivate init(_ weight: CGFloat) {
        self =
            switch weight {
            case ..<450: .regular
            case ..<550: .medium
            case ..<650: .semibold
            case ..<750: .bold
            case ..<850: .heavy
            default: .black
            }
    }
}

extension View {
    /// A pill floating over the content (the formatting bar, the undo toast): Liquid Glass in a
    /// style that blurs, its solid panel and pop shadow in one that doesn't.
    func floatingPanel() -> some View {
        modifier(FloatingPanel())
    }
}

private struct FloatingPanel: ViewModifier {
    @Environment(\.colorScheme) private var scheme

    func body(content: Content) -> some View {
        let theme = CurrentTheme.theme
        if theme.blurs.panel != nil {
            content.glassEffect(.regular, in: .capsule)
        } else {
            let pop = theme.shadows.shadowPop
            content
                .background(Color(Palette.glassHi), in: .capsule)
                .overlay(Capsule().strokeBorder(Color.edge))
                .background {
                    // CSS's blur is twice SwiftUI's radius; its spread grows or shrinks the shape.
                    ForEach(Array((scheme == .dark ? pop.dark : pop.light).enumerated()), id: \.offset) { _, layer in
                        Capsule().fill(Color(Palette.glassHi)).padding(-layer.spread)
                            .shadow(color: Color(argb: layer.color), radius: layer.blur / 2, x: layer.x, y: layer.y)
                    }
                }
        }
    }
}

extension Color {
    fileprivate init(argb: UInt32) {
        self.init(
            .sRGB, red: Double((argb >> 16) & 0xFF) / 255, green: Double((argb >> 8) & 0xFF) / 255,
            blue: Double(argb & 0xFF) / 255, opacity: Double((argb >> 24) & 0xFF) / 255)
    }
}

/// A title's size and the Dynamic Type style it scales like.
struct TitleStyle {
    let size: CGFloat
    let style: UIFont.TextStyle

    static let display = TitleStyle(size: 34, style: .largeTitle)
    static let heading = TitleStyle(size: 30, style: .title1)
    static let title = TitleStyle(size: 22, style: .title2)
}

extension View {
    /// Set as a title in the theme's title face, scaling with Dynamic Type.
    func titleFont(_ title: TitleStyle) -> some View {
        modifier(TitleFont(title: title))
    }
}

private struct TitleFont: ViewModifier {
    let title: TitleStyle
    @Environment(\.dynamicTypeSize) private var typeSize

    func body(content: Content) -> some View {
        let traits = UITraitCollection(preferredContentSizeCategory: UIContentSizeCategory(typeSize))
        let font = UIFontMetrics(forTextStyle: title.style).scaledFont(
            for: Typeface.title(title.size), compatibleWith: traits)
        content.font(Font(font as CTFont)).tracking(Typeface.titleKern(font))
    }
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
                configuration.isPressed ? Color.on : selected ? Color.on : .clear,
                in: .rect(cornerRadius: corner(radius))
            )
            .contentShape(.rect(cornerRadius: corner(radius)))
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
                    RoundedRectangle(cornerRadius: corner(16), style: .continuous).fill(Color.accent)
                        .overlay(alignment: .bottom) {
                            Rectangle().fill(Color.accentDeep).frame(height: 2.5)
                        }
                        .clipShape(.rect(cornerRadius: corner(16), style: .continuous))
                }
                .shadow(color: Color.accentDeep.opacity(0.35), radius: 10, y: 4)
        }
        .buttonStyle(PressScaleStyle())
        .accessibilityLabel("New note")
    }
}
