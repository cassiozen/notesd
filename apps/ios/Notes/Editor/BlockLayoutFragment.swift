import NotesCore
import UIKit

/// One block's layout (a TextKit 2 paragraph is one block). Draws what the
/// text itself doesn't: quote bars and the list marker in the margin, and the
/// pill behind each #tag, as tall as the letters rather than the line.
nonisolated final class BlockLayoutFragment: NSTextLayoutFragment {
    private let style = EditorStyle()

    private var attr: BlockAttr? {
        guard let paragraph = textElement as? NSTextParagraph, paragraph.attributedString.length > 0 else { return nil }
        return paragraph.attributedString.attribute(.notesBlock, at: 0, effectiveRange: nil) as? BlockAttr
    }

    override var renderingSurfaceBounds: CGRect {
        // From the container's edge: markers and bars sit left of the text.
        var bounds = super.renderingSurfaceBounds
        let left = -layoutFragmentFrame.minX
        if bounds.minX > left {
            bounds.size.width += bounds.minX - left
            bounds.origin.x = left
        }
        return bounds
    }

    override func draw(at point: CGPoint, in context: CGContext) {
        guard let attr else { return super.draw(at: point, in: context) }
        let height = layoutFragmentFrame.height
        let originX = point.x - layoutFragmentFrame.minX

        // Quote bars, one per quote level, in the path's order. A quote that starts or ends on this
        // line has its bar start or end with the text, not in the space between it and the next.
        let levels = attr.path.count { !isItem($0) }
        let textTop = textLineFragments.first.map { point.y + $0.typographicBounds.minY } ?? point.y
        let textBottom = textLineFragments.last.map { point.y + $0.typographicBounds.maxY } ?? point.y + height
        var offset: CGFloat = 0
        var level = 0
        for container in attr.path {
            if isItem(container) {
                offset += style.itemIndent
            } else {
                let top = level >= levels - attr.quoteEdges.starts ? textTop : point.y
                let bottom = level >= levels - attr.quoteEdges.ends ? textBottom : point.y + height
                context.setFillColor(Palette.accent.cgColor)
                context.fill(CGRect(x: originX + offset, y: top, width: style.quoteBar, height: bottom - top))
                offset += style.quoteIndent
                level += 1
            }
        }

        drawTagPills(at: point, in: context, style: style)
        super.draw(at: point, in: context)

        if let marker = attr.marker, let line = textLineFragments.first {
            let column = originX + style.margin(attr) - style.itemIndent
            drawMarker(marker, column: column, line: line, at: point, in: context, style: style)
        }
    }

    /// The first line's baseline and the middle of its letters, in drawing coordinates.
    private func metrics(_ line: NSTextLineFragment, at point: CGPoint) -> (
        baseline: CGFloat, mid: CGFloat, font: UIFont
    ) {
        let font =
            (line.attributedString.length > 0
                ? line.attributedString.attribute(.font, at: line.characterRange.location, effectiveRange: nil)
                    as? UIFont
                : nil) ?? Typeface.sans(17)
        let baseline = point.y + line.typographicBounds.minY + line.glyphOrigin.y
        return (baseline, baseline - (font.ascender + font.descender) / 2, font)
    }

    private func drawMarker(
        _ marker: Marker, column: CGFloat, line: NSTextLineFragment, at point: CGPoint, in context: CGContext,
        style: EditorStyle
    ) {
        let (baseline, mid, font) = metrics(line, at: point)
        let center = column + style.itemIndent * 0.42
        switch marker {
        case .bullet(let level):
            let r: CGFloat = 2.6
            let dot = CGRect(x: center - r, y: mid - r, width: 2 * r, height: 2 * r)
            if level % 2 == 1 {
                context.setStrokeColor(Palette.ink2.cgColor)
                context.setLineWidth(1.2)
                context.strokeEllipse(in: dot)
            } else {
                context.setFillColor(Palette.ink2.cgColor)
                context.fillEllipse(in: dot)
            }
        case .number(let n):
            let label = NSAttributedString(
                string: "\(n).", attributes: [.font: Typeface.sans(font.pointSize), .foregroundColor: Palette.ink2])
            let size = label.size()
            // Right-aligned against the text, like a list's number column.
            let x = column + style.itemIndent - 7 - size.width
            UIGraphicsPushContext(context)
            label.draw(at: CGPoint(x: x, y: baseline - Typeface.sans(font.pointSize).ascender))
            UIGraphicsPopContext()
        case .box(let checked):
            let r = style.boxSize / 2
            let box = CGRect(x: center - r, y: mid - r, width: 2 * r, height: 2 * r)
            // Round, or a rounded square, as the theme draws a task's box.
            func shape(_ rect: CGRect) -> CGPath {
                switch style.theme.shape.checkCorner {
                case .circle: CGPath(ellipseIn: rect, transform: nil)
                case .radius(let corner): UIBezierPath(roundedRect: rect, cornerRadius: corner).cgPath
                }
            }
            if checked {
                context.setFillColor(Palette.accent.cgColor)
                context.addPath(shape(box))
                context.fillPath()
                if style.theme.shape.checkMark == .dot {
                    context.setFillColor(Palette.onAccent.cgColor)
                    context.fillEllipse(in: box.insetBy(dx: r * 0.55, dy: r * 0.55))
                }
            }
            context.setLineWidth(1.5)
            context.setStrokeColor((checked ? Palette.accentDeep : Palette.ink3).cgColor)
            context.addPath(shape(box.insetBy(dx: 0.75, dy: 0.75)))
            context.strokePath()
        }
    }

    /// The web's tag pill, one per line a tag wraps across.
    private func drawTagPills(at point: CGPoint, in context: CGContext, style: EditorStyle) {
        guard let paragraph = textElement as? NSTextParagraph else { return }
        let text = paragraph.attributedString
        var tags: [NSRange] = []
        text.enumerateAttribute(.notesTag, in: NSRange(location: 0, length: text.length)) { value, range, _ in
            if value != nil { tags.append(range) }
        }
        // A theme without pills colours the tags' text instead (EditorStyle).
        if tags.isEmpty || style.theme.colors.tagBackground == nil { return }
        context.setFillColor(Palette.tagBg.cgColor)
        for line in textLineFragments {
            let lineRange = line.characterRange
            for tag in tags {
                let from = max(tag.location, lineRange.location)
                let to = min(NSMaxRange(tag), NSMaxRange(lineRange))
                guard from < to else { continue }
                let (baseline, _, font) = metrics(line, at: point)
                let x0 = point.x + line.typographicBounds.minX + line.locationForCharacter(at: from).x
                let x1 = point.x + line.typographicBounds.minX + line.locationForCharacter(at: to).x
                let rect = CGRect(
                    x: x0 - style.tagPadX, y: baseline - font.ascender, width: x1 - x0 + 2 * style.tagPadX,
                    height: font.ascender - font.descender)
                context.addPath(UIBezierPath(roundedRect: rect, cornerRadius: style.tagRadius).cgPath)
                context.fillPath()
            }
        }
    }
}
