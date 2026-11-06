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

    @Test func amberIsThePaletteTheAppDrawsToday() {
        let amber = Themes.amber.colors
        for style in [UIUserInterfaceStyle.light, .dark] {
            let traits = UITraitCollection(userInterfaceStyle: style)
            // Every role both have; blob and code differ on purpose (a glow's full strength, a dark block's alpha).
            let pairs: [(String, ThemeColor, UIColor)] = [
                ("bg", amber.bg, Palette.bg),
                ("ink", amber.ink, Palette.ink),
                ("ink2", amber.ink2, Palette.ink2),
                ("ink3", amber.ink3, Palette.ink3),
                ("accent", amber.accent, Palette.accent),
                ("accentDeep", amber.accentDeep, Palette.accentDeep),
                ("onAccent", amber.onAccent, Palette.onAccent),
                ("tint", amber.tint, Palette.tint),
                ("link", amber.link, Palette.link),
                ("danger", amber.danger, Palette.danger),
                ("solid", amber.solid, Palette.solid),
                ("onSolid", amber.onSolid, Palette.onSolid),
                ("hair", amber.hair, Palette.hair),
                ("hairStrong", amber.hairStrong, Palette.hairStrong),
                ("hover", amber.hover, Palette.hover),
                ("on", amber.on, Palette.on),
                ("edge", amber.edge, Palette.edge),
                ("field", amber.field, Palette.field),
                ("chip", amber.chip, Palette.chip),
                ("dangerBg", amber.dangerBg, Palette.dangerBg),
                ("good", amber.good, Palette.good),
                ("goodBg", amber.goodBg, Palette.goodBg),
                ("errBg", amber.errBg, Palette.errBg),
                ("errEdge", amber.errEdge, Palette.errEdge),
                ("errInk", amber.errInk, Palette.errInk),
                ("warnBg", amber.warnBg, Palette.warnBg),
                ("warnEdge", amber.warnEdge, Palette.warnEdge),
                ("warnInk", amber.warnInk, Palette.warnInk),
                ("tagBg", amber.tagBackground!, Palette.tagBg),
            ]
            for (name, generated, current) in pairs {
                // Within one step of 255: 0.7 alpha is 178.5, which the hand-made value rounded down.
                let a = rgba(generated.uiColor.resolvedColor(with: traits))
                let b = rgba(current.resolvedColor(with: traits))
                #expect(zip(a, b).allSatisfy { abs($0 - $1) <= 1.01 / 255 }, "\(name): \(a) vs \(b)")
            }
        }
    }
}
