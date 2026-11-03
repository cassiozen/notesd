"""Generate every app icon from one master PNG.

    python3 -m venv /tmp/icons && /tmp/icons/bin/pip install pillow
    /tmp/icons/bin/python scripts/icons.py docs/images/icon.png [web] [android] [ios] [macos]

With no targets after the master, it writes them all.

The master is the rounded amber tile on a transparent canvas. Its soft halo is
cut away, the tile is cropped square, and then placed per target:
  web:     favicon.ico/png, icon-192/512, a maskable 512, apple-touch-icon,
           and the in-app mark (src/ui/mark.png)
  android: adaptive launcher foreground + monochrome, splash icon, brand mark
  ios:     the 1024px app icon, full bleed (iOS masks it to its own shape and
           allows no transparency), and the in-app mark
  macos:   the desktop app's icon.icns and icon.png, on Apple's icon grid
"""

import sys
from pathlib import Path

from PIL import Image, ImageChops

ROOT = Path(__file__).resolve().parent.parent
WEB = ROOT / "apps/web"
RES = ROOT / "apps/android/app/src/main/res"
IOS = ROOT / "apps/ios/Notes/Resources/Assets.xcassets"
MACOS = ROOT / "apps/desktop/src-tauri/icons"
PAPER = (246, 243, 238, 255)  # --paper / @color/paper
DENSITIES = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}


def load_tile(path):
    im = Image.open(path).convert("RGBA")
    # Keep the tile and its anti-aliased edge; drop the faint halo around it.
    a = im.getchannel("A").point(lambda v: max(0, min(255, (v - 150) * 255 // 90)))
    im.putalpha(a)
    im = im.crop(a.getbbox())
    side = max(im.size)
    sq = Image.new("RGBA", (side, side))
    sq.alpha_composite(im, ((side - im.width) // 2, (side - im.height) // 2))
    return sq


def place(tile, size, scale, bg=None):
    """The tile at `scale` of a `size` canvas, centered, on `bg` or transparent."""
    canvas = Image.new("RGBA", (size, size), bg or (0, 0, 0, 0))
    t = round(size * scale)
    canvas.alpha_composite(tile.resize((t, t), Image.LANCZOS), ((size - t) // 2, (size - t) // 2))
    return canvas


def sheet_mask(tile):
    """The white sheet alone, as alpha: the themed (monochrome) launcher glyph."""
    r, g, b, a = tile.split()
    # Paper is near-white in blue; the amber tile is low in blue.
    sheet = b.point(lambda v: max(0, min(255, (v - 130) * 255 // 90)))
    sheet = ImageChops.multiply(sheet, a)
    glyph = Image.new("RGBA", tile.size, (0, 0, 0, 255))
    glyph.putalpha(sheet)
    return glyph.crop(sheet.getbbox())


def save(img, path, **kw):
    path.parent.mkdir(parents=True, exist_ok=True)
    img.save(path, optimize=True, **kw)
    print(path.relative_to(ROOT))


def main(src, targets):
    tile = load_tile(src)
    if "web" in targets:
        web(tile)
    if "android" in targets:
        android(tile)
    if "ios" in targets:
        ios(tile)
    if "macos" in targets:
        macos(tile)


def web(tile):
    pub = WEB / "public"

    # Web. Favicons use the whole canvas: at 16px every pixel counts.
    save(place(tile, 256, 1), pub / "favicon.ico", sizes=[(16, 16), (32, 32), (48, 48)])
    save(place(tile, 96, 1), pub / "favicon.png")
    save(place(tile, 192, 0.9), pub / "icon-192.png")
    save(place(tile, 512, 0.9), pub / "icon-512.png")
    # Maskable: the tile's farthest corner stays inside the 80% safe circle.
    save(place(tile, 512, 0.64, PAPER), pub / "icon-maskable-512.png")
    # iOS masks and doesn't allow transparency: the tile on paper.
    save(place(tile, 180, 0.84, PAPER).convert("RGB"), pub / "apple-touch-icon.png")
    save(place(tile, 96, 1), WEB / "src/ui/mark.png")


def android(tile):
    # Android, on the adaptive icon's 108dp canvas. The launcher shows the
    # middle 72dp through its mask; the tile overscans that (80dp) so the mask,
    # not the tile's own corners, gives the shape.
    glyph = sheet_mask(tile)
    for name, d in DENSITIES.items():
        px = round(108 * d)
        save(place(tile, px, 80 / 108), RES / f"mipmap-{name}/ic_launcher_foreground.png")
        mono = Image.new("RGBA", (px, px))
        w = round(46 * d)
        g = glyph.resize((w, round(w * glyph.height / glyph.width)), Image.LANCZOS)
        mono.alpha_composite(g, ((px - g.width) // 2, (px - g.height) // 2))
        save(mono, RES / f"mipmap-{name}/ic_launcher_monochrome.png")
        # Splash: the whole tile, inside the circle the splash screen crops to.
        save(place(tile, px, 56 / 108), RES / f"drawable-{name}/splash_icon.png")
    save(place(tile, 104, 1), RES / "drawable-nodpi/brand_mark.png")


def ios(tile):
    # The tile overscans the canvas, as on Android, so the system's mask and
    # not the tile's own corners gives the shape. Underneath, the tile's amber
    # (sampled inside its top and bottom edges) fills what the overscan leaves.
    top = tile.getpixel((tile.width // 2, round(tile.height * 0.03)))
    bottom = tile.getpixel((tile.width * 4 // 5, round(tile.height * 0.97)))
    assert top[3] == 255 and bottom[3] == 255, "background sampled outside the tile; move the sample points inward"
    size = 1024
    bg = Image.linear_gradient("L").resize((size, size))
    bg = Image.composite(Image.new("RGBA", (size, size), bottom), Image.new("RGBA", (size, size), top), bg)
    save(Image.alpha_composite(bg, place(tile, size, 1.06)).convert("RGB"), IOS / "AppIcon.appiconset/icon-1024.png")
    save(place(tile, 312, 1), IOS / "BrandMark.imageset/brand-mark.png")


def macos(tile):
    # macOS doesn't mask: the icon keeps its own shape, inside the 824px of the 1024px grid that Apple's icons
    # use, so it sits at the same size as theirs in the Dock.
    icon = place(tile, 1024, 824 / 1024)
    save(icon, MACOS / "icon.png")
    save(icon, MACOS / "icon.icns")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2:] or ["web", "android", "ios", "macos"])
