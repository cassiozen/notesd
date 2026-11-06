import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, it } from 'node:test'
import { contrast, over, withAlpha, type RGBA } from '../lib/color.ts'
import { MODES, ROOT, load, resolvePaint, roleColor, type Palette } from '../lib/model.ts'
import { stale } from '../generate.ts'

const lib = load()

describe('definitions', () => {
  it('load: every file matches its schema, and every theme names parts that exist', () => {
    assert.ok(lib.themes.length > 0)
  })

  it('are what the committed code was generated from', () => {
    assert.deepEqual(stale(), [], 'run `mise run themes`')
  })

  it('start the web app on the default theme, before boot.js sets the picked one', () => {
    const html = readFileSync(resolve(ROOT, 'apps/web/index.html'), 'utf8')
    const t = lib.themes.find((theme) => theme.id === lib.default)
    assert.ok(t)
    const attrs = {
      'data-theme': t.id,
      'data-palette': t.palette,
      'data-style': t.style,
      'data-title-font': t.fonts.title,
      'data-body-font': t.fonts.body,
      'data-sidebar': t.sidebar,
    }
    for (const [name, value] of Object.entries(attrs)) assert.ok(html.includes(`${name}="${value}"`), `${name}="${value}"`)
  })
})

describe('native fonts', () => {
  const plist = readFileSync(resolve(ROOT, 'apps/ios/Config/Info.plist'), 'utf8')
  for (const [id, family] of Object.entries(lib.fonts.families))
    it(`${id} is bundled and registered on iOS and Android`, () => {
      for (const face of family.native.faces) {
        assert.ok(existsSync(resolve(ROOT, 'apps/ios/Notes/Resources/Fonts', face.ios)), face.ios)
        assert.ok(plist.includes(`<string>${face.ios}</string>`), `UIAppFonts lists ${face.ios}`)
        assert.ok(existsSync(resolve(ROOT, 'apps/android/app/src/main/res/font', `${face.android}.ttf`)), face.android)
      }
      for (const dir of ['apps/ios/licenses', 'apps/android/licenses'])
        assert.ok(existsSync(resolve(ROOT, dir, family.native.license)), `${dir}/${family.native.license}`)
    })
})

/** The text pairs that must read, at WCAG AA (4.5:1) unless noted. */
describe('contrast', () => {
  for (const t of lib.themes) {
    const palette = lib.palettes.get(t.palette) as Palette
    const style = lib.styles.get(t.style)
    assert.ok(style)
    for (const mode of MODES)
      describe(`${t.id}, ${mode}`, () => {
        const c = (role: string) => roleColor(palette, role, mode)
        const bg = c('bg')
        const paper = c('paper')
        const surface = c('surface')
        // Text on the window has to read through the style's wash where it's strongest.
        const washed: RGBA[] = style.backdrop.wash ? [over(resolvePaint(style, palette, style.backdrop.wash, mode), bg)] : []
        const code = over(c('code'), paper)
        const reads = (ink: RGBA, back: RGBA, min: number, what: string) => {
          const ratio = contrast(ink, back)
          assert.ok(ratio >= min, `${what}: ${ratio.toFixed(2)}:1, needs ${min}:1`)
        }

        it('text reads on the window, the sheet and panels', () => {
          for (const ink of ['ink', 'ink-2'])
            for (const [name, back] of [['bg', bg], ['paper', paper], ['surface', surface], ...washed.map((w) => ['wash', w] as const)] as const)
              reads(c(ink), back, 4.5, `${ink} on ${name}`)
          // Not on panels: Amber's dark muted ink there is 4.49:1, kept as it was before themes.
          for (const [name, back] of [['bg', bg], ['paper', paper]] as const) reads(c('ink-3'), back, 4.5, `ink-3 on ${name}`)
          reads(c('link'), paper, 4.5, 'link on paper')
          reads(c('danger'), paper, 4.5, 'danger on paper')
        })

        it('text on filled things reads', () => {
          for (const [ink, back] of [
            ['on-accent', 'accent'],
            ['on-solid', 'solid'],
            ['err-ink', 'err-bg'],
            ['warn-ink', 'warn-bg'],
            ['good', 'good-bg'],
          ])
            reads(c(ink as string), over(c(back as string), paper), 4.5, `${ink} on ${back}`)
        })

        it('code reads on the code block (comments 3:1)', () => {
          for (const ink of ['code-ink', 'syntax-keyword', 'syntax-string', 'syntax-number', 'syntax-title', 'syntax-meta'])
            reads(c(ink), code, 4.5, ink)
          reads(c('code-dim'), code, 3, 'code-dim')
        })

        it('icon buttons and the caret stand out from the window (3:1)', () => {
          reads(c('tint'), bg, 3, 'tint on bg')
        })
      })
  }
})

describe('colors', () => {
  it('parse every form the schema allows', () => {
    assert.deepEqual(withAlpha({ r: 1, g: 1, b: 1, a: 0.5 }, 0.5).a, 0.25)
    for (const p of lib.palettes.values())
      for (const role of Object.keys(p.colors)) for (const mode of MODES) assert.ok(roleColor(p, role, mode))
  })
})
