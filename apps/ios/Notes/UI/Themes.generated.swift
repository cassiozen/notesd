// Generated from shared/themes by shared/themes/generate.ts. Change the JSON there and run `mise run themes`.
// The format and what each value means: docs/contributing/themes.md.

import UIKit

/// A color in light and dark appearance, each 0xAARRGGBB.
struct ThemeColor: Sendable, Equatable {
    let light: UInt32
    let dark: UInt32

    /// Follows the trait collection it's drawn in.
    var uiColor: UIColor {
        UIColor { $0.userInterfaceStyle == .dark ? Self.make(dark) : Self.make(light) }
    }

    private static func make(_ argb: UInt32) -> UIColor {
        UIColor(
            red: CGFloat((argb >> 16) & 0xFF) / 255, green: CGFloat((argb >> 8) & 0xFF) / 255,
            blue: CGFloat(argb & 0xFF) / 255, alpha: CGFloat((argb >> 24) & 0xFF) / 255)
    }
}

/// One layer of a shadow, as CSS's box-shadow describes it. color is 0xAARRGGBB.
struct ThemeShadowLayer: Sendable, Equatable {
    let x: CGFloat
    let y: CGFloat
    let blur: CGFloat
    let spread: CGFloat
    let color: UInt32
    let inset: Bool
}

/// A shadow's layers in each appearance, the first on top; none is an empty list.
struct ThemeShadow: Sendable, Equatable {
    let light: [ThemeShadowLayer]
    let dark: [ThemeShadowLayer]
}

/// A see-through panel's blur: the closest system material stands in for it.
struct ThemeBlur: Sendable, Equatable {
    let radius: CGFloat
    let saturation: CGFloat
}

enum ThemeCurve: Sendable, Equatable {
    case cubicBezier(CGFloat, CGFloat, CGFloat, CGFloat)
    /// Evenly spaced points of a curve: a spring.
    case linear([CGFloat])
}

enum ThemeCorner: Sendable, Equatable {
    case circle
    case radius(CGFloat)
}

/// A ticked box: a dot inside an accent ring, or filled.
enum ThemeCheckMark: Sendable, Equatable {
    case dot
    case fill
}

/// In em.
struct ThemeInsets: Sendable, Equatable {
    let vertical: CGFloat
    let horizontal: CGFloat
}

struct ThemeFontFace: Sendable, Equatable {
    let postScriptName: String
    /// One weight, or the range a variable font covers.
    let weights: ClosedRange<CGFloat>
    let italic: Bool
}

struct ThemeTypeface: Sendable, Equatable {
    let name: String
    let faces: [ThemeFontFace]
    /// Has an opsz axis: set it to the point size, as browsers do by themselves.
    let opticalSizing: Bool
}

/// The title face and how titles are set in it. Quotes use it too, lighter.
struct ThemeTitleFont: Sendable, Equatable {
    let typeface: ThemeTypeface
    let weight: CGFloat
    let italic: Bool
    let quoteWeight: CGFloat
    let quoteItalic: Bool
    /// Variation axes for titles, besides weight and optical size.
    let axes: [String: CGFloat]
    /// Multiplies title sizes, which were written for Instrument Serif, a narrow face.
    let scale: CGFloat
    /// Added letter spacing, in em.
    let tracking: CGFloat
}

/// Palette roles, then the style's materials resolved on that palette.
struct ThemeColors: Sendable, Equatable {
    let bg: ThemeColor
    let paper: ThemeColor
    let ink: ThemeColor
    let ink2: ThemeColor
    let ink3: ThemeColor
    let scrim: ThemeColor
    let surface: ThemeColor
    let line: ThemeColor
    let shade: ThemeColor
    let blob1: ThemeColor
    let accent: ThemeColor
    let accentDeep: ThemeColor
    let onAccent: ThemeColor
    let tint: ThemeColor
    let link: ThemeColor
    let focus: ThemeColor
    let solid: ThemeColor
    let onSolid: ThemeColor
    let danger: ThemeColor
    let dangerBg: ThemeColor
    let good: ThemeColor
    let goodBg: ThemeColor
    let errBg: ThemeColor
    let errEdge: ThemeColor
    let errInk: ThemeColor
    let warnBg: ThemeColor
    let warnEdge: ThemeColor
    let warnInk: ThemeColor
    let statusSaved: ThemeColor
    let statusUnsaved: ThemeColor
    let statusSaving: ThemeColor
    let statusFailed: ThemeColor
    let statusConflict: ThemeColor
    let code: ThemeColor
    let codeInk: ThemeColor
    let codeDim: ThemeColor
    let syntaxKeyword: ThemeColor
    let syntaxString: ThemeColor
    let syntaxNumber: ThemeColor
    let syntaxTitle: ThemeColor
    let syntaxMeta: ThemeColor
    let syntaxDeletion: ThemeColor
    let checkInk: ThemeColor
    let sidebar: ThemeColor
    let glassHi: ThemeColor
    let edge: ThemeColor
    let field: ThemeColor
    let chip: ThemeColor
    let hair: ThemeColor
    let hairStrong: ThemeColor
    let hover: ThemeColor
    /// A selected row.
    let on: ThemeColor
    /// The glow behind the window; nil for none.
    let wash: ThemeColor?
    /// Behind #tags; nil for none.
    let tagBackground: ThemeColor?
    /// #tags' text; nil for the text around them.
    let tagInk: ThemeColor?
    /// Selected text; nil for the system's own.
    let selection: ThemeColor?
}

struct ThemeShadows: Sendable, Equatable {
    let inset: ThemeShadow
    let shadow: ThemeShadow
    let shadowLg: ThemeShadow
    let shadowPop: ThemeShadow
    let shadowBar: ThemeShadow
    let shadowToast: ThemeShadow
    let shadowThumb: ThemeShadow
    let focusRing: ThemeShadow
}

struct ThemeBlurs: Sendable, Equatable {
    let panel: ThemeBlur?
    let pop: ThemeBlur?
    let dialog: ThemeBlur?
    let scrim: ThemeBlur?
}

struct ThemeShape: Sendable, Equatable {
    /// Multiplies every corner radius.
    let radiusScale: CGFloat
    let checkCorner: ThemeCorner
    let checkMark: ThemeCheckMark
    let tagRadius: CGFloat
    let tagPadding: ThemeInsets
    let doneStrikethrough: Bool
}

