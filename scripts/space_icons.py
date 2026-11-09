"""Generate the space icons and their themes (architecture §7.7) for every app.

    python3 -m venv /tmp/icons && /tmp/icons/bin/pip install resvg-py pillow
    /tmp/icons/bin/python scripts/space_icons.py [DOWNLOADS]

The web app's SVGs (apps/web/src/notes/space-icons/) are the master copy. With
DOWNLOADS, the icons8 SVGs listed in ICONS are first tidied and copied there
under their keys; then, always:
  web:     space-icons.ts, the keys in order with their names and window
           colours, and styles/space-themes.css, every theme's tokens
  android: a 144px PNG per icon (48dp at 3x, the picker's largest),
           SpaceIcons.kt, mapping keys to drawables, and ui/SpaceThemes.kt,
           every theme's colours
  ios:     an imageset per icon, the SVG kept as a vector at 24pt, the app's
           mark at the same size (menus draw images as they come),
           SpaceIcons.swift, and UI/SpaceThemes.swift, every theme's colours
  server:  publish/assets/theme.css, a published space's colours: the app
           icon's neutrals with each theme's accent (§7.10), and
           accents.json, each accent and background for its favicon

A key is stored with the space and never changes. '' is the app's own icon,
the default space's to begin with; an app shows it for a key it doesn't have
too, so removing an icon is safe, renaming a key isn't.

Each icon comes with a theme, never picked apart from it: tinted neutrals
(background, paper, ink) carry the mood, and the accent shows in few places
(caret, checked tasks, the main button). A theme is a seed; every token is
derived from it here, once, so all apps get the same colours, and the ones
that must stay legible are adjusted until they pass a contrast check.
"""

from __future__ import annotations

import json
import math
import re
import shutil
import sys
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import NamedTuple

import resvg_py
from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
WEB = ROOT / "apps/web/src/notes"
SVGS = WEB / "space-icons"
WEB_CSS = ROOT / "apps/web/src/styles/space-themes.css"
RES = ROOT / "apps/android/app/src/main/res/drawable-nodpi"
KOTLIN = ROOT / "apps/android/app/src/main/kotlin/notes/app/notes/SpaceIcons.kt"
KOTLIN_THEMES = ROOT / "apps/android/app/src/main/kotlin/notes/app/ui/SpaceThemes.kt"
XCASSETS = ROOT / "apps/ios/Notes/Resources/Assets.xcassets/SpaceIcons"
BRAND_MARK = ROOT / "apps/ios/Notes/Resources/Assets.xcassets/BrandMark.imageset/brand-mark.png"
# The app's mark fills its square; the icons leave a margin. Shown among them,
# the mark is inset this much (web and Android do the same as they draw it).
APP_ICON_SCALE = 0.84
SWIFT = ROOT / "apps/ios/Notes/Notes/SpaceIcons.swift"
SWIFT_THEMES = ROOT / "apps/ios/Notes/UI/SpaceThemes.swift"
BLOG_CSS = ROOT / "server/internal/publish/assets/theme.css"
BLOG_ACCENTS = ROOT / "server/internal/publish/assets/accents.json"


class Theme(NamedTuple):
    """oklch: accent (L, C, H); each mode's neutrals (H, C)."""

    accent: tuple[float, float, float]
    light: tuple[float, float]
    dark: tuple[float, float]


# The app icon's: honey on warm paper, cool graphite at night. Its neutrals and
# accent are DEFAULT_TOKENS, kept as they were before themes; the seed gives
# only what those don't set.
DEFAULT_THEME = Theme((0.80, 0.14, 78), (80, 0.0035), (250, 0.005))

