import Testing
import UIKit

@testable import Notes

// Themes.generated.swift comes from shared/themes; shared/themes/test checks the definitions.
struct ThemesTests {
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
            for (generated, current) in [
                (amber.bg, Palette.bg), (amber.ink, Palette.ink), (amber.ink2, Palette.ink2),
                (amber.accent, Palette.accent), (amber.link, Palette.link), (amber.hair, Palette.hair),
            ] {
                #expect(generated.uiColor.resolvedColor(with: traits) == current.resolvedColor(with: traits))
            }
        }
    }
}
