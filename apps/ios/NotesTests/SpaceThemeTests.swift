import SwiftUI
import Testing
import UIKit

@testable import Notes

@MainActor
struct SpaceThemeTests {
    @Test func everyIconBringsAThemeOfItsOwn() {
        for icon in SpaceIcon.all {
            #expect(SpaceThemes.key(icon.key) == icon.key)
            let own = SpaceThemes.colors(icon.key, dark: false)
            #expect(own.bg != SpaceThemes.colors("", dark: false).bg, "\(icon.key)")
        }
        #expect(SpaceThemes.key("notepad") == "")
    }

    @Test func paletteColoursFollowTheTraitInUIKit() {
        let shown = UITraitCollection { $0.spaceTheme = "dragon" }
        let none = UITraitCollection { $0.spaceTheme = "" }
        #expect(Palette.bg.resolvedColor(with: shown) != Palette.bg.resolvedColor(with: none))
        #expect(Palette.accent.resolvedColor(with: shown) != Palette.accent.resolvedColor(with: none))
    }

    @Test func paletteColoursFollowTheEnvironmentInSwiftUI() {
        var shown = EnvironmentValues()
        shown.spaceTheme = "dragon"
        let none = EnvironmentValues()
        #expect(Color.accent.resolve(in: shown) != Color.accent.resolve(in: none))
        #expect(Color.notesBg.resolve(in: shown) != Color.notesBg.resolve(in: none))
    }
}