# Picker order: (key, name, icons8 file, theme). Names are what screen readers say.
# Hues are spread round the wheel; where two share one, the neutrals or the
# accent's lightness tell them apart.
ICONS = [
    # Saddle: cognac on tan paper, espresso at night.
    ("leather-journal", "Leather journal", "book-journal3", Theme((0.55, 0.10, 50), (60, 0.012), (45, 0.016))),
    # Coral on blush.
    ("diary", "Diary", "book-diary", Theme((0.70, 0.16, 30), (25, 0.010), (20, 0.014))),
    # Library: banker's-lamp green on aged paper, a green study at night.
    ("open-book", "Open book", "book-open", Theme((0.48, 0.10, 160), (85, 0.014), (165, 0.014))),
    # Arena: the scoreboard's electric blue.
    ("scoreboard", "Scoreboard", "fun-scoreboard", Theme((0.62, 0.19, 252), (250, 0.006), (255, 0.016))),
    # Violet ink on lavender grey.
    ("quill", "Quill and ink", "fun-quill-with-ink", Theme((0.48, 0.20, 293), (290, 0.008), (285, 0.020))),
    # Eureka: lemon on the indigo of the bulb's base.
    ("idea", "Light bulb", "fun-idea", Theme((0.90, 0.17, 102), (275, 0.010), (275, 0.022))),
    # Sea teal on parchment.
    ("treasure-map", "Treasure map", "fun-treasure-map", Theme((0.54, 0.10, 205), (85, 0.025), (75, 0.016))),
    # Gallery: the portrait's mint backdrop.
    ("portrait", "Portrait", "fun-dailyart", Theme((0.74, 0.13, 172), (180, 0.008), (190, 0.012))),
    # Dalí: the clock face's indigo on desert sand, rust at dusk.
    ("melting-clock", "Melting clock", "fun-dali", Theme((0.52, 0.16, 275), (65, 0.020), (45, 0.018))),
    # Velvet: gilt masks on a red house.
    ("theatre", "Theatre masks", "fun-theatre-mask", Theme((0.80, 0.15, 85), (15, 0.012), (15, 0.030))),
    # Sandstone walls, terracotta roof.
    ("castle", "Castle", "fun-castle", Theme((0.56, 0.15, 38), (70, 0.020), (55, 0.016))),
    # Vaporwave: hot pink on peach, purple dusk.
    ("sunset", "Sunset", "fun-vaporwave", Theme((0.64, 0.23, 0), (40, 0.014), (320, 0.028))),
    # Night sky: a flame across deep blue.
    ("comet", "Comet", "fun-comet", Theme((0.70, 0.19, 45), (265, 0.010), (270, 0.030))),
    # Orbit: visor sky blue, deep space at night.
    ("astronaut", "Astronaut", "fun-space", Theme((0.70, 0.13, 230), (235, 0.008), (245, 0.025))),
    # Protocol droid: brass plating on gunmetal.
    ("robot", "Droid", "fun-c-3po", Theme((0.74, 0.13, 82), (235, 0.010), (230, 0.018))),
    # Precious: gold, by Shire-green daylight and in the dark.
    ("ring", "One ring", "fun-one-ring", Theme((0.76, 0.16, 92), (100, 0.012), (60, 0.012))),
    # Wyrm: wine-dark scales.
    ("dragon", "Dragon", "fun-dragon", Theme((0.45, 0.17, 345), (350, 0.010), (350, 0.025))),
    # Silver mane: hot pink on cool silver.
    ("unicorn", "Unicorn", "fun-unicorn", Theme((0.66, 0.22, 5), (250, 0.010), (250, 0.016))),
    # Storybook: lilac on pink pages.
    ("reading-unicorn", "Reading unicorn", "fun-reading-unicorn", Theme((0.68, 0.13, 315), (330, 0.012), (300, 0.020))),
    # Monarch orange on cream, espresso at night.
    ("butterfly", "Butterfly", "fun-monarch-butterfly", Theme((0.66, 0.18, 55), (75, 0.012), (45, 0.018))),
    # Lagoon: salmon on tropical aqua.
    ("flamingo", "Flamingo", "fun-flamingo", Theme((0.72, 0.14, 18), (190, 0.010), (200, 0.020))),
    # Pond: mallard green among reeds.
    ("duck", "Duck", "fun-duck", Theme((0.52, 0.13, 155), (95, 0.012), (150, 0.014))),
    # Fiesta: papel-picado fuchsia on the skull's pastel green.
    ("sugar-skull", "Sugar skull", "fun-day-of-the-dead", Theme((0.56, 0.24, 335), (120, 0.020), (120, 0.014))),
    # Laboratory: chartreuse on lab slate.
    ("monster", "Monster", "fun-frankensteins-monster", Theme((0.72, 0.16, 118), (130, 0.010), (250, 0.018))),
]

# The app icon's tokens as they were before themes; DEFAULT_THEME derives the rest.
DEFAULT_TOKENS = {
    "light": {
        "bg": "oklch(0.965 0.0035 80)",
        "paper": "oklch(0.988 0.002 80)",
        "ink": "oklch(0.24 0.006 60)",
        "ink-2": "oklch(0.42 0.006 60)",
        "ink-3": "oklch(0.5 0.006 60)",
        "glass": "rgb(254 253 252 / 0.74)",
        "glass-hi": "rgb(254 253 252 / 0.88)",
        "hair": "rgb(45 40 35 / 0.09)",
        "hair-strong": "rgb(45 40 35 / 0.16)",
        "hover": "rgb(45 40 35 / 0.07)",
        "on": "rgb(45 40 35 / 0.11)",
        "shadow": "0 12px 32px -18px rgb(48 40 33 / 0.22)",
        "shadow-lg": "0 40px 90px -34px rgb(48 40 33 / 0.35)",
        "scrim": "rgb(236 234 230 / 0.42)",
        "solid": "oklch(0.25 0.006 60)",
        "on-solid": "oklch(0.98 0.0025 80)",
        "accent": "oklch(0.8 0.14 78)",
        "accent-deep": "oklch(0.72 0.15 72)",
        "on-accent": "oklch(0.22 0.012 60)",
        "tint": "oklch(0.62 0.15 70)",
        "link": "oklch(0.48 0.11 65)",
        "blob-1": "oklch(0.895 0.07 82)",
    },
    "dark": {
        "bg": "oklch(0.19 0.005 250)",
        "paper": "oklch(0.225 0.005 250)",
        "ink": "oklch(0.94 0.004 250)",
        "ink-2": "oklch(0.77 0.005 250)",
        "ink-3": "oklch(0.67 0.005 250)",
        "glass": "rgb(39 41 43 / 0.72)",
        "glass-hi": "rgb(45 47 49 / 0.88)",
        "shadow": "0 14px 34px -18px rgb(0 0 0 / 0.65)",
        "shadow-lg": "0 40px 90px -30px rgb(0 0 0 / 0.75)",
        "scrim": "rgb(11 13 14 / 0.5)",
        "solid": "oklch(0.93 0.004 250)",
        "on-solid": "oklch(0.2 0.005 250)",
        "accent": "oklch(0.8 0.14 78)",
        "accent-deep": "oklch(0.72 0.15 72)",
        "on-accent": "oklch(0.22 0.012 60)",
        "tint": "oklch(0.82 0.13 80)",
        "link": "oklch(0.82 0.12 78)",
        "blob-1": "oklch(0.42 0.065 75)",
    },
}

