import NotesCore
import UIKit

/// Sizes, faces and colours the editor draws with. Typography follows the web
/// app's phone sizes (editor.module.css): the theme's title face for the title, H1/H2 and
/// quotes, its body face for the rest. Sizes scale with the user's text size.
nonisolated struct EditorStyle {
    var body: CGFloat

    init(traits: UITraitCollection? = nil) {
        body = UIFontMetrics(forTextStyle: .body).scaledValue(for: 17, compatibleWith: traits)
    }

    /// Width one list level adds; the marker is drawn inside it.
    let itemIndent: CGFloat = 28
    /// Width one quote level adds; the bar sits at its start.
    let quoteIndent: CGFloat = 18
    let quoteBar: CGFloat = 2
    /// Space a quote adds above and below itself (with a line's own 4 after,
    /// about the web's 0.6em), so two quotes in a row read as two.
    let quoteSpaceBefore: CGFloat = 6
    let quoteSpaceAfter: CGFloat = 10
    let boxSize: CGFloat = 18
    // Under half a space: two tags in a row keep a gap between their pills.
    let tagPadX: CGFloat = 2
    let theme = CurrentTheme.theme
    var tagRadius: CGFloat { theme.shape.tagRadius }

    func margin(_ attr: BlockAttr) -> CGFloat {
        attr.path.reduce(0) { $0 + (isItem($1) ? itemIndent : quoteIndent) }
    }

    /// The font for a block's text with these marks.
    func font(_ leaf: Leaf, quoted: Bool, bold: Bool, italic: Bool) -> UIFont {
        switch leaf {
        case .title: return Typeface.title(body * 34 / 17)
        case .heading(1): return Typeface.title(body * 30 / 17, italic: italic)
        case .heading(2): return Typeface.title(body * 24 / 17, italic: italic)
        case .heading: return Typeface.sans(body, weight: 600, italic: italic)
        case .raw: return Typeface.mono(body * 0.82)
        case .paragraph:
            if quoted { return Typeface.quote(body * 20 / 17, italic: italic) }
            return Typeface.sans(body, weight: bold ? 600 : 400, italic: italic)
        }
    }

    func paragraphStyle(_ attr: BlockAttr) -> NSParagraphStyle {
        let p = NSMutableParagraphStyle()
        let indent = margin(attr)
        p.firstLineHeadIndent = indent
        p.headIndent = indent
        p.lineHeightMultiple = 1.18
        switch attr.leaf {
        case .title:
            p.paragraphSpacing = 6
            p.lineHeightMultiple = 1.0
        case .heading:
            p.paragraphSpacingBefore = 13
            p.paragraphSpacing = 2
            p.lineHeightMultiple = 1.05
        default:
            p.paragraphSpacing = 4
        }
        if attr.quoteEdges.starts > 0 { p.paragraphSpacingBefore += quoteSpaceBefore }
        if attr.quoteEdges.ends > 0 { p.paragraphSpacing = max(p.paragraphSpacing, quoteSpaceAfter) }
        return p
    }

    /// Every display attribute for a run: the block's look plus its marks.
    func attributes(_ attr: BlockAttr, run: [NSAttributedString.Key: Any]) -> [NSAttributedString.Key: Any] {
        let bold = run[.notesBold] != nil
        let italic = run[.notesItalic] != nil
        let font = font(attr.leaf, quoted: attr.quoted, bold: bold, italic: italic)
        var out: [NSAttributedString.Key: Any] = [
            .font: font,
            .paragraphStyle: paragraphStyle(attr),
            .foregroundColor: color(attr),
        ]
        if titled(attr), theme.title.tracking != 0 { out[.kern] = Typeface.titleKern(font) }
        if run[.notesStrike] != nil || (attr.dim && theme.shape.doneStrikethrough) {
            out[.strikethroughStyle] = NSUnderlineStyle.single.rawValue
        }
        if run[.notesTag] != nil, let ink = Palette.tagInk { out[.foregroundColor] = ink }
        if run[.notesLink] != nil {
            out[.foregroundColor] = Palette.link
            out[.underlineStyle] = NSUnderlineStyle.single.rawValue
        }
        return out
    }

    private func color(_ attr: BlockAttr) -> UIColor {
        if case .raw = attr.leaf { return Palette.ink2 }
        if attr.dim { return Palette.ink3 }
        if attr.quoted && attr.leaf == .paragraph { return Palette.ink2 }
        return Palette.ink
    }

    /// Set in the title face: the title, H1 and H2, and quotes.
    private func titled(_ attr: BlockAttr) -> Bool {
        switch attr.leaf {
        case .title, .heading(1), .heading(2): true
        case .paragraph: attr.quoted
        default: false
        }
    }

    private static let displayKeys: [NSAttributedString.Key] = [
        .font, .paragraphStyle, .foregroundColor, .strikethroughStyle, .underlineStyle, .kern,
    ]

    /// Restyles the blocks at `indices` from their structure and marks.
    func apply(_ text: NSMutableAttributedString, blocks: [BlockRange], indices: IndexSet) {
        for k in indices where k < blocks.count {
            let b = blocks[k]
            guard let attr = Blocks.attr(text, at: b.start), NSMaxRange(b.whole) <= text.length else { continue }
            for key in Self.displayKeys { text.removeAttribute(key, range: b.whole) }
            text.enumerateAttributes(in: b.whole) { run, range, _ in
                text.addAttributes(attributes(attr, run: run), range: range)
            }
        }
    }
}