struct ThemeMotion: Sendable, Equatable {
    /// Things that appear.
    let spring: ThemeCurve
    let easeOut: ThemeCurve
}

struct NotesTheme: Sendable, Equatable, Identifiable {
    let id: String
    let name: String
    let mood: String
    let colors: ThemeColors
    let shadows: ThemeShadows
    let blurs: ThemeBlurs
    let shape: ThemeShape
    let motion: ThemeMotion
    let title: ThemeTitleFont
    /// Everything that isn't a title.
    let body: ThemeTypeface
}

enum Themes {
    static let all: [NotesTheme] = [amber, nocturne, carta, den]
    static let fallback = amber

    /// fallback for an id no theme has (a stored one from a build that had it).
    static func theme(id: String) -> NotesTheme {
        all.first { $0.id == id } ?? fallback
    }

    static let amber = NotesTheme(
        id: "amber",
        name: "Amber",
        mood: "Afternoon light on paper",
        colors: ThemeColors(
            bg: ThemeColor(light: 0xFFF5_F3F1, dark: 0xFF12_1416),
            paper: ThemeColor(light: 0xFFFC_FBFA, dark: 0xFF1A_1C1E),
            ink: ThemeColor(light: 0xFF21_1F1C, dark: 0xFFE9_EBEE),
            ink2: ThemeColor(light: 0xFF50_4C4A, dark: 0xFFB2_B4B7),
            ink3: ThemeColor(light: 0xFF66_6260, dark: 0xFF93_9598),
            scrim: ThemeColor(light: 0x6BEC_EAE6, dark: 0x800B_0D0E),
            surface: ThemeColor(light: 0xFFFE_FDFC, dark: 0xFF2D_2F31),
            line: ThemeColor(light: 0xFF2D_2823, dark: 0xFFFF_FFFF),
            shade: ThemeColor(light: 0xFF30_2821, dark: 0xFF00_0000),
            blob1: ThemeColor(light: 0xFFF4_D9A8, dark: 0xFF62_4821),
            accent: ThemeColor(light: 0xFFEF_B146, dark: 0xFFEF_B146),
            accentDeep: ThemeColor(light: 0xFFDD_9314, dark: 0xFFDD_9314),
            onAccent: ThemeColor(light: 0xFF1F_1915, dark: 0xFF1F_1915),
            tint: ThemeColor(light: 0xFFBE_7200, dark: 0xFFF0_BA59),
            link: ThemeColor(light: 0xFF87_4E00, dark: 0xFFEF_BA64),
            focus: ThemeColor(light: 0x8CDD_9314, dark: 0x8CDD_9314),
            solid: ThemeColor(light: 0xFF24_211F, dark: 0xFFE6_E8EA),
            onSolid: ThemeColor(light: 0xFFF9_F8F6, dark: 0xFF14_1618),
            danger: ThemeColor(light: 0xFFAC_312C, dark: 0xFFFB_8276),
            dangerBg: ThemeColor(light: 0x1AD2_4D45, dark: 0x29D2_4D45),
            good: ThemeColor(light: 0xFF0E_492A, dark: 0xFF9E_E0B4),
            goodBg: ThemeColor(light: 0xBFC0_EACD, dark: 0x8024_5337),
            errBg: ThemeColor(light: 0xD9FF_E8E3, dark: 0xB34D_2620),
            errEdge: ThemeColor(light: 0xFFFD_CAC0, dark: 0xFF72_3B32),
            errInk: ThemeColor(light: 0xFF78_2B21, dark: 0xFFFD_CAC0),
            warnBg: ThemeColor(light: 0xE0FF_EAD3, dark: 0xBF48_2F1A),
            warnEdge: ThemeColor(light: 0xFFF6_CBA5, dark: 0xFF72_4B2B),
            warnInk: ThemeColor(light: 0xFF62_3512, dark: 0xFFF8_D8B4),
            statusSaved: ThemeColor(light: 0xFF56_AE6C, dark: 0xFF56_AE6C),
            statusUnsaved: ThemeColor(light: 0xFF96_918C, dark: 0xFF96_918C),
            statusSaving: ThemeColor(light: 0xFFE8_A127, dark: 0xFFE8_A127),
            statusFailed: ThemeColor(light: 0xFFE2_4942, dark: 0xFFE2_4942),
            statusConflict: ThemeColor(light: 0xFFE6_7339, dark: 0xFFE6_7339),
            code: ThemeColor(light: 0xED24_211F, dark: 0xD906_0709),
            codeInk: ThemeColor(light: 0xFFD8_D4CD, dark: 0xFFD8_D4CD),
            codeDim: ThemeColor(light: 0xFF8F_8C85, dark: 0xFF8F_8C85),
            syntaxKeyword: ThemeColor(light: 0xFFC3_A5F9, dark: 0xFFC3_A5F9),
            syntaxString: ThemeColor(light: 0xFF8B_D28D, dark: 0xFF8B_D28D),
            syntaxNumber: ThemeColor(light: 0xFFFB_A962, dark: 0xFFFB_A962),
            syntaxTitle: ThemeColor(light: 0xFF75_CAF2, dark: 0xFF75_CAF2),
            syntaxMeta: ThemeColor(light: 0xFFF8_A4A3, dark: 0xFFF8_A4A3),
            syntaxDeletion: ThemeColor(light: 0xFFF4_7C70, dark: 0xFFF4_7C70),
            checkInk: ThemeColor(light: 0xFF26_201C, dark: 0xFF26_201C),
            sidebar: ThemeColor(light: 0xFFF5_F3F1, dark: 0xFF12_1416),
            glassHi: ThemeColor(light: 0xE0FE_FDFC, dark: 0xE02D_2F31),
            edge: ThemeColor(light: 0xD9FF_FFFF, dark: 0x14FF_FFFF),
            field: ThemeColor(light: 0xC7FF_FFFF, dark: 0x0FFF_FFFF),
            chip: ThemeColor(light: 0x9EFF_FFFF, dark: 0x12FF_FFFF),
            hair: ThemeColor(light: 0x172D_2823, dark: 0x14FF_FFFF),
            hairStrong: ThemeColor(light: 0x292D_2823, dark: 0x29FF_FFFF),
            hover: ThemeColor(light: 0x122D_2823, dark: 0x12FF_FFFF),
            on: ThemeColor(light: 0x1C2D_2823, dark: 0x21FF_FFFF),
            wash: ThemeColor(light: 0x2EF4_D9A8, dark: 0x3362_4821),
            tagBackground: ThemeColor(light: 0x4DEF_B146, dark: 0x4DEF_B146),
            tagInk: nil,
            selection: nil
        ),
        shadows: ThemeShadows(
            inset: ThemeShadow(
                light: [ThemeShadowLayer(x: 0, y: 1, blur: 0, spread: 0, color: 0xE6FF_FFFF, inset: true)],
                dark: [ThemeShadowLayer(x: 0, y: 1, blur: 0, spread: 0, color: 0x0FFF_FFFF, inset: true)]
            ),
            shadow: ThemeShadow(
                light: [ThemeShadowLayer(x: 0, y: 12, blur: 32, spread: -18, color: 0x3830_2821, inset: false)],
                dark: [ThemeShadowLayer(x: 0, y: 14, blur: 34, spread: -18, color: 0xA600_0000, inset: false)]
            ),
            shadowLg: ThemeShadow(
                light: [ThemeShadowLayer(x: 0, y: 40, blur: 90, spread: -34, color: 0x5930_2821, inset: false)],
                dark: [ThemeShadowLayer(x: 0, y: 40, blur: 90, spread: -30, color: 0xBF00_0000, inset: false)]
            ),
            shadowPop: ThemeShadow(
                light: [ThemeShadowLayer(x: 0, y: 24, blur: 50, spread: -16, color: 0x5900_0000, inset: false)],
                dark: [ThemeShadowLayer(x: 0, y: 24, blur: 50, spread: -16, color: 0x5900_0000, inset: false)]
            ),
            shadowBar: ThemeShadow(
                light: [ThemeShadowLayer(x: 0, y: 24, blur: 50, spread: -16, color: 0x4D00_0000, inset: false)],
                dark: [ThemeShadowLayer(x: 0, y: 24, blur: 50, spread: -16, color: 0x4D00_0000, inset: false)]
            ),
            shadowToast: ThemeShadow(
                light: [ThemeShadowLayer(x: 0, y: 16, blur: 40, spread: -12, color: 0x6600_0000, inset: false)],
                dark: [ThemeShadowLayer(x: 0, y: 16, blur: 40, spread: -12, color: 0x6600_0000, inset: false)]
            ),
            shadowThumb: ThemeShadow(
                light: [ThemeShadowLayer(x: 0, y: 1, blur: 3, spread: 0, color: 0x1F00_0000, inset: false)],
                dark: [ThemeShadowLayer(x: 0, y: 1, blur: 3, spread: 0, color: 0x1F00_0000, inset: false)]
            ),
            focusRing: ThemeShadow(
                light: [ThemeShadowLayer(x: 0, y: 0, blur: 0, spread: 3, color: 0x8CDD_9314, inset: false)],
                dark: [ThemeShadowLayer(x: 0, y: 0, blur: 0, spread: 3, color: 0x8CDD_9314, inset: false)]
            )
        ),
        blurs: ThemeBlurs(
            panel: ThemeBlur(radius: 18, saturation: 1.3),
            pop: ThemeBlur(radius: 24, saturation: 1.4),
            dialog: ThemeBlur(radius: 24, saturation: 1.3),
            scrim: ThemeBlur(radius: 10, saturation: 1)
        ),
        shape: ThemeShape(
            radiusScale: 1,
            checkCorner: .circle,
            checkMark: .dot,
            tagRadius: 6,
            tagPadding: ThemeInsets(vertical: 0.05, horizontal: 0.3),
            doneStrikethrough: false
        ),
        motion: ThemeMotion(
            spring: .linear([
                0, 0.084, 0.266, 0.471, 0.657, 0.805, 0.911, 0.979, 1.017,
                1.035, 1.038, 1.034, 1.027, 1.019, 1.012, 1.007, 1.003, 1,
            ]),
            easeOut: .cubicBezier(0.2, 0.8, 0.2, 1)
        ),
        title: ThemeTitleFont(
            typeface: ThemeTypeface(
                name: "Instrument Serif",
                faces: [
                    ThemeFontFace(postScriptName: "InstrumentSerif-Regular", weights: 400...400, italic: false),
                    ThemeFontFace(postScriptName: "InstrumentSerif-Italic", weights: 400...400, italic: true),
                ],
                opticalSizing: false
            ),
            weight: 400,
            italic: false,
            quoteWeight: 400,
            quoteItalic: true,
            axes: [:],
            scale: 1,
            tracking: 0
        ),
        body: ThemeTypeface(
            name: "Instrument Sans",
            faces: [
                ThemeFontFace(postScriptName: "InstrumentSans-Regular", weights: 400...700, italic: false),
                ThemeFontFace(postScriptName: "InstrumentSans-Italic", weights: 400...700, italic: true),
            ],
            opticalSizing: false
        )
    )