# What must stay legible against what (WCAG 2: 4.5:1 for text, 3:1 for a caret,
# a control's edge or a focus ring). "row" is a selected row (--on over --bg).
# legible() moves each token's lightness until it passes, the app icon's too.
LEGIBLE = [
    ("ink", ("bg", "paper", "row"), 4.5),
    ("ink-2", ("bg", "paper", "row"), 4.5),
    ("ink-3", ("bg", "paper", "row"), 4.5),
    ("link", ("bg", "paper"), 4.5),
    # The caret.
    ("tint", ("paper",), 3.0),
    # A checked task's ring and the main button's edge.
    ("accent-deep", ("paper",), 3.0),
    ("focus", ("bg", "paper"), 3.0),
]
# Checked, not moved: the label's colour is picked for its fill, the seed sets the fill.
FIXED = [("on-accent", "accent", 4.5), ("on-solid", "solid", 4.5)]


SVG_NS = "http://www.w3.org/2000/svg"
XLINK = "{http://www.w3.org/1999/xlink}href"
ET.register_namespace("", SVG_NS)
ET.register_namespace("xlink", "http://www.w3.org/1999/xlink")
GRADIENTS = {f"{{{SVG_NS}}}linearGradient", f"{{{SVG_NS}}}radialGradient"}
# What a gradient takes from the one it references, when it doesn't set it.
INHERITED = ("gradientUnits", "gradientTransform", "spreadMethod", "x1", "y1", "x2", "y2", "cx", "cy", "r", "fx", "fy")


def _srgb_linear(L: float, C: float, H: float) -> tuple[float, float, float]:
    a, b = C * math.cos(math.radians(H)), C * math.sin(math.radians(H))
    l_ = (L + 0.3963377774 * a + 0.2158037573 * b) ** 3
    m_ = (L - 0.1055613458 * a - 0.0638541728 * b) ** 3
    s_ = (L - 0.0894841775 * a - 1.2914855480 * b) ** 3
    return (
        4.0767416621 * l_ - 3.3077115913 * m_ + 0.2309699292 * s_,
        -1.2684380046 * l_ + 2.6097574011 * m_ - 0.3413193965 * s_,
        -0.0041960863 * l_ - 0.7034186147 * m_ + 1.7076147010 * s_,
    )


def _in_gamut(L: float, C: float, H: float) -> tuple[float, float, float]:
    """Linear sRGB, chroma reduced until it fits: roughly what browsers show."""
    while True:
        rgb = _srgb_linear(L, C, H)
        if all(-1e-4 <= v <= 1 + 1e-4 for v in rgb) or C <= 0:
            return tuple(min(max(v, 0.0), 1.0) for v in rgb)
        C -= 0.002


def luminance(lch: tuple[float, float, float]) -> float:
    r, g, b = _in_gamut(*lch)
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


def contrast(x: tuple[float, float, float], y: tuple[float, float, float]) -> float:
    hi, lo = sorted((luminance(x), luminance(y)), reverse=True)
    return (hi + 0.05) / (lo + 0.05)


def to_hex(lch: tuple[float, float, float]) -> str:
    enc = lambda v: v * 12.92 if v <= 0.0031308 else 1.055 * v ** (1 / 2.4) - 0.055  # noqa: E731
    return "#" + "".join(f"{round(enc(v) * 255):02X}" for v in _in_gamut(*lch))


def _num(x: float) -> str:
    return f"{x:.4f}".rstrip("0").rstrip(".") or "0"


def oklch(L: float, C: float, H: float, alpha: float | None = None) -> str:
    a = f" / {_num(alpha)}" if alpha is not None else ""
    return f"oklch({_num(L)} {_num(C)} {_num(H % 360)}{a})"


