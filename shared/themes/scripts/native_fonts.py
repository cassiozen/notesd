"""The theme fonts the iOS and Android apps bundle, built from fonts.json.

Each family with a `native.source` is downloaded from the pinned google/fonts commit, cut down
to the Latin and Latin Extended characters (the web loads the same subsets), and written to both
apps with its license. Variable axes are kept, except as a face's `limits` pins or narrows them. Every face, built here or not, must have the
PostScript name fonts.json gives it: iOS loads fonts by that name.

    mise run themes:fonts            build the files, then check the names
    mise run themes:fonts -- --check check only (what the tests run)
"""

import json
import sys
import urllib.parse
import urllib.request
from io import BytesIO
from pathlib import Path

from fontTools import subset
from fontTools.ttLib import TTFont
from fontTools.varLib import instancer

ROOT = Path(__file__).resolve().parents[3]
FONTS = ROOT / "shared/themes/fonts.json"
IOS_FONTS = ROOT / "apps/ios/Notes/Resources/Fonts"
ANDROID_FONTS = ROOT / "apps/android/app/src/main/res/font"
LICENSES = [ROOT / "apps/ios/licenses", ROOT / "apps/android/licenses"]

# Google Fonts' own subset ranges, the ones fontsource's unicode-range rules use.
UNICODES = {
    "latin": "U+0000-00FF,U+0131,U+0152-0153,U+02BB-02BC,U+02C6,U+02DA,U+02DC,U+0304,U+0308,"
    "U+0329,U+2000-206F,U+20AC,U+2122,U+2191,U+2193,U+2212,U+2215,U+FEFF,U+FFFD",
    "latin-ext": "U+0100-02BA,U+02BD-02C5,U+02C7-02CC,U+02CE-02D7,U+02DD-02FF,U+0304,U+0308,"
    "U+0329,U+1D00-1DBF,U+1E00-1E9F,U+1EF2-1EFF,U+2020,U+20A0-20AB,U+20AD-20C0,U+2113,"
    "U+2C60-2C7F,U+A720-A7FF",
}


def fetch(repository: str, commit: str, path: str) -> bytes:
    url = f"https://raw.githubusercontent.com/{repository}/{commit}/{urllib.parse.quote(path)}"
    with urllib.request.urlopen(url) as response:
        return response.read()


def build(fonts: dict) -> None:
    repo, commit = fonts["sources"]["repository"], fonts["sources"]["commit"]
    unicodes = subset.parse_unicodes(",".join(UNICODES[s] for s in fonts["subsets"]))
    for family in fonts["families"].values():
        native = family["native"]
        if "source" not in native:
            continue
        license_text = fetch(repo, commit, f"{native['source']}/OFL.txt")
        for directory in LICENSES:
            (directory / native["license"]).write_bytes(license_text)
        for face in native["faces"]:
            font = TTFont(BytesIO(fetch(repo, commit, f"{native['source']}/{face['file']}")), lazy=False)
            options = subset.Options()
            options.layout_features = ["*"]
            options.name_IDs = ["*"]
            options.notdef_outline = True
            options.hinting = False
            subsetter = subset.Subsetter(options)
            subsetter.populate(unicodes=unicodes)
            subsetter.subset(font)
            if "limits" in face:
                # Only the slice of the design space the themes use: a pinned axis is gone, a
                # narrowed one keeps its range. The bulk of a big variable font is its masters.
                limits = {axis: tuple(v) if isinstance(v, list) else v for axis, v in face["limits"].items()}
                font = instancer.instantiateVariableFont(font, limits)
                # Its names still describe the original default instance; iOS loads by this one.
                for record in font["name"].names:
                    if record.nameID == 6:
                        record.string = face["postscript"]
            # The source's own timestamp, not now: a rebuild from the same commit is the same file.
            font.recalcTimestamp = False
            font.save(IOS_FONTS / face["ios"])
            font.save(ANDROID_FONTS / f"{face['android']}.ttf")
            print(f"{face['ios']}: {(IOS_FONTS / face['ios']).stat().st_size // 1024} KB")


def check(fonts: dict) -> list[str]:
    problems = []
    for name, family in fonts["families"].items():
        native = family["native"]
        for directory in LICENSES:
            if not (directory / native["license"]).exists():
                problems.append(f"{name}: no {directory.relative_to(ROOT)}/{native['license']}")
        for face in native["faces"]:
            ios, android = IOS_FONTS / face["ios"], ANDROID_FONTS / f"{face['android']}.ttf"
            for file in (ios, android):
                if not file.exists():
                    problems.append(f"{name}: no {file.relative_to(ROOT)}")
            if ios.exists():
                actual = TTFont(ios)["name"].getDebugName(6)
                if actual != face["postscript"]:
                    problems.append(f"{name}: {face['ios']} is named {actual!r}, fonts.json says {face['postscript']!r}")
    return problems


def main() -> None:
    fonts = json.loads(FONTS.read_text())
    if "--check" not in sys.argv:
        build(fonts)
    problems = check(fonts)
    for problem in problems:
        print(problem, file=sys.stderr)
    sys.exit(1 if problems else 0)


if __name__ == "__main__":
    main()