    static let nocturne = NotesTheme(
        id: "nocturne",
        name: "Nocturne",
        mood: "The city, long after midnight",
        colors: ThemeColors(
            bg: ThemeColor(light: 0xFFE8_E9EF, dark: 0xFF13_141B),
            paper: ThemeColor(light: 0xFFF6_F6F9, dark: 0xFF19_1A24),
            ink: ThemeColor(light: 0xFF26_2A45, dark: 0xFFD0_D5EA),
            ink2: ThemeColor(light: 0xFF4A_4F70, dark: 0xFFA3_ABCB),
            ink3: ThemeColor(light: 0xFF5D_6286, dark: 0xFF7C_84AB),
            scrim: ThemeColor(light: 0x80E1_E3EC, dark: 0x8C08_090E),
            surface: ThemeColor(light: 0xFFFB_FBFD, dark: 0xFF1F_2130),
            line: ThemeColor(light: 0xFF26_2A45, dark: 0xFFA9_B1D6),
            shade: ThemeColor(light: 0xFF1E_2450, dark: 0xFF00_0004),
            blob1: ThemeColor(light: 0xFFC9_D1EF, dark: 0xFF28_3163),
            accent: ThemeColor(light: 0xFFB8_336F, dark: 0xFFF0_A3C6),
            accentDeep: ThemeColor(light: 0xFF96_2A5B, dark: 0xFFDF_88B1),
            onAccent: ThemeColor(light: 0xFFFF_FFFF, dark: 0xFF12_131B),
            tint: ThemeColor(light: 0xFFB8_336F, dark: 0xFFF2_ADCC),
            link: ThemeColor(light: 0xFFA0_2C61, dark: 0xFFF5_B3D0),
            focus: ThemeColor(light: 0x4DB8_336F, dark: 0x66F0_A3C6),
            solid: ThemeColor(light: 0xFF26_2A45, dark: 0xFFD0_D5EA),
            onSolid: ThemeColor(light: 0xFFF6_F6F9, dark: 0xFF13_141B),
            danger: ThemeColor(light: 0xFFB8_324F, dark: 0xFFEF_8399),
            dangerBg: ThemeColor(light: 0x17B8_324F, dark: 0x21EF_8399),
            good: ThemeColor(light: 0xFF3F_6A1E, dark: 0xFFA9_D27C),
            goodBg: ThemeColor(light: 0x3878_AA50, dark: 0x249E_CE6A),
            errBg: ThemeColor(light: 0xE6FA_E4EA, dark: 0xB346_1E2D),
            errEdge: ThemeColor(light: 0xFFEA_B8C4, dark: 0xFF5E_3346),
            errInk: ThemeColor(light: 0xFF8A_2139, dark: 0xFFFF_C9D3),
            warnBg: ThemeColor(light: 0xE6F8_ECD6, dark: 0xB346_371E),
            warnEdge: ThemeColor(light: 0xFFE6_C99A, dark: 0xFF5E_4A2A),
            warnInk: ThemeColor(light: 0xFF6A_4710, dark: 0xFFF2_D6A6),
            statusSaved: ThemeColor(light: 0xFF5F_9A35, dark: 0xFF9E_CE6A),
            statusUnsaved: ThemeColor(light: 0xFF9A_9FBA, dark: 0xFF56_5F89),
            statusSaving: ThemeColor(light: 0xFFB8_336F, dark: 0xFFF0_A3C6),
            statusFailed: ThemeColor(light: 0xFFC4_304F, dark: 0xFFF7_768E),
            statusConflict: ThemeColor(light: 0xFFC0_7A20, dark: 0xFFE0_AF68),
            code: ThemeColor(light: 0xFF1C_1D29, dark: 0xFF10_1118),
            codeInk: ThemeColor(light: 0xFFC0_CAF5, dark: 0xFFC0_CAF5),
            codeDim: ThemeColor(light: 0xFF6A_72A0, dark: 0xFF6A_72A0),
            syntaxKeyword: ThemeColor(light: 0xFFBB_9AF7, dark: 0xFFBB_9AF7),
            syntaxString: ThemeColor(light: 0xFF9E_CE6A, dark: 0xFF9E_CE6A),
            syntaxNumber: ThemeColor(light: 0xFFFF_9E64, dark: 0xFFFF_9E64),
            syntaxTitle: ThemeColor(light: 0xFF7A_A2F7, dark: 0xFF7A_A2F7),
            syntaxMeta: ThemeColor(light: 0xFF7D_CFFF, dark: 0xFF7D_CFFF),
            syntaxDeletion: ThemeColor(light: 0xFFF7_768E, dark: 0xFFF7_768E),
            checkInk: ThemeColor(light: 0xFFFF_FFFF, dark: 0xFF12_131B),
            sidebar: ThemeColor(light: 0xFFE8_E9EF, dark: 0xFF13_141B),
            glassHi: ThemeColor(light: 0xD6FB_FBFD, dark: 0xD61F_2130),
            edge: ThemeColor(light: 0x1226_2A45, dark: 0x0FA9_B1D6),
            field: ThemeColor(light: 0xB3FF_FFFF, dark: 0x0DA9_B1D6),
            chip: ThemeColor(light: 0x8CFF_FFFF, dark: 0x0FA9_B1D6),
            hair: ThemeColor(light: 0x1426_2A45, dark: 0x14A9_B1D6),
            hairStrong: ThemeColor(light: 0x2626_2A45, dark: 0x26A9_B1D6),
            hover: ThemeColor(light: 0x0F26_2A45, dark: 0x0FA9_B1D6),
            on: ThemeColor(light: 0x1A26_2A45, dark: 0x1AA9_B1D6),
            wash: nil,
            tagBackground: ThemeColor(light: 0x29B8_336F, dark: 0x29F0_A3C6),
            tagInk: nil,
            selection: ThemeColor(light: 0x4DB8_336F, dark: 0x4DF0_A3C6)
        ),
        shadows: ThemeShadows(
            inset: ThemeShadow(
                light: [ThemeShadowLayer(x: 0, y: 1, blur: 0, spread: 0, color: 0xE6FF_FFFF, inset: true)],
                dark: [ThemeShadowLayer(x: 0, y: 1, blur: 0, spread: 0, color: 0x0EFF_FFFF, inset: true)]
            ),
            shadow: ThemeShadow(
                light: [],
                dark: []
            ),
            shadowLg: ThemeShadow(
                light: [],
                dark: []
            ),
            shadowPop: ThemeShadow(
                light: [ThemeShadowLayer(x: 0, y: 28, blur: 60, spread: -20, color: 0x4D1E_2450, inset: false)],
                dark: [ThemeShadowLayer(x: 0, y: 28, blur: 60, spread: -20, color: 0xBF00_0004, inset: false)]
            ),
            shadowBar: ThemeShadow(
                light: [ThemeShadowLayer(x: 0, y: 28, blur: 60, spread: -20, color: 0x4D1E_2450, inset: false)],
                dark: [ThemeShadowLayer(x: 0, y: 28, blur: 60, spread: -20, color: 0xBF00_0004, inset: false)]
            ),
            shadowToast: ThemeShadow(
                light: [ThemeShadowLayer(x: 0, y: 28, blur: 60, spread: -20, color: 0x4D1E_2450, inset: false)],
                dark: [ThemeShadowLayer(x: 0, y: 28, blur: 60, spread: -20, color: 0xBF00_0004, inset: false)]
            ),
            shadowThumb: ThemeShadow(
                light: [
                    ThemeShadowLayer(x: 0, y: 1, blur: 0, spread: 0, color: 0xE6FF_FFFF, inset: true),
                    ThemeShadowLayer(x: 0, y: 1, blur: 3, spread: 0, color: 0x291E_2450, inset: false),
                ],
                dark: [
                    ThemeShadowLayer(x: 0, y: 1, blur: 0, spread: 0, color: 0x12FF_FFFF, inset: true),
                    ThemeShadowLayer(x: 0, y: 1, blur: 3, spread: 0, color: 0xB300_0004, inset: false),
                ]
            ),
            focusRing: ThemeShadow(
                light: [ThemeShadowLayer(x: 0, y: 0, blur: 0, spread: 3, color: 0x4DB8_336F, inset: false)],
                dark: [ThemeShadowLayer(x: 0, y: 0, blur: 0, spread: 3, color: 0x66F0_A3C6, inset: false)]
            )
        ),
        blurs: ThemeBlurs(
            panel: ThemeBlur(radius: 28, saturation: 1.15),
            pop: ThemeBlur(radius: 32, saturation: 1.2),
            dialog: ThemeBlur(radius: 32, saturation: 1.2),
            scrim: ThemeBlur(radius: 14, saturation: 1)
        ),
        shape: ThemeShape(
            radiusScale: 0.86,
            checkCorner: .circle,
            checkMark: .dot,
            tagRadius: 6,
            tagPadding: ThemeInsets(vertical: 0.05, horizontal: 0.3),
            doneStrikethrough: false
        ),
        motion: ThemeMotion(
            spring: .cubicBezier(0.22, 1, 0.36, 1),
            easeOut: .cubicBezier(0.22, 1, 0.36, 1)
        ),
        title: ThemeTitleFont(
            typeface: ThemeTypeface(
                name: "Shippori Mincho",
                faces: [
                    ThemeFontFace(postScriptName: "ShipporiMincho-Regular", weights: 400...400, italic: false),
                    ThemeFontFace(postScriptName: "ShipporiMincho-Medium", weights: 500...500, italic: false),
                ],
                opticalSizing: false
            ),
            weight: 500,
            italic: false,
            quoteWeight: 400,
            quoteItalic: false,
            axes: [:],
            scale: 0.9,
            tracking: 0
        ),
        body: ThemeTypeface(
            name: "Zen Kaku Gothic New",
            faces: [
                ThemeFontFace(postScriptName: "ZenKakuGothicNew-Regular", weights: 400...400, italic: false),
                ThemeFontFace(postScriptName: "ZenKakuGothicNew-Medium", weights: 500...500, italic: false),
                ThemeFontFace(postScriptName: "ZenKakuGothicNew-Bold", weights: 700...700, italic: false),
            ],
            opticalSizing: false
        )
    )