def _clamp(x: float, lo: float, hi: float) -> float:
    return min(max(x, lo), hi)


_OKLCH = re.compile(r"oklch\(([\d.]+) ([\d.]+) ([\d.]+)(?: / ([\d.]+))?\)")
_RGB = re.compile(r"rgb\(([\d.]+) ([\d.]+) ([\d.]+)(?: / ([\d.]+))?\)")


def _decode(v: float) -> float:
    return v / 12.92 if v <= 0.04045 else ((v + 0.055) / 1.055) ** 2.4


def _encode(v: float) -> float:
    return v * 12.92 if v <= 0.0031308 else 1.055 * v ** (1 / 2.4) - 0.055


def _rgba(token: str) -> tuple[float, float, float, float]:
    """A token's colour as linear sRGB and alpha."""
    if m := _OKLCH.fullmatch(token):
        L, C, H, a = m.groups()
        return (*_in_gamut(float(L), float(C), float(H)), float(a or 1))
    r, g, b, a = _RGB.fullmatch(token).groups()
    return (*(_decode(float(v) / 255) for v in (r, g, b)), float(a or 1))


def _over(top: tuple[float, ...], base: tuple[float, ...]) -> tuple[float, ...]:
    """top painted over an opaque base, blended as browsers do (in encoded sRGB)."""
    a = top[3]
    return (*(_decode(_encode(t) * a + _encode(b) * (1 - a)) for t, b in zip(top[:3], base[:3])), 1.0)


def _ratio(x: tuple[float, ...], y: tuple[float, ...]) -> float:
    lum = lambda c: 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2]  # noqa: E731
    hi, lo = sorted((lum(x), lum(y)), reverse=True)
    return (hi + 0.05) / (lo + 0.05)


def _shortfalls(t: dict[str, str], rules) -> list[tuple[str, str, float, float]]:
    """(token, against, ratio, floor) for each pair in rules under its floor."""
    grounds = {k: _rgba(t[k]) for k in ("bg", "paper")}
    grounds["row"] = _over(_rgba(t["on"]), grounds["bg"])
    out = []
    for fg, against, floor in rules:
        for name in (against,) if isinstance(against, str) else against:
            ground = grounds.get(name) or _rgba(t[name])
            ratio = _ratio(_over(_rgba(t[fg]), ground), ground)
            if ratio < floor:
                out.append((fg, name, ratio, floor))
    return out


def legible(t: dict[str, str], mode: str, rules=LEGIBLE) -> dict[str, str]:
    """t with each rule's token darkened (light) or lightened (dark) until it passes."""
    step = -0.01 if mode == "light" else 0.01
    for rule in rules:
        token = rule[0]
        while _shortfalls(t, [rule]):
            L, C, H, a = _OKLCH.fullmatch(t[token]).groups()
            L = round(float(L) + step, 3)
            if not 0 < L < 1:
                raise SystemExit(f"{token} can't reach {rule[2]}:1 in {mode}")
            t[token] = oklch(L, float(C), float(H), float(a) if a else None)
    return t


