import NotesCore
import UIKit

/// Sizes, faces and colours the editor draws with. Typography follows the web
/// app's phone sizes (editor.module.css): serif title and H1/H2, a sans H3,
/// italic serif quotes. Sizes scale with the user's text size.
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
    let boxSize: CGFloat = 18
    // Under half a space: two tags in a row keep a gap between their pills.
    let tagPadX: CGFloat = 2
    let tagRadius: CGFloat = 6

    func margin(_ attr: BlockAttr) -> CGFloat {
        attr.path.reduce(0) { $0 + (isItem($1) ? itemIndent : quoteIndent) }
    }

    /// The font for a block's text with these marks.
    func font(_ leaf: Leaf, quoted: Bool, bold: Bool, italic: Bool) -> UIFont {
        switch leaf {
        case .title: return Typeface.serif(body * 34 / 17)
        case .heading(1): return Typeface.serif(body * 30 / 17, italic: italic)
        case .heading(2): return Typeface.serif(body * 24 / 17, italic: italic)
        case .heading: return Typeface.sans(body, weight: 600, italic: italic)
        case .raw: return Typeface.mono(body * 0.82)
        case .paragraph:
            if quoted { return Typeface.serif(body * 20 / 17, italic: !italic) }
            return Typeface.sans(body, weight: bold ? 600 : 400, italic: italic)
        }
    }

    /// Space after a line, as the web's margins (editor.module.css). In body units, so it
    /// grows with the text size.
    func space(_ gap: Gap) -> CGFloat {
        switch gap {
        case .item: body * 0.15
        case .paragraph: body * 0.4
        case .block: body * 12 / 17
        }
    }

    func paragraphStyle(_ attr: BlockAttr) -> NSParagraphStyle {
        let p = NSMutableParagraphStyle()
        let indent = margin(attr)
        let unit = body / 17
        p.firstLineHeadIndent = indent
        p.headIndent = indent
        p.lineHeightMultiple = 1.18
        // The title and headings keep their own space below, close to the text they head,
        // unless what follows needs more.
        let wide = attr.gap == .block ? space(.block) : 0
        switch attr.leaf {
        case .title:
            p.paragraphSpacing = max(6 * unit, wide)
            p.lineHeightMultiple = 1.0
        case .heading:
            p.paragraphSpacingBefore = 13 * unit
            p.paragraphSpacing = max(2 * unit, wide)
            p.lineHeightMultiple = 1.05
        default:
            p.paragraphSpacing = space(attr.gap)
        }
        return p
    }

    /// Every display attribute for a run: the block's look plus its marks.
    func attributes(_ attr: BlockAttr, run: [NSAttributedString.Key: Any]) -> [NSAttributedString.Key: Any] {
        let bold = run[.notesBold] != nil
        let italic = run[.notesItalic] != nil
        var out: [NSAttributedString.Key: Any] = [
            .font: font(attr.leaf, quoted: attr.quoted, bold: bold, italic: italic),
            .paragraphStyle: paragraphStyle(attr),
            .foregroundColor: color(attr),
        ]
        if run[.notesStrike] != nil { out[.strikethroughStyle] = NSUnderlineStyle.single.rawValue }
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

    private static let displayKeys: [NSAttributedString.Key] = [
        .font, .paragraphStyle, .foregroundColor, .strikethroughStyle, .underlineStyle,
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
