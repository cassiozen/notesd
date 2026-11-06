import NotesCore
import UIKit

/// Text outside the dialect, kept as its exact source (spec §7.1): one
/// character, shown as written, deleted whole, never edited. A raw block
/// (code, table, rule) takes the width of its line and wraps; a raw run inside
/// a line (a code span, another image) is one line, cut short if it's too long.
///
/// A picture the apps added (`notesd:image/…`, §7.6) is drawn as the picture,
/// at its own size in points, shrunk to fit the line; a rounded box holds its
/// place until it loads. Its source still goes back as written.
///
/// A code fence shows its code without the fence lines, on a dark block with
/// its language above and highlighted, as on the web.
nonisolated final class RawAttachment: NSTextAttachment {
    let source: String
    let isBlock: Bool
    /// The picture this run shows, if it's one the apps added.
    let picture: ImageRef?
    /// The code fence this block is, if it is one.
    let fence: Fence?

    init(source: String, isBlock: Bool) {
        self.source = source
        self.isBlock = isBlock
        picture = isBlock ? nil : ImageRef.parse(source)
        fence = isBlock ? Fence.parse(source) : nil
        super.init(data: nil, ofType: nil)
        if let fence {
            accessibilityLabel = fence.lang.isEmpty ? "Code: \(fence.code)" : "\(fence.lang) code: \(fence.code)"
        }
        if picture != nil {
            let alt = ImageRef.alt(source)
            accessibilityLabel = alt.isEmpty ? "Picture" : "Picture: \(alt)"
        }
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("not coded") }

    private static let padX: CGFloat = 4
    private static let padBlock: CGFloat = 8

    /// What's shown: the source as written, or a fence's code.
    private var shown: String { fence?.code ?? source }

    private var lang: String { fence?.lang ?? "" }

    private func labelFont(_ attributes: [NSAttributedString.Key: Any]) -> UIFont {
        Typeface.sans(((attributes[.font] as? UIFont)?.pointSize ?? 17) * 11 / 17, weight: 500)
    }

    /// Room for the language label above a fence's code.
    private func labelHeight(_ attributes: [NSAttributedString.Key: Any]) -> CGFloat {
        lang.isEmpty ? 0 : ceil(labelFont(attributes).lineHeight) + 4
    }

    private static let pictureRadius: CGFloat = 10

    private func font(_ attributes: [NSAttributedString.Key: Any]) -> UIFont {
        let size = (attributes[.font] as? UIFont)?.pointSize ?? 17
        return Typeface.mono(isBlock ? size : size * 0.88)
    }

    private func text(_ font: UIFont, wraps: Bool) -> NSAttributedString {
        let p = NSMutableParagraphStyle()
        p.lineBreakMode = wraps ? .byCharWrapping : .byTruncatingTail
        guard let fence else {
            return NSAttributedString(
                string: shown, attributes: [.font: font, .foregroundColor: Palette.ink2, .paragraphStyle: p])
        }
        let out = NSMutableAttributedString(
            string: shown, attributes: [.font: font, .foregroundColor: Palette.codeInk, .paragraphStyle: p])
        for run in Fence.highlight(fence.code, lang: fence.lang) {
            let color: UIColor =
                switch run.token {
                case .comment: Palette.codeDim
                case .string: Palette.codeString
                case .number: Palette.codeNumber
                case .keyword: Palette.codeKeyword
                }
            out.addAttribute(
                .foregroundColor, value: color, range: NSRange(location: run.start, length: run.end - run.start))
        }
        return out
    }

    override func attachmentBounds(
        for attributes: [NSAttributedString.Key: Any], location: any NSTextLocation, textContainer: NSTextContainer?,
        proposedLineFragment: CGRect, position: CGPoint
    ) -> CGRect {
        let font = font(attributes)
        let available = max(proposedLineFragment.width - position.x, 40)
        if let picture {
            let ratio = CGFloat(picture.height) / CGFloat(picture.width)
            var w = min(CGFloat(picture.width), available)
            // At most four lines' width tall: the size comes from the note's text, and a made-up one
            // (w=999&h=999999) would otherwise draw a bitmap thousands of screens high.
            let tallest = available * 4
            if w * ratio > tallest { w = max(tallest / ratio, 1) }
            let h = min(max((w * ratio).rounded(), 1), tallest)
            return CGRect(x: 0, y: font.descender, width: max(w.rounded(.down), 1), height: h)
        }
        if isBlock {
            let measured = text(font, wraps: true).boundingRect(
                with: CGSize(width: available - 2 * Self.padBlock, height: .greatestFiniteMagnitude),
                options: [.usesLineFragmentOrigin], context: nil)
            return CGRect(
                x: 0, y: 0, width: available,
                height: ceil(measured.height) + 2 * Self.padBlock + labelHeight(attributes))
        }
        let width = ceil(text(font, wraps: false).size().width) + 2 * Self.padX
        return CGRect(x: 0, y: font.descender - 2, width: min(width, available), height: font.lineHeight + 4)
    }

    override func image(
        for bounds: CGRect, attributes: [NSAttributedString.Key: Any], location: any NSTextLocation,
        textContainer: NSTextContainer?
    ) -> UIImage? {
        let font = font(attributes)
        let format = UIGraphicsImageRendererFormat.preferred()
        if let picture {
            let loaded = ImageLoader.shared?.image(picture.id, pixels: Int((bounds.width * format.scale).rounded(.up)))
            return UIGraphicsImageRenderer(size: bounds.size, format: format).image { _ in
                let box = CGRect(origin: .zero, size: bounds.size)
                let shape = UIBezierPath(roundedRect: box, cornerRadius: corner(Self.pictureRadius))
                Palette.hover.setFill()
                shape.fill()
                if let loaded {
                    shape.addClip()
                    loaded.draw(in: box)
                }
            }
        }
        return UIGraphicsImageRenderer(size: bounds.size, format: format).image { _ in
            let box = CGRect(origin: .zero, size: bounds.size)
            (fence != nil ? Palette.code : Palette.hover).setFill()
            UIBezierPath(roundedRect: box, cornerRadius: corner(isBlock ? 8 : 5)).fill()
            let pad = isBlock ? Self.padBlock : Self.padX
            var inner = box.insetBy(dx: pad, dy: isBlock ? pad : 2)
            if !lang.isEmpty {
                NSAttributedString(
                    string: lang,
                    attributes: [.font: labelFont(attributes), .foregroundColor: Palette.codeDim, .kern: 0.2]
                ).draw(at: inner.origin)
                inner = inner.inset(by: UIEdgeInsets(top: labelHeight(attributes), left: 0, bottom: 0, right: 0))
            }
            text(font, wraps: isBlock).draw(
                with: inner, options: [.usesLineFragmentOrigin, .truncatesLastVisibleLine], context: nil)
        }
    }
}