def derive(theme: Theme, mode: str) -> tuple[dict[str, str], tuple[float, float, float]]:
    """A theme's tokens in one mode, and its background (for window colours).
    The neutrals stay a soft tint, capped, so every theme could be the app's own
    style: the seed's chroma sets how far each leans, not how loud it gets."""
    aL, aC, aH = theme.accent
    if mode == "light":
        h, c = theme.light
        ink_c = min(c * 1.2, 0.02)
        bg, paper = (0.958, min(c * 0.8, 0.016), h), (0.986, c * 0.35, h)
        accent = (aL, aC, aH)
        shade = f"{_num(0.3)} {_num(c * 2)} {_num(h)}"
        t = {
            "bg": oklch(*bg),
            "paper": oklch(*paper),
            "ink": oklch(0.24, ink_c, h),
            "ink-2": oklch(0.42, ink_c, h),
            "ink-3": oklch(0.5, ink_c, h),
            "glass": oklch(0.993, c * 0.25, h, 0.74),
            "glass-hi": oklch(0.993, c * 0.25, h, 0.88),
            "hair": oklch(0.3, c * 1.5, h, 0.09),
            "hair-strong": oklch(0.3, c * 1.5, h, 0.16),
            "hover": oklch(0.3, c * 1.5, h, 0.07),
            "on": oklch(0.3, c * 1.5, h, 0.11),
            "shadow": f"0 12px 32px -18px oklch({shade} / 0.22)",
            "shadow-lg": f"0 40px 90px -34px oklch({shade} / 0.35)",
            "scrim": oklch(0.93, c * 0.8, h, 0.42),
            "solid": oklch(0.25, ink_c, h),
            "on-solid": oklch(0.98, c * 0.35, h),
            "blob-1": oklch(0.895, _clamp(c * 3, 0.03, 0.06), h),
            "tag": oklch(0.6, _clamp(c * 4, 0.03, 0.08), h, 0.2),
            "focus": oklch(0.55, _clamp(c * 4, 0.02, 0.1), h, 0.75),
        }
        # Starting points: legible() takes them further if they need it.
        caret = (min(aL, 0.62), aC, aH)
        link = (min(aL, 0.5), min(aC, 0.14), aH)
    else:
        h, seed = theme.dark
        c = min(seed * 0.55, 0.014)
        bg, paper = (0.19, c, h), (0.225, c, h)
        accent = (max(aL, 0.66), aC, aH)
        t = {
            "bg": oklch(*bg),
            "paper": oklch(*paper),
            "ink": oklch(0.94, c * 0.8, h),
            "ink-2": oklch(0.77, c, h),
            "ink-3": oklch(0.67, c, h),
            "glass": oklch(0.27, c, h, 0.72),
            "glass-hi": oklch(0.29, c, h, 0.88),
            "hair": "rgb(255 255 255 / 0.08)",
            "hair-strong": "rgb(255 255 255 / 0.16)",
            "hover": "rgb(255 255 255 / 0.07)",
            "on": "rgb(255 255 255 / 0.13)",
            "shadow": "0 14px 34px -18px rgb(0 0 0 / 0.65)",
            "shadow-lg": "0 40px 90px -30px rgb(0 0 0 / 0.75)",
            "scrim": oklch(0.13, c, h, 0.5),
            "solid": oklch(0.93, c * 0.8, h),
            "on-solid": oklch(0.2, c, h),
            "blob-1": oklch(0.42, _clamp(seed * 2, 0.03, 0.06), h),
            "tag": oklch(0.6, _clamp(seed * 3, 0.03, 0.08), h, 0.28),
            "focus": oklch(0.8, _clamp(seed * 3, 0.02, 0.1), h, 0.75),
        }
        caret = (max(aL, 0.78), aC * 0.9, aH)
        link = (max(aL, 0.8), min(aC, 0.14), aH)
    aL2 = accent[0]
    dark_label, light_label = (0.22, 0.012, aH), (0.99, 0.005, aH)
    label = max((dark_label, light_label), key=lambda x: contrast(x, accent))
    t |= {
        "accent": oklch(*accent),
        "accent-deep": oklch(aL2 - 0.08, aC + 0.01, aH - 4),
        "on-accent": oklch(*label),
        "tint": oklch(*caret),
        "link": oklch(*link),
    }
    return t, bg


def theme_tokens(theme: Theme, mode: str, default: bool) -> tuple[dict[str, str], str]:
    """The tokens to write and the window colour: the app icon's kept as they
    were, every theme's moved where they must to stay legible."""
    t, bg = derive(theme, mode)
    if default:
        t |= DEFAULT_TOKENS[mode]
        bg = (0.965, 0.0035, 80) if mode == "light" else (0.19, 0.005, 250)
    return legible(t, mode), to_hex(bg)


# A published space reads as the app icon's paper and ink, whatever its icon: only its accent comes along,
# moved until its links read and the accent shows against them.
BLOG = [("link", ("bg", "paper"), 4.5), ("accent", ("bg", "paper"), 3.0)]


def blog_tokens(key: str, theme: Theme, mode: str) -> dict[str, str]:
    """A published space's colours in one mode, as the site's variables."""
    base, _ = theme_tokens(DEFAULT_THEME, mode, True)
    own, _ = theme_tokens(theme, mode, key == "")
    t = legible(dict(base) | {"link": own["link"], "accent": own["accent-deep"]}, mode, BLOG)
    return {
        "bg": t["paper"],
        "surface": t["bg"],
        "text": t["ink"],
        "muted": t["ink-2"],
        "border": t["hair-strong"],
        "accent": t["accent"],
        "link": t["link"],
    }


def check_contrast():
    """Stops on any pair still under its floor: FIXED ones are the seeds' to fix."""
    low = []
    for key, name, _, theme in [("", "App icon", "", DEFAULT_THEME), *ICONS]:
        for mode in ("light", "dark"):
            t, _ = theme_tokens(theme, mode, key == "")
            for fg, against, ratio, floor in _shortfalls(t, LEGIBLE + FIXED):
                low.append(f"  {name} ({mode}): {fg} on {against} {ratio:.2f}, under {floor}")
    if low:
        raise SystemExit("Too little contrast:\n" + "\n".join(low))


