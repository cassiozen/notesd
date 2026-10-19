"""Generate the space icons (architecture §7.7) for every app.

    python3 -m venv /tmp/icons && /tmp/icons/bin/pip install resvg-py
    /tmp/icons/bin/python scripts/space_icons.py [DOWNLOADS]

The web app's SVGs (apps/web/src/notes/space-icons/) are the master copy. With
DOWNLOADS, the icons8 SVGs listed in ICONS are first tidied and copied there
under their keys; then, always:
  web:     space-icons.ts, the keys in order with their names
  android: a 144px PNG per icon (48dp at 3x, the picker's largest) and
           SpaceIcons.kt, mapping keys to drawables
  ios:     an imageset per icon, the SVG kept as a vector at 24pt, and
           SpaceIcons.swift

A key is stored with the space and never changes; an app shows the default
icon for a key it doesn't have, so removing one is safe, renaming isn't.
"""

import json
import re
import shutil
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

import resvg_py

ROOT = Path(__file__).resolve().parent.parent
WEB = ROOT / "apps/web/src/notes"
SVGS = WEB / "space-icons"
RES = ROOT / "apps/android/app/src/main/res/drawable-nodpi"
KOTLIN = ROOT / "apps/android/app/src/main/kotlin/notes/app/notes/SpaceIcons.kt"
XCASSETS = ROOT / "apps/ios/Notes/Resources/Assets.xcassets/SpaceIcons"
SWIFT = ROOT / "apps/ios/Notes/Notes/SpaceIcons.swift"

# The default space's icon, and the one shown for a space without one.
DEFAULT = "notepad"

# Picker order: (key, name, icons8 file). Names are what screen readers say.
ICONS = [
    ("notepad", "Notepad", "book-notepad"),
    ("journal", "Journal", "book-journal"),
    ("leather-journal", "Leather journal", "book-journal3"),
    ("diary", "Diary", "book-diary"),
    ("spiral-notepad", "Spiral notepad", "book-spiral-notepad"),
    ("ledger", "Ledger", "book-ledger"),
    ("open-book", "Open book", "book-open"),
    ("sketchbook", "Sketchbook", "book-sketchbook"),
    ("comic-book", "Comic book", "book-comic-book"),
    ("magazine", "Magazine", "book-comics-magazine"),
    ("health", "Health", "book-health-book"),
    ("cookbook", "Cookbook", "cooking-book"),
    ("upload", "Book with arrow", "book-return-book"),
    ("paper", "Paper", "bookish-paper"),
    ("ruled-page", "Ruled page", "bookish-rules"),
    ("clipboard", "Clipboard", "bookish-notes"),
    ("task", "Task", "bookish-task"),
    ("checklist", "Checklist", "bookish-pass-fail"),
    ("list", "List", "bookish-paper-activity-history"),
    ("edited-page", "Edited page", "bookish-paper-amendment"),
    ("noticeboard", "Noticeboard", "fun-noticeboard"),
    ("layout", "Layout", "fun-tiles"),
    ("scoreboard", "Scoreboard", "fun-scoreboard"),
    ("translate", "Translate", "fun-translate-text"),
    ("pencil", "Pencil", "fun-pencil"),
    ("quill", "Quill and ink", "fun-quill-with-ink"),
    ("pushpin", "Pushpin", "fun-pushpin"),
    ("bookmark", "Bookmark", "fun-bookmark-emoji"),
    ("idea", "Light bulb", "fun-idea"),
    ("heart-lock", "Heart lock", "fun-heart-lock"),
    ("treasure-map", "Treasure map", "fun-treasure-map"),
    ("message-bottle", "Message in a bottle", "fun-bottle-floating-in-water"),
    ("portrait", "Portrait", "fun-dailyart"),
    ("melting-clock", "Melting clock", "fun-dali"),
    ("theatre", "Theatre masks", "fun-theatre-mask"),
    ("castle", "Castle", "fun-castle"),
    ("eiffel-tower", "Eiffel Tower", "fun-eiffel-tower"),
    ("sunset", "Sunset", "fun-vaporwave"),
    ("comet", "Comet", "fun-comet"),
    ("astronaut", "Astronaut", "fun-space"),
    ("robot", "Robot", "fun-c-3po"),
    ("ring", "Ring", "fun-one-ring"),
    ("dragon", "Dragon", "fun-dragon"),
    ("unicorn", "Unicorn", "fun-unicorn"),
    ("reading-unicorn", "Reading unicorn", "fun-reading-unicorn"),
    ("butterfly", "Butterfly", "fun-monarch-butterfly"),
    ("flamingo", "Flamingo", "fun-flamingo"),
    ("duck", "Duck", "fun-duck"),
    ("sugar-skull", "Sugar skull", "fun-day-of-the-dead"),
    ("monster", "Monster", "fun-frankensteins-monster"),
]