    static let carta = NotesTheme(
        id: "carta",
        name: "Carta",
        mood: "Black ink on good paper",
        colors: ThemeColors(
            bg: ThemeColor(light: 0xFFEC_EBE7, dark: 0xFF0A_0A0A),
            paper: ThemeColor(light: 0xFFFB_FAF7, dark: 0xFF13_1313),
            ink: ThemeColor(light: 0xFF12_1212, dark: 0xFFEC_EBE6),
            ink2: ThemeColor(light: 0xFF3D_3D3B, dark: 0xFFB4_B3AE),
            ink3: ThemeColor(light: 0xFF5C_5C59, dark: 0xFF8F_8E8A),
            scrim: ThemeColor(light: 0x99EC_EBE7, dark: 0x9900_0000),
            surface: ThemeColor(light: 0xFFFD_FCFA, dark: 0xFF1A_1A1A),
            line: ThemeColor(light: 0xFF12_1212, dark: 0xFFEC_EBE6),
            shade: ThemeColor(light: 0xFF14_120F, dark: 0xFF00_0000),
            blob1: ThemeColor(light: 0xFFE2_E1DC, dark: 0xFF1C_1C1C),
            accent: ThemeColor(light: 0xFF12_1212, dark: 0xFFEC_EBE6),
            accentDeep: ThemeColor(light: 0xFF00_0000, dark: 0xFFFF_FFFF),
            onAccent: ThemeColor(light: 0xFFFB_FAF7, dark: 0xFF0A_0A0A),
            tint: ThemeColor(light: 0xFF12_1212, dark: 0xFFEC_EBE6),
            link: ThemeColor(light: 0xFF5C_5C59, dark: 0xFFA8_A7A2),
            focus: ThemeColor(light: 0x4012_1212, dark: 0x4DEC_EBE6),
            solid: ThemeColor(light: 0xFF12_1212, dark: 0xFFEC_EBE6),
            onSolid: ThemeColor(light: 0xFFFB_FAF7, dark: 0xFF0A_0A0A),
            danger: ThemeColor(light: 0xFFB3_261E, dark: 0xFFF2_847A),
            dangerBg: ThemeColor(light: 0x12B3_261E, dark: 0x1FF2_847A),
            good: ThemeColor(light: 0xFF2F_4A36, dark: 0xFFB9_CBBD),
            goodBg: ThemeColor(light: 0x1A2F_4A36, dark: 0x1FB9_CBBD),
            errBg: ThemeColor(light: 0xE6F6_E6E3, dark: 0xB346_1C19),
            errEdge: ThemeColor(light: 0xFFE3_BDB7, dark: 0xFF5E_302B),
            errInk: ThemeColor(light: 0xFF7D_1D17, dark: 0xFFFF_CFC9),
            warnBg: ThemeColor(light: 0xE6F0_ECE2, dark: 0xCC32_2E24),
            warnEdge: ThemeColor(light: 0xFFD8_D0BC, dark: 0xFF55_503F),
            warnInk: ThemeColor(light: 0xFF4A_4430, dark: 0xFFEC_E4CC),
            statusSaved: ThemeColor(light: 0xFF9A_9A96, dark: 0xFF6E_6D6A),
            statusUnsaved: ThemeColor(light: 0xFFCF_CFCB, dark: 0xFF3A_3A38),
            statusSaving: ThemeColor(light: 0xFF12_1212, dark: 0xFFEC_EBE6),
            statusFailed: ThemeColor(light: 0xFFC6_2D22, dark: 0xFFC6_2D22),
            statusConflict: ThemeColor(light: 0xFF7A_6A3A, dark: 0xFFC9_B47A),
            code: ThemeColor(light: 0xFF15_1515, dark: 0xFF05_0505),
            codeInk: ThemeColor(light: 0xFFE4_E3DE, dark: 0xFFE4_E3DE),
            codeDim: ThemeColor(light: 0xFF85_8480, dark: 0xFF85_8480),
            syntaxKeyword: ThemeColor(light: 0xFFFF_FFFF, dark: 0xFFFF_FFFF),
            syntaxString: ThemeColor(light: 0xFFBD_BCB7, dark: 0xFFBD_BCB7),
            syntaxNumber: ThemeColor(light: 0xFFFF_FFFF, dark: 0xFFFF_FFFF),
            syntaxTitle: ThemeColor(light: 0xFFF4_F3EE, dark: 0xFFF4_F3EE),
            syntaxMeta: ThemeColor(light: 0xFFA6_A5A0, dark: 0xFFA6_A5A0),
            syntaxDeletion: ThemeColor(light: 0xFFF2_847A, dark: 0xFFF2_847A),
            checkInk: ThemeColor(light: 0xFFFB_FAF7, dark: 0xFF0A_0A0A),
            sidebar: ThemeColor(light: 0xFFEC_EBE7, dark: 0xFF0A_0A0A),
            glassHi: ThemeColor(light: 0xFFFD_FCFA, dark: 0xFF1A_1A1A),
            edge: ThemeColor(light: 0x2112_1212, dark: 0x21EC_EBE6),
            field: ThemeColor(light: 0xFFFB_FAF7, dark: 0xFF13_1313),
            chip: ThemeColor(light: 0x0A12_1212, dark: 0x0AEC_EBE6),
            hair: ThemeColor(light: 0x1A12_1212, dark: 0x1AEC_EBE6),
            hairStrong: ThemeColor(light: 0x2E12_1212, dark: 0x2EEC_EBE6),
            hover: ThemeColor(light: 0x0D12_1212, dark: 0x0DEC_EBE6),
            on: ThemeColor(light: 0x1412_1212, dark: 0x14EC_EBE6),
            wash: nil,
            tagBackground: nil,
            tagInk: ThemeColor(light: 0xFF5C_5C59, dark: 0xFFA8_A7A2),
            selection: ThemeColor(light: 0x2912_1212, dark: 0x29EC_EBE6)
        ),
        shadows: ThemeShadows(
            inset: ThemeShadow(
                light: [],
                dark: []
            ),
            shadow: ThemeShadow(
                light: [
                    ThemeShadowLayer(x: 0, y: 1, blur: 1, spread: 0, color: 0x0F14_120F, inset: false),
                    ThemeShadowLayer(x: 0, y: 8, blur: 20, spread: -14, color: 0x4714_120F, inset: false),
                ],
                dark: [
                    ThemeShadowLayer(x: 0, y: 1, blur: 1, spread: 0, color: 0x0F00_0000, inset: false),
                    ThemeShadowLayer(x: 0, y: 8, blur: 20, spread: -14, color: 0x9900_0000, inset: false),
                ]
            ),
            shadowLg: ThemeShadow(
                light: [
                    ThemeShadowLayer(x: 0, y: 1, blur: 1, spread: 0, color: 0x1214_120F, inset: false),
                    ThemeShadowLayer(x: 0, y: 14, blur: 32, spread: -22, color: 0x5214_120F, inset: false),
                ],
                dark: [
                    ThemeShadowLayer(x: 0, y: 1, blur: 1, spread: 0, color: 0x1200_0000, inset: false),
                    ThemeShadowLayer(x: 0, y: 14, blur: 32, spread: -22, color: 0xB300_0000, inset: false),
                ]
            ),
            shadowPop: ThemeShadow(
                light: [
                    ThemeShadowLayer(x: 0, y: 1, blur: 2, spread: 0, color: 0x1414_120F, inset: false),
                    ThemeShadowLayer(x: 0, y: 14, blur: 28, spread: -16, color: 0x5214_120F, inset: false),
                ],
                dark: [
                    ThemeShadowLayer(x: 0, y: 1, blur: 2, spread: 0, color: 0x1400_0000, inset: false),
                    ThemeShadowLayer(x: 0, y: 14, blur: 28, spread: -16, color: 0xB300_0000, inset: false),
                ]
            ),
            shadowBar: ThemeShadow(
                light: [
                    ThemeShadowLayer(x: 0, y: 1, blur: 2, spread: 0, color: 0x1414_120F, inset: false),
                    ThemeShadowLayer(x: 0, y: 14, blur: 28, spread: -16, color: 0x5214_120F, inset: false),
                ],
                dark: [
                    ThemeShadowLayer(x: 0, y: 1, blur: 2, spread: 0, color: 0x1400_0000, inset: false),
                    ThemeShadowLayer(x: 0, y: 14, blur: 28, spread: -16, color: 0xB300_0000, inset: false),
                ]
            ),
            shadowToast: ThemeShadow(
                light: [
                    ThemeShadowLayer(x: 0, y: 1, blur: 2, spread: 0, color: 0x1414_120F, inset: false),
                    ThemeShadowLayer(x: 0, y: 14, blur: 28, spread: -16, color: 0x5214_120F, inset: false),
                ],
                dark: [
                    ThemeShadowLayer(x: 0, y: 1, blur: 2, spread: 0, color: 0x1400_0000, inset: false),
                    ThemeShadowLayer(x: 0, y: 14, blur: 28, spread: -16, color: 0xB300_0000, inset: false),
                ]
            ),
            shadowThumb: ThemeShadow(
                light: [
                    ThemeShadowLayer(x: 0, y: 0, blur: 0, spread: 1, color: 0x1A12_1212, inset: false),
                    ThemeShadowLayer(x: 0, y: 1, blur: 1, spread: 0, color: 0x1A14_120F, inset: false),
                ],
                dark: [
                    ThemeShadowLayer(x: 0, y: 0, blur: 0, spread: 1, color: 0x1AEC_EBE6, inset: false),
                    ThemeShadowLayer(x: 0, y: 1, blur: 1, spread: 0, color: 0x1A00_0000, inset: false),
                ]
            ),
            focusRing: ThemeShadow(
                light: [ThemeShadowLayer(x: 0, y: 0, blur: 0, spread: 2, color: 0x4012_1212, inset: false)],
                dark: [ThemeShadowLayer(x: 0, y: 0, blur: 0, spread: 2, color: 0x4DEC_EBE6, inset: false)]
            )
        ),
        blurs: ThemeBlurs(
            panel: nil,
            pop: nil,
            dialog: nil,
            scrim: ThemeBlur(radius: 8, saturation: 1)
        ),
        shape: ThemeShape(
            radiusScale: 0.45,
            checkCorner: .radius(4),
            checkMark: .fill,
            tagRadius: 0,
            tagPadding: ThemeInsets(vertical: 0, horizontal: 0),
            doneStrikethrough: false
        ),
        motion: ThemeMotion(
            spring: .cubicBezier(0.25, 0.8, 0.3, 1),
            easeOut: .cubicBezier(0.25, 0.8, 0.3, 1)
        ),
        title: ThemeTitleFont(
            typeface: ThemeTypeface(
                name: "Playfair",
                faces: [
                    ThemeFontFace(postScriptName: "Playfair-Regular", weights: 400...500, italic: false),
                    ThemeFontFace(postScriptName: "Playfair-Italic", weights: 400...500, italic: true),
                ],
                opticalSizing: true
            ),
            weight: 400,
            italic: false,
            quoteWeight: 400,
            quoteItalic: true,
            axes: ["wdth": 88],
            scale: 1,
            tracking: 0
        ),
        body: ThemeTypeface(
            name: "Hanken Grotesk",
            faces: [
                ThemeFontFace(postScriptName: "HankenGrotesk-Regular", weights: 100...900, italic: false),
                ThemeFontFace(postScriptName: "HankenGrotesk-Italic", weights: 100...900, italic: true),
            ],
            opticalSizing: false
        )
    )