def web_themes():
    """styles/space-themes.css: [data-space=key] on <html> (the space shown) or
    any element (a preview of one), in each mode. The app icon's are :root's
    too, so an unknown key, or none, is the app icon's theme."""

    def block(selectors: list[str], tokens: dict[str, str], indent: str) -> str:
        sel = (",\n" + indent).join(selectors)
        body = "\n".join(f"{indent}  --{k}: {v};" for k, v in tokens.items())
        return f"{indent}{sel} {{\n{body}\n{indent}}}\n"

    themes = [("", DEFAULT_THEME), *((k, t) for k, _, _, t in ICONS)]
    light, os_dark, dark = [], [], []
    for key, theme in themes:
        default = key == ""
        attr = f"[data-space='{key}']"
        light.append(block([":root", attr] if default else [attr], theme_tokens(theme, "light", default)[0], ""))
        os_root = ":root:not([data-theme='light'])"
        os_dark.append(
            block([os_root, f"{os_root} {attr}"] if default else [f"{os_root}{attr}", f"{os_root} {attr}"],
                  theme_tokens(theme, "dark", default)[0], "  "))
        root = ":root[data-theme='dark']"
        dark.append(
            block([root, f"{root} {attr}"] if default else [f"{root}{attr}", f"{root} {attr}"],
                  theme_tokens(theme, "dark", default)[0], ""))
    WEB_CSS.write_text(
        "/* Generated by scripts/space_icons.py; don't edit. Every space theme's tokens (§7.7), for\n"
        "   [data-space] on <html> (the space shown) or on any element (a preview of one); the app\n"
        "   icon's are :root's too. The rest of the tokens, the same in every theme, are in tokens.css. */\n\n"
        + "\n".join(light)
        + "\n/* Dark: the OS preference unless light was picked, then an explicit pick (as in tokens.css). */\n"
        + "@media (prefers-color-scheme: dark) {\n" + "\n".join(os_dark) + "}\n\n"
        + "\n".join(dark)
    )


def blog_themes():
    """server/internal/publish/assets/theme.css: every site's neutrals on :root, each theme's accent on
    [data-accent=key] (the space's icon) on <html>, in each mode."""

    def block(selector: str, tokens: dict[str, str], indent: str) -> str:
        body = "\n".join(f"{indent}  --{k}: {v};" for k, v in tokens.items())
        return f"{indent}{selector} {{\n{body}\n{indent}}}\n"

    accents = ("accent", "link")
    themes = [("", DEFAULT_THEME), *((k, t) for k, _, _, t in ICONS)]
    light, os_dark, dark = [], [], []
    # For what CSS can't colour: the site's favicon, its accent with the page's background on it.
    marks = {}
    for key, theme in themes:
        lt, dt = blog_tokens(key, theme, "light"), blog_tokens(key, theme, "dark")
        marks[key] = {mode: {"accent": t["accent"], "bg": t["bg"]} for mode, t in (("light", lt), ("dark", dt))}
        if key == "":
            light.append(block(":root", lt, ""))
            os_dark.append(block(":root:not([data-theme='light'])", dt, "  "))
            dark.append(block(":root[data-theme='dark']", dt, ""))
            continue
        attr = f"[data-accent='{key}']"
        light.append(block(attr, {k: lt[k] for k in accents}, ""))
        os_dark.append(block(f":root:not([data-theme='light']){attr}", {k: dt[k] for k in accents}, "  "))
        dark.append(block(f":root[data-theme='dark']{attr}", {k: dt[k] for k in accents}, ""))
    BLOG_CSS.parent.mkdir(parents=True, exist_ok=True)
    BLOG_CSS.write_text(
        "/* Generated by scripts/space_icons.py; don't edit. A published space's colours (§7.10): the app\n"
        "   icon's neutrals in every theme, and the space's accent, from data-accent on <html> (its icon). */\n\n"
        + "\n".join(light)
        + "\n/* Dark: the system's preference unless light was picked, then an explicit pick. */\n"
        + "@media (prefers-color-scheme: dark) {\n" + "\n".join(os_dark) + "}\n\n"
        + "\n".join(dark)
    )
    BLOG_ACCENTS.write_text(json.dumps(marks, indent=2, sort_keys=True) + "\n")


def tidy(svg: str) -> str:
    """The icon without its pixel size or editor names, and with every gradient
    holding its own stops: Xcode's SVG support doesn't follow xlink:href."""
    root = ET.fromstring(svg)
    root.attrib.pop("width", None)
    root.attrib.pop("height", None)
    by_id = {el.get("id"): el for el in root.iter() if el.tag in GRADIENTS}
    for el in root.iter():
        el.attrib.pop("data-name", None)
        # Only a gradient's reference is resolved; any other (<use>, <image>) stays.
        if el.tag not in GRADIENTS or XLINK not in el.attrib:
            continue
        src = by_id[el.attrib.pop(XLINK).lstrip("#")]
        for name in INHERITED:
            if name not in el.attrib and name in src.attrib:
                el.set(name, src.get(name))
        if len(el) == 0:
            el.extend(src)
    return ET.tostring(root, encoding="unicode").replace("ns0:", "") + "\n"


def import_downloads(src: Path):
    # Every icon read and tidied before the old ones go: a bad download leaves them as they were.
    tidied = {key: tidy((src / f"icons8-{file}.svg").read_text()) for key, _, file, _ in ICONS}
    if SVGS.exists():
        shutil.rmtree(SVGS)
    SVGS.mkdir(parents=True)
    for key, svg in tidied.items():
        (SVGS / f"{key}.svg").write_text(svg)