SVG_NS = "http://www.w3.org/2000/svg"
XLINK = "{http://www.w3.org/1999/xlink}href"
ET.register_namespace("", SVG_NS)
ET.register_namespace("xlink", "http://www.w3.org/1999/xlink")
GRADIENTS = {f"{{{SVG_NS}}}linearGradient", f"{{{SVG_NS}}}radialGradient"}
# What a gradient takes from the one it references, when it doesn't set it.
INHERITED = ("gradientUnits", "gradientTransform", "spreadMethod", "x1", "y1", "x2", "y2", "cx", "cy", "r", "fx", "fy")


def tidy(svg: str) -> str:
    """The icon without its pixel size or editor names, and with every gradient
    holding its own stops: Xcode's SVG support doesn't follow xlink:href."""
    root = ET.fromstring(svg)
    root.attrib.pop("width", None)
    root.attrib.pop("height", None)
    by_id = {el.get("id"): el for el in root.iter() if el.tag in GRADIENTS}
    for el in root.iter():
        el.attrib.pop("data-name", None)
        ref = el.attrib.pop(XLINK, None)
        if el.tag not in GRADIENTS or not ref:
            continue
        src = by_id[ref.lstrip("#")]
        for name in INHERITED:
            if name not in el.attrib and name in src.attrib:
                el.set(name, src.get(name))
        if len(el) == 0:
            el.extend(src)
    return ET.tostring(root, encoding="unicode").replace("ns0:", "") + "\n"


def import_downloads(src: Path):
    if SVGS.exists():
        shutil.rmtree(SVGS)
    SVGS.mkdir(parents=True)
    for key, _, file in ICONS:
        svg = (src / f"icons8-{file}.svg").read_text()
        (SVGS / f"{key}.svg").write_text(tidy(svg))


def web():
    rows = "\n".join(f"  {{ key: '{k}', name: '{n}' }}," for k, n, _ in ICONS)
    (WEB / "space-icons.ts").write_text(f"""// Generated by scripts/space_icons.py; don't edit.

/** A space's icon (§7.7): its key, stored with the space, and what it shows. */
export type SpaceIconInfo = {{ key: string; name: string }}

/** Shown for the default space and any icon this app doesn't have. */
export const DEFAULT_SPACE_ICON = '{DEFAULT}'

/** In the picker's order. */
export const SPACE_ICONS: SpaceIconInfo[] = [
{rows}
]
""")


def resource(key: str) -> str:
    return "space_icon_" + key.replace("-", "_")


def android():
    for old in RES.glob("space_icon_*.png"):
        old.unlink()
    RES.mkdir(parents=True, exist_ok=True)
    for key, _, _ in ICONS:
        png = resvg_py.svg_to_bytes(svg_path=str(SVGS / f"{key}.svg"), width=144, height=144)
        (RES / f"{resource(key)}.png").write_bytes(bytes(png))
    rows = ",\n".join(f'    SpaceIcon("{k}", "{n}", R.drawable.{resource(k)})' for k, n, _ in ICONS)
    KOTLIN.write_text(f"""// Generated by scripts/space_icons.py; don't edit.
package notes.app.notes

import androidx.annotation.DrawableRes
import notes.app.R

/** A space's icon (§7.7): its key, stored with the space, what it's called, and its picture. */
data class SpaceIcon(val key: String, val name: String, @DrawableRes val drawable: Int)

/** Shown for the default space and any icon this app doesn't have. */
const val DEFAULT_SPACE_ICON = "{DEFAULT}"

/** In the picker's order. */
val SPACE_ICONS = listOf(
{rows}
)
""")


def ios():
    if XCASSETS.exists():
        shutil.rmtree(XCASSETS)
    XCASSETS.mkdir(parents=True)
    info = {"info": {"author": "xcode", "version": 1}}
    (XCASSETS / "Contents.json").write_text(json.dumps(info, indent=2) + "\n")
    for key, _, _ in ICONS:
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
    rows = "\n".join(f'        SpaceIcon(key: "{k}", name: "{n}"),' for k, n, _ in ICONS)
    SWIFT.write_text(f"""// Generated by scripts/space_icons.py; don't edit.

/// A space's icon (§7.7): its key, stored with the space, and what it's called.
/// The picture is the asset `space-<key>`.
struct SpaceIcon: Hashable, Identifiable {{
    let key: String
    let name: String
    var id: String {{ key }}
    var asset: String {{ "space-\\(key)" }}

    /// Shown for the default space and any icon this app doesn't have.
    static let defaultKey = "{DEFAULT}"

    /// In the picker's order.
    static let all = [
{rows}
    ]
}}
""")


def main():
    assert len({k for k, _, _ in ICONS}) == len(ICONS)
    assert all(re.fullmatch(r"[a-z0-9-]{1,64}", k) for k, _, _ in ICONS)
    if len(sys.argv) > 1:
        import_downloads(Path(sys.argv[1]))
    web()
    android()
    ios()


if __name__ == "__main__":
    main()
