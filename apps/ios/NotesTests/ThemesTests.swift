import Testing
import UIKit

@testable import Notes

// Themes.generated.swift comes from shared/themes; shared/themes/test checks the definitions.
struct ThemesTests {
    private func rgba(_ color: UIColor) -> [CGFloat] {
        var (r, g, b, a): (CGFloat, CGFloat, CGFloat, CGFloat) = (0, 0, 0, 0)
        color.getRed(&r, green: &g, blue: &b, alpha: &a)
        return [r, g, b, a]
    }

    @Test func everyThemesFontsLoadByName() {
        for theme in Themes.all {
            for face in theme.title.typeface.faces + theme.body.faces {
                #expect(UIFont(name: face.postScriptName, size: 17) != nil, "\(theme.id): \(face.postScriptName)")
            }
        }
    }

    @Test func anIdNoThemeHasFallsBackToTheDefault() {
        #expect(Themes.theme(id: "gone") == Themes.fallback)
        #expect(Set(Themes.all.map(\.id)).count == Themes.all.count)
    }

    @MainActor @Test func amberDrawsWhatTheAppDrewBeforeThemes() {
        // The test host is the app, with whatever theme this simulator last picked.
        let picked = CurrentTheme.theme
        CurrentTheme.set(Themes.amber)
        defer { CurrentTheme.set(picked) }
        for style in [UIUserInterfaceStyle.light, .dark] {
            let traits = UITraitCollection(userInterfaceStyle: style)
            for (name, light, dark, current) in Self.beforeThemes {
                let before = ThemeColor(light: light, dark: dark).uiColor.resolvedColor(with: traits)
                let a = rgba(before)
                let b = rgba(current.resolvedColor(with: traits))
                // Within one step of 255: 0.7 alpha is 178.5, which the hand-made value rounded down.
                #expect(
                    zip(a, b).allSatisfy { abs($0 - $1) <= 1.01 / 255 },
                    "\(name), \(style == .dark ? "dark" : "light"): \(a) vs \(b)")
            }
        }
    }

    // The hand-kept Palette the app drew with before shared/themes, kept to hold Amber to it. The code
    // block is left out: its dark is now the web's (#060709 against #0b0c0e).
    private static let beforeThemes: [(String, UInt32, UInt32, UIColor)] = [
        ("bg", 0xFFF5_F3F1, 0xFF12_1416, Palette.bg),
        ("ink", 0xFF21_1F1C, 0xFFE9_EBEE, Palette.ink),
        ("ink2", 0xFF50_4C4A, 0xFFB2_B4B7, Palette.ink2),
        ("ink3", 0xFF66_6260, 0xFF93_9598, Palette.ink3),
        ("accent", 0xFFEF_B146, 0xFFEF_B146, Palette.accent),
        ("accentDeep", 0xFFDD_9314, 0xFFDD_9314, Palette.accentDeep),
        ("onAccent", 0xFF1F_1915, 0xFF1F_1915, Palette.onAccent),
        ("tint", 0xFFBE_7200, 0xFFF0_BA59, Palette.tint),
        ("link", 0xFF87_4E00, 0xFFEF_BA64, Palette.link),
        ("danger", 0xFFAC_312C, 0xFFFB_8276, Palette.danger),
        ("solid", 0xFF24_211F, 0xFFE6_E8EA, Palette.solid),
        ("onSolid", 0xFFF9_F8F6, 0xFF14_1618, Palette.onSolid),
        ("hair", 0x172D_2823, 0x14FF_FFFF, Palette.hair),
        ("hairStrong", 0x292D_2823, 0x29FF_FFFF, Palette.hairStrong),
        ("hover", 0x122D_2823, 0x12FF_FFFF, Palette.hover),
        ("on", 0x1C2D_2823, 0x21FF_FFFF, Palette.on),
        ("edge", 0xD9FF_FFFF, 0x14FF_FFFF, Palette.edge),
        ("dangerBg", 0x1AD2_4D45, 0x29D2_4D45, Palette.dangerBg),
        ("blob", 0x2EF4_D9A8, 0x3362_4821, Palette.blob),
        ("field", 0xC7FF_FFFF, 0x0FFF_FFFF, Palette.field),
        ("chip", 0x9EFF_FFFF, 0x12FF_FFFF, Palette.chip),
        ("good", 0xFF0E_492A, 0xFF9E_E0B4, Palette.good),
        ("goodBg", 0xBFC0_EACD, 0x8024_5337, Palette.goodBg),
        ("errBg", 0xD9FF_E8E3, 0xB24D_2620, Palette.errBg),
        ("errEdge", 0xFFFD_CAC0, 0xFF72_3B32, Palette.errEdge),
        ("errInk", 0xFF78_2B21, 0xFFFD_CAC0, Palette.errInk),
        ("warnBg", 0xE0FF_EAD3, 0xBF48_2F1A, Palette.warnBg),
        ("warnEdge", 0xFFF6_CBA5, 0xFF72_4B2B, Palette.warnEdge),
        ("warnInk", 0xFF62_3512, 0xFFF8_D8B4, Palette.warnInk),
        ("tagBg", 0x4DEF_B146, 0x4DEF_B146, Palette.tagBg),
        ("codeInk", 0xFFD8_D4CD, 0xFFD8_D4CD, Palette.codeInk),
        ("codeDim", 0xFF8F_8C85, 0xFF8F_8C85, Palette.codeDim),
        ("codeKeyword", 0xFFC3_A5F9, 0xFFC3_A5F9, Palette.codeKeyword),
        ("codeString", 0xFF8B_D28D, 0xFF8B_D28D, Palette.codeString),
        ("codeNumber", 0xFFFB_A962, 0xFFFB_A962, Palette.codeNumber),
    ]
}