def web():
    rows = "\n".join(f"  {{ key: '{k}', name: '{n}' }}," for k, n, *_ in ICONS)
    bgs = "\n".join(
        f"  '{k}': {{ light: '{theme_tokens(t, 'light', k == '')[1]}', dark: '{theme_tokens(t, 'dark', k == '')[1]}' }},"
        for k, t in [("", DEFAULT_THEME), *((k, t) for k, _, _, t in ICONS)]
    )
    (WEB / "space-icons.ts").write_text(f"""// Generated by scripts/space_icons.py; don't edit.

/**
 * A space's icon (§7.7): its key, stored with the space, and what it shows.
 * The key '' (and any this app doesn't have) is the app's own icon.
 */
export type SpaceIconInfo = {{ key: string; name: string }}

/** In the picker's order, after the app's icon. */
export const SPACE_ICONS: SpaceIconInfo[] = [
{rows}
]

type WindowColor = {{ light: string; dark: string }}

/** Each icon's theme's --bg as hex, for the browser's window colour; '' is the app icon's. */
export const SPACE_THEME_BG: Record<string, WindowColor | undefined> & {{ '': WindowColor }} = {{
{bgs}
}}
""")
    web_themes()


# Theme tokens as the native apps name them; web-only ones (paper, shadows) are left out.
NATIVE_TOKENS = [
    ("bg", "bg"),
    ("ink", "ink"),
    ("ink-2", "ink2"),
    ("ink-3", "ink3"),
    ("accent", "accent"),
    ("accent-deep", "accentDeep"),
    ("on-accent", "onAccent"),
    ("tint", "tint"),
    ("link", "link"),
    ("solid", "solid"),
    ("on-solid", "onSolid"),
    ("hair", "hair"),
    ("hair-strong", "hairStrong"),
    ("hover", "hover"),
    ("on", "on"),
    ("glass", "glass"),
    ("glass-hi", "glassHi"),
    ("scrim", "scrim"),
    ("focus", "focus"),
    ("blob-1", "blob1"),
    ("tag", "tag"),
]


def argb(token: str) -> str:
    """A token's colour as AARRGGBB hex, as the browser draws it."""
    *rgb, a = _rgba(token)
    return f"{round(a * 255):02X}" + "".join(f"{round(_encode(v) * 255):02X}" for v in rgb)


def android_themes():
    def colors(key: str, theme: Theme, mode: str) -> str:
        t, _ = theme_tokens(theme, mode, key == "")
        last = len(NATIVE_TOKENS) - 1
        rows = "\n".join(
            f"            {name} = Color(0x{argb(t[css])}){'' if i == last else ','} // {t[css]}"
            for i, (css, name) in enumerate(NATIVE_TOKENS)
        )
        return f'        "{key}" to SpaceColors(\n{rows}\n        )'

    themes = [("", DEFAULT_THEME), *((k, t) for k, _, _, t in ICONS)]
    fields = ",\n".join(f"    val {name}: Color" for _, name in NATIVE_TOKENS)
    light = ",\n".join(colors(k, t, "light") for k, t in themes)
    dark = ",\n".join(colors(k, t, "dark") for k, t in themes)
    KOTLIN_THEMES.write_text(f"""// Generated by scripts/space_icons.py; don't edit.
package notes.app.ui

import androidx.compose.ui.graphics.Color

/**
 * The colours a space's theme sets (§7.7), the same as the web app's
 * (styles/space-themes.css). [Tokens.forSpace] adds the ones every theme shares.
 */
data class SpaceColors(
{fields}
)

object SpaceThemes {{
    /** The theme of a space's icon, or the app icon's ("") for a key this app doesn't have. */
    fun key(icon: String): String = if (icon in LIGHT) icon else ""

    fun colors(key: String, dark: Boolean): SpaceColors = (if (dark) DARK else LIGHT).getValue(key(key))

    private val LIGHT = mapOf(
{light}
    )

    private val DARK = mapOf(
{dark}
    )
}}
""")


# What iOS draws with: no glass, scrim or focus ring of its own.
SWIFT_TOKENS = [(css, name) for css, name in NATIVE_TOKENS if name not in ("glass", "glassHi", "scrim", "focus")]