    static let den = NotesTheme(
        id: "den",
        name: "Den",
        mood: "Warm graphite, one honey mark",
        colors: ThemeColors(
            bg: ThemeColor(light: 0xFFF6_F6F5, dark: 0xFF22_2427),
            paper: ThemeColor(light: 0xFFFF_FFFF, dark: 0xFF2A_2C30),
            ink: ThemeColor(light: 0xFF2B_2D30, dark: 0xFFDE_DEDE),
            ink2: ThemeColor(light: 0xFF53_565B, dark: 0xFFB0_B2B5),
            ink3: ThemeColor(light: 0xFF66_696E, dark: 0xFF91_9397),
            scrim: ThemeColor(light: 0xD1F6_F6F5, dark: 0xBF12_1315),
            surface: ThemeColor(light: 0xFFFF_FFFF, dark: 0xFF30_3236),
            line: ThemeColor(light: 0xFF2B_2D30, dark: 0xFFDE_DEDE),
            shade: ThemeColor(light: 0xFF1E_1E20, dark: 0xFF00_0000),
            blob1: ThemeColor(light: 0xFFEC_ECEB, dark: 0xFF2A_2C30),
            accent: ThemeColor(light: 0xFFC8_962F, dark: 0xFFD9_A94F),
            accentDeep: ThemeColor(light: 0xFFA8_7A1F, dark: 0xFFC2_913A),
            onAccent: ThemeColor(light: 0xFF21_1C12, dark: 0xFF1A_1B1D),
            tint: ThemeColor(light: 0xFFB2_842A, dark: 0xFFDC_AE58),
            link: ThemeColor(light: 0xFF8A_6414, dark: 0xFFE2_B766),
            focus: ThemeColor(light: 0x59C8_962F, dark: 0x66D9_A94F),
            solid: ThemeColor(light: 0xFF2B_2D30, dark: 0xFFDE_DEDE),
            onSolid: ThemeColor(light: 0xFFFF_FFFF, dark: 0xFF1C_1D20),
            danger: ThemeColor(light: 0xFFB4_2318, dark: 0xFFF2_847A),
            dangerBg: ThemeColor(light: 0x12B4_2318, dark: 0x1FF2_847A),
            good: ThemeColor(light: 0xFF2F_6B3A, dark: 0xFF9F_D8A8),
            goodBg: ThemeColor(light: 0x1A2F_6B3A, dark: 0x1F9F_D8A8),
            errBg: ThemeColor(light: 0xF2FC_E8E6, dark: 0xCC50_1E1C),
            errEdge: ThemeColor(light: 0xFFF0_C0BB, dark: 0xFF6E_3530),
            errInk: ThemeColor(light: 0xFF86_201A, dark: 0xFFFF_D0C8),
            warnBg: ThemeColor(light: 0xF2FC_F2DC, dark: 0xCC4B_3C19),
            warnEdge: ThemeColor(light: 0xFFEA_D39C, dark: 0xFF6E_5A28),
            warnInk: ThemeColor(light: 0xFF5E_4500, dark: 0xFFF2_DF9C),
            statusSaved: ThemeColor(light: 0xFF4F_9A5C, dark: 0xFF7F_CF8C),
            statusUnsaved: ThemeColor(light: 0xFFB5_B7BA, dark: 0xFF5A_5C60),
            statusSaving: ThemeColor(light: 0xFFC8_962F, dark: 0xFFD9_A94F),
            statusFailed: ThemeColor(light: 0xFFD1_403D, dark: 0xFFD1_403D),
            statusConflict: ThemeColor(light: 0xFFD9_8A1C, dark: 0xFFD9_8A1C),
            code: ThemeColor(light: 0xFF2F_3237, dark: 0xFF1C_1D20),
            codeInk: ThemeColor(light: 0xFFE3_E3E3, dark: 0xFFE3_E3E3),
            codeDim: ThemeColor(light: 0xFF8E_9196, dark: 0xFF8E_9196),
            syntaxKeyword: ThemeColor(light: 0xFFFF_8A85, dark: 0xFFFF_8A85),
            syntaxString: ThemeColor(light: 0xFFA8_D68F, dark: 0xFFA8_D68F),
            syntaxNumber: ThemeColor(light: 0xFFF4_B860, dark: 0xFFF4_B860),
            syntaxTitle: ThemeColor(light: 0xFF8F_BCF0, dark: 0xFF8F_BCF0),
            syntaxMeta: ThemeColor(light: 0xFFC7_A3F0, dark: 0xFFC7_A3F0),
            syntaxDeletion: ThemeColor(light: 0xFFFF_8A85, dark: 0xFFFF_8A85),
            checkInk: ThemeColor(light: 0xFF21_1C12, dark: 0xFF1A_1B1D),
            sidebar: ThemeColor(light: 0xFFEC_ECEB, dark: 0xFF25_272A),
            glassHi: ThemeColor(light: 0xD1FF_FFFF, dark: 0xD130_3236),
            edge: ThemeColor(light: 0x1F2B_2D30, dark: 0x1FDE_DEDE),
            field: ThemeColor(light: 0x0F2B_2D30, dark: 0x0FDE_DEDE),
            chip: ThemeColor(light: 0x0F2B_2D30, dark: 0x0FDE_DEDE),
            hair: ThemeColor(light: 0x1A2B_2D30, dark: 0x1ADE_DEDE),
            hairStrong: ThemeColor(light: 0x2E2B_2D30, dark: 0x2EDE_DEDE),
            hover: ThemeColor(light: 0x0D2B_2D30, dark: 0x0DDE_DEDE),
            on: ThemeColor(light: 0x172B_2D30, dark: 0x17DE_DEDE),
            wash: nil,
            tagBackground: ThemeColor(light: 0x142B_2D30, dark: 0x14DE_DEDE),
            tagInk: nil,
            selection: ThemeColor(light: 0x40C8_962F, dark: 0x40D9_A94F)
        ),
        shadows: ThemeShadows(
            inset: ThemeShadow(
                light: [],
                dark: []
            ),
            shadow: ThemeShadow(
                light: [],
                dark: []
            ),
            shadowLg: ThemeShadow(
                light: [],
                dark: []
            ),
            shadowPop: ThemeShadow(
                light: [
                    ThemeShadowLayer(x: 0, y: 0, blur: 0, spread: 0.5, color: 0x242B_2D30, inset: false),
                    ThemeShadowLayer(x: 0, y: 10, blur: 30, spread: -8, color: 0x401E_1E20, inset: false),
                ],
                dark: [
                    ThemeShadowLayer(x: 0, y: 0, blur: 0, spread: 0.5, color: 0x24DE_DEDE, inset: false),
                    ThemeShadowLayer(x: 0, y: 10, blur: 30, spread: -8, color: 0xA600_0000, inset: false),
                ]
            ),
            shadowBar: ThemeShadow(
                light: [
                    ThemeShadowLayer(x: 0, y: 0, blur: 0, spread: 0.5, color: 0x242B_2D30, inset: false),
                    ThemeShadowLayer(x: 0, y: 10, blur: 30, spread: -8, color: 0x401E_1E20, inset: false),
                ],
                dark: [
                    ThemeShadowLayer(x: 0, y: 0, blur: 0, spread: 0.5, color: 0x24DE_DEDE, inset: false),
                    ThemeShadowLayer(x: 0, y: 10, blur: 30, spread: -8, color: 0xA600_0000, inset: false),
                ]
            ),
            shadowToast: ThemeShadow(
                light: [
                    ThemeShadowLayer(x: 0, y: 0, blur: 0, spread: 0.5, color: 0x242B_2D30, inset: false),
                    ThemeShadowLayer(x: 0, y: 10, blur: 30, spread: -8, color: 0x401E_1E20, inset: false),
                ],
                dark: [
                    ThemeShadowLayer(x: 0, y: 0, blur: 0, spread: 0.5, color: 0x24DE_DEDE, inset: false),
                    ThemeShadowLayer(x: 0, y: 10, blur: 30, spread: -8, color: 0xA600_0000, inset: false),
                ]
            ),
            shadowThumb: ThemeShadow(
                light: [
                    ThemeShadowLayer(x: 0, y: 0, blur: 0, spread: 0.5, color: 0x1F2B_2D30, inset: false),
                    ThemeShadowLayer(x: 0, y: 1, blur: 2, spread: 0, color: 0x261E_1E20, inset: false),
                ],
                dark: [
                    ThemeShadowLayer(x: 0, y: 0, blur: 0, spread: 0.5, color: 0x1FDE_DEDE, inset: false),
                    ThemeShadowLayer(x: 0, y: 1, blur: 2, spread: 0, color: 0x8000_0000, inset: false),
                ]
            ),
            focusRing: ThemeShadow(
                light: [ThemeShadowLayer(x: 0, y: 0, blur: 0, spread: 3, color: 0x59C8_962F, inset: false)],
                dark: [ThemeShadowLayer(x: 0, y: 0, blur: 0, spread: 3, color: 0x66D9_A94F, inset: false)]
            )
        ),
        blurs: ThemeBlurs(
            panel: ThemeBlur(radius: 30, saturation: 1.8),
            pop: ThemeBlur(radius: 30, saturation: 1.8),
            dialog: ThemeBlur(radius: 30, saturation: 1.8),
            scrim: ThemeBlur(radius: 12, saturation: 1)
        ),
        shape: ThemeShape(
            radiusScale: 0.5,
            checkCorner: .circle,
            checkMark: .fill,
            tagRadius: 4,
            tagPadding: ThemeInsets(vertical: 0.05, horizontal: 0.35),
            doneStrikethrough: true
        ),
        motion: ThemeMotion(
            spring: .cubicBezier(0.25, 0.1, 0.25, 1),
            easeOut: .cubicBezier(0.25, 0.1, 0.25, 1)
        ),
        title: ThemeTitleFont(
            typeface: ThemeTypeface(
                name: "Nunito Sans",
                faces: [
                    ThemeFontFace(postScriptName: "NunitoSans-Regular", weights: 400...800, italic: false),
                    ThemeFontFace(postScriptName: "NunitoSans-Italic", weights: 400...800, italic: true),
                ],
                opticalSizing: true
            ),
            weight: 750,
            italic: false,
            quoteWeight: 400,
            quoteItalic: true,
            axes: [:],
            scale: 0.84,
            tracking: -0.012
        ),
        body: ThemeTypeface(
            name: "Nunito Sans",
            faces: [
                ThemeFontFace(postScriptName: "NunitoSans-Regular", weights: 400...800, italic: false),
                ThemeFontFace(postScriptName: "NunitoSans-Italic", weights: 400...800, italic: true),
            ],
            opticalSizing: true
        )
    )
}
