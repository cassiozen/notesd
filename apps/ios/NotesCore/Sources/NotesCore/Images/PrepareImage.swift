import Foundation
import ImageIO
import UniformTypeIdentifiers

/// A picture ready to store: re-encoded bytes, their type and pixel size.
public struct PreparedImage: Sendable {
    public var bytes: Data
    public var type: String
    public var width: Int
    public var height: Int
}

/// Couldn't make a note picture from what was picked; the message is for the user.
public struct ImageError: Error, Equatable {
    public var message: String

    public init(_ message: String) { self.message = message }
}

public enum PrepareImage {
    public static let maxSide = 2048
    /// The server's 10 MiB cap, less AES-GCM's nonce and tag, so a picture fits locked or not.
    public static let maxBytes = (10 << 20) - (12 + 16)
    private static let quality = 0.85

    /// Downscaling before upload (architecture §7.6): the long side is capped at
    /// `maxSide`, orientation applied, and the picture re-encoded as JPEG, or
    /// PNG when it has transparency, which drops metadata such as location.
    /// GIFs are kept as they are, for animation.
    public static func prepare(_ data: Data) throws(ImageError) -> PreparedImage {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil), CGImageSourceGetCount(source) > 0,
            let props = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
            let w = props[kCGImagePropertyPixelWidth] as? Int, let h = props[kCGImagePropertyPixelHeight] as? Int,
            w > 0, h > 0
        else {
            throw ImageError("That picture couldn’t be read.")
        }
        let type = CGImageSourceGetType(source).flatMap { UTType($0 as String) }
        if type == .gif {
            if data.count > maxBytes { throw ImageError("GIFs can be at most 10 MB.") }
            return PreparedImage(bytes: data, type: "image/gif", width: w, height: h)
        }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: min(maxSide, max(w, h)),
            kCGImageSourceShouldCacheImmediately: true,
        ]
        guard let image = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else {
            throw ImageError("That picture couldn’t be read.")
        }
        let png = type != .jpeg && hasAlpha(image)
        let out = NSMutableData()
        let utType = png ? UTType.png : UTType.jpeg
        guard let dest = CGImageDestinationCreateWithData(out, utType.identifier as CFString, 1, nil) else {
            throw ImageError("That picture couldn’t be read.")
        }
        // Only the pixels: no EXIF, GPS or other metadata is copied over.
        let encode: [CFString: Any] = png ? [:] : [kCGImageDestinationLossyCompressionQuality: quality]
        CGImageDestinationAddImage(dest, image, encode as CFDictionary)
        guard CGImageDestinationFinalize(dest) else { throw ImageError("That picture couldn’t be read.") }
        if out.length > maxBytes { throw ImageError("That picture is too large, even resized.") }
        return PreparedImage(
            bytes: out as Data, type: png ? "image/png" : "image/jpeg", width: image.width, height: image.height)
    }

    /// The server's largest profile photo side (auth.AvatarSize).
    public static let photoSide = 512

    /// A profile photo as the server takes it: the middle square of the picture, upright, at most `photoSide` a
    /// side, as a JPEG. Only the pixels are kept, so no EXIF such as location; see-through parts show white.
    public static func photo(_ data: Data) throws(ImageError) -> Data {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil), CGImageSourceGetCount(source) > 0,
            let props = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
            let w = props[kCGImagePropertyPixelWidth] as? Int, let h = props[kCGImagePropertyPixelHeight] as? Int,
            w > 0, h > 0
        else {
            throw ImageError("That picture couldn’t be read.")
        }
        // Scaled so the short side, which becomes the square's, is at most photoSide.
        let scale = min(1, Double(photoSide) / Double(min(w, h)))
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: Int((Double(max(w, h)) * scale).rounded(.up)),
            kCGImageSourceShouldCacheImmediately: true,
        ]
        guard let image = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else {
            throw ImageError("That picture couldn’t be read.")
        }
        let side = min(image.width, image.height, photoSide)
        guard
            let ctx = CGContext(
                data: nil, width: side, height: side, bitsPerComponent: 8, bytesPerRow: 0,
                space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)
        else { throw ImageError("That picture couldn’t be read.") }
        ctx.setFillColor(CGColor(red: 1, green: 1, blue: 1, alpha: 1))
        ctx.fill(CGRect(x: 0, y: 0, width: side, height: side))
        ctx.draw(
            image,
            in: CGRect(
                x: -(image.width - side) / 2, y: -(image.height - side) / 2, width: image.width,
                height: image.height))
        let out = NSMutableData()
        guard let square = ctx.makeImage(),
            let dest = CGImageDestinationCreateWithData(out, UTType.jpeg.identifier as CFString, 1, nil)
        else { throw ImageError("That picture couldn’t be read.") }
        CGImageDestinationAddImage(dest, square, [kCGImageDestinationLossyCompressionQuality: 0.88] as CFDictionary)
        guard CGImageDestinationFinalize(dest) else { throw ImageError("That picture couldn’t be read.") }
        return out as Data
    }

    /// Whether any pixel is less than opaque: an alpha channel alone (common in screenshots) isn't enough.
    private static func hasAlpha(_ image: CGImage) -> Bool {
        switch image.alphaInfo {
        case .none, .noneSkipFirst, .noneSkipLast: return false
        default: break
        }
        // At full size (already at most 2048 px): drawn smaller, a small transparent part averages away.
        let w = image.width
        let h = image.height
        var pixels = [UInt8](repeating: 255, count: w * h * 4)
        let drawn = pixels.withUnsafeMutableBytes { buffer -> Bool in
            guard
                let ctx = CGContext(
                    data: buffer.baseAddress, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4,
                    space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)
            else { return false }
            ctx.clear(CGRect(x: 0, y: 0, width: w, height: h))
            ctx.draw(image, in: CGRect(x: 0, y: 0, width: w, height: h))
            return true
        }
        guard drawn else { return true }
        return stride(from: 3, to: pixels.count, by: 4).contains { pixels[$0] < 255 }
    }
}