def ios_themes():
    def colors(key: str, theme: Theme, mode: str) -> str:
        t, _ = theme_tokens(theme, mode, key == "")
        last = len(SWIFT_TOKENS) - 1
        rows = "\n".join(
            f"            {name}: 0x{argb(t[css])[:4]}_{argb(t[css])[4:]}{'' if i == last else ','}  // {t[css]}"
            for i, (css, name) in enumerate(SWIFT_TOKENS)
        )
        return f'        "{key}": SpaceColors(\n{rows}\n        ),'

    themes = [("", DEFAULT_THEME), *((k, t) for k, _, _, t in ICONS)]
    fields = "\n".join(f"    let {name}: UInt32" for _, name in SWIFT_TOKENS)
    light = "\n".join(colors(k, t, "light") for k, t in themes)
    dark = "\n".join(colors(k, t, "dark") for k, t in themes)
    SWIFT_THEMES.write_text(f"""// Generated by scripts/space_icons.py; don't edit.

/// The colours a space's theme sets (§7.7), as ARGB: the same as the web app's
/// (styles/space-themes.css). Palette draws with them; the rest are shared.
nonisolated struct SpaceColors: Sendable {{
{fields}
}}

nonisolated enum SpaceThemes {{
    /// The theme of a space's icon, or the app icon's ("") for a key this app doesn't have.
    static func key(_ icon: String) -> String {{ light[icon] == nil ? "" : icon }}

    static func colors(_ key: String, dark: Bool) -> SpaceColors {{
        (dark ? Self.dark : Self.light)[Self.key(key)] ?? Self.light[""]!
    }}

    private static let light: [String: SpaceColors] = [
{light}
    ]

    private static let dark: [String: SpaceColors] = [
{dark}
    ]
}}
""")


def resource(key: str) -> str:
    return "space_icon_" + key.replace("-", "_")


def android():
    for old in RES.glob("space_icon_*.png"):
        old.unlink()
    RES.mkdir(parents=True, exist_ok=True)
    for key, *_ in ICONS:
        png = resvg_py.svg_to_bytes(svg_path=str(SVGS / f"{key}.svg"), width=144, height=144)
        (RES / f"{resource(key)}.png").write_bytes(bytes(png))
    rows = ",\n".join(f'    SpaceIcon("{k}", "{n}", R.drawable.{resource(k)})' for k, n, *_ in ICONS)
    KOTLIN.write_text(f"""// Generated by scripts/space_icons.py; don't edit.
package notes.app.notes

import androidx.annotation.DrawableRes
import notes.app.R

/**
 * A space's icon (§7.7): its key, stored with the space, what it's called, and
 * its picture. The key "" (and any this app doesn't have) is the app's own icon.
 */
data class SpaceIcon(val key: String, val name: String, @DrawableRes val drawable: Int)

/** In the picker's order, after the app's icon. */
val SPACE_ICONS = listOf(
{rows}
)
""")
    android_themes()


def ios():
    if XCASSETS.exists():
        shutil.rmtree(XCASSETS)
    XCASSETS.mkdir(parents=True)
    info = {"info": {"author": "xcode", "version": 1}}
    (XCASSETS / "Contents.json").write_text(json.dumps(info, indent=2) + "\n")
    for key, *_ in ICONS:
        dest = XCASSETS / f"space-{key}.imageset"
        dest.mkdir()
        # 24pt, the size menus show it at; the vector scales for the picker.
        svg = (SVGS / f"{key}.svg").read_text().replace("<svg ", '<svg width="24" height="24" ', 1)
        (dest / f"{key}.svg").write_text(svg)
        contents = {
            "images": [{"filename": f"{key}.svg", "idiom": "universal"}],
            **info,
            "properties": {"preserves-vector-representation": True, "template-rendering-intent": "original"},
        }
        (dest / "Contents.json").write_text(json.dumps(contents, indent=2) + "\n")
    # The app's icon, as one of them: 24pt at 3x, inset like the others.
    dest = XCASSETS / "space-app.imageset"
    dest.mkdir()
    canvas = Image.new("RGBA", (72, 72))
    side = round(72 * APP_ICON_SCALE)
    mark = Image.open(BRAND_MARK).convert("RGBA").resize((side, side), Image.LANCZOS)
    canvas.alpha_composite(mark, ((72 - side) // 2, (72 - side) // 2))
    canvas.save(dest / "app.png")
    contents = {
        "images": [{"filename": "app.png", "idiom": "universal", "scale": "3x"}],
        **info,
        "properties": {"template-rendering-intent": "original"},
    }
    (dest / "Contents.json").write_text(json.dumps(contents, indent=2) + "\n")
    rows = "\n".join(f'        SpaceIcon(key: "{k}", name: "{n}"),' for k, n, *_ in ICONS)
    SWIFT.write_text(f"""// Generated by scripts/space_icons.py; don't edit.

/// A space's icon (§7.7): its key, stored with the space, and what it's called.
/// The picture is the asset `space-<key>`. The key "" (and any this app
/// doesn't have) is the app's own icon, `space-app`.
struct SpaceIcon: Hashable, Identifiable {{
    let key: String
    let name: String
    var id: String {{ key }}
    var asset: String {{ "space-\\(key)" }}

    /// In the picker's order, after the app's icon.
    static let all = [
{rows}
    ]
}}
""")
    ios_themes()


def main():
    assert len({k for k, *_ in ICONS}) == len(ICONS)
    assert all(re.fullmatch(r"[a-z0-9-]{1,64}", k) for k, *_ in ICONS)
    if len(sys.argv) > 1:
        import_downloads(Path(sys.argv[1]))
    check_contrast()
    web()
    android()
    ios()
    blog_themes()


if __name__ == "__main__":
    main()
