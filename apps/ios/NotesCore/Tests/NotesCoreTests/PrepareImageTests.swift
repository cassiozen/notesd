import CoreGraphics
import Foundation
import ImageIO
import Testing
import UniformTypeIdentifiers

@testable import NotesCore

struct PrepareImageTests {
    private func image(_ w: Int, _ h: Int, alpha: Bool) -> CGImage {
        let ctx = CGContext(
            data: nil, width: w, height: h, bitsPerComponent: 8, bytesPerRow: 0,
            space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
        ctx.setFillColor(CGColor(red: 1, green: 0, blue: 0, alpha: alpha ? 0.5 : 1))
        ctx.fill(CGRect(x: 0, y: 0, width: w, height: h))
        return ctx.makeImage()!
    }

    private func encode(_ image: CGImage, _ type: UTType, _ props: [CFString: Any] = [:]) -> Data {
        let out = NSMutableData()
        let dest = CGImageDestinationCreateWithData(out, type.identifier as CFString, 1, nil)!
        CGImageDestinationAddImage(dest, image, props as CFDictionary)
        CGImageDestinationFinalize(dest)
        return out as Data
    }

    private func properties(_ data: Data) -> [CFString: Any] {
        let source = CGImageSourceCreateWithData(data as CFData, nil)!
        return CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as! [CFString: Any]
    }

    @Test func aProfilePhotoIsTheMiddleSquareAtMost512AndStripped() throws {
        let photo = encode(
            image(1200, 800, alpha: false), .jpeg,
            [kCGImagePropertyGPSDictionary: [kCGImagePropertyGPSLatitude: 52.5, kCGImagePropertyGPSLatitudeRef: "N"]])
        let square = try PrepareImage.photo(photo)
        let props = properties(square)
        #expect(props[kCGImagePropertyPixelWidth] as? Int == 512)
        #expect(props[kCGImagePropertyPixelHeight] as? Int == 512)
        #expect(props[kCGImagePropertyGPSDictionary] == nil)
        let source = CGImageSourceCreateWithData(square as CFData, nil)!
        #expect(CGImageSourceGetType(source).flatMap { UTType($0 as String) } == .jpeg)

        // A small one stays its size: only cropped.
        let small = properties(try PrepareImage.photo(encode(image(100, 60, alpha: true), .png)))
        #expect(small[kCGImagePropertyPixelWidth] as? Int == 60)
        #expect(small[kCGImagePropertyPixelHeight] as? Int == 60)
        #expect(throws: ImageError.self) { try PrepareImage.photo(Data("not a picture".utf8)) }
    }

    @Test func aLargePhotoIsCappedTurnedUprightAndStripped() throws {
        let photo = encode(
            image(4000, 3000, alpha: false), .jpeg,
            [
                kCGImagePropertyOrientation: 6,
                kCGImagePropertyGPSDictionary: [
                    kCGImagePropertyGPSLatitude: 52.5, kCGImagePropertyGPSLatitudeRef: "N",
                ],
                kCGImagePropertyExifDictionary: [kCGImagePropertyExifUserComment: "secret"],
            ])
        #expect(properties(photo)[kCGImagePropertyGPSDictionary] != nil)
        let prepared = try PrepareImage.prepare(photo)
        #expect(prepared.type == "image/jpeg")
        // Orientation 6 is a quarter turn: the stored 4000×3000 shows as 3000×4000.
        #expect(prepared.width == 1536 && prepared.height == 2048)
        let props = properties(prepared.bytes)
        #expect(props[kCGImagePropertyGPSDictionary] == nil)
        #expect((props[kCGImagePropertyOrientation] as? Int ?? 1) == 1)
        let exif = props[kCGImagePropertyExifDictionary] as? [CFString: Any]
        #expect(exif?[kCGImagePropertyExifUserComment] == nil)
    }

    @Test func transparencyKeepsPNGAndOpaquePNGBecomesJPEG() throws {
        let clear = try PrepareImage.prepare(encode(image(10, 20, alpha: true), .png))
        #expect(clear.type == "image/png" && clear.width == 10 && clear.height == 20)
        let opaque = try PrepareImage.prepare(encode(image(10, 20, alpha: false), .png))
        #expect(opaque.type == "image/jpeg")
    }

    @Test func aSmallTransparentPartOfALargePictureKeepsPNG() throws {
        let ctx = CGContext(
            data: nil, width: 1600, height: 1200, bitsPerComponent: 8, bytesPerRow: 0,
            space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
        ctx.setFillColor(CGColor(red: 0, green: 0, blue: 1, alpha: 1))
        ctx.fill(CGRect(x: 0, y: 0, width: 1600, height: 1200))
        // A 2×2 hole: drawn at 64×64 it would average away.
        ctx.clear(CGRect(x: 801, y: 601, width: 2, height: 2))
        let prepared = try PrepareImage.prepare(encode(ctx.makeImage()!, .png))
        #expect(prepared.type == "image/png")
    }

    @Test func gifsPassThroughAndOtherBytesAreRefused() throws {
        let gif = encode(image(3, 2, alpha: false), .gif)
        let prepared = try PrepareImage.prepare(gif)
        #expect(prepared.bytes == gif && prepared.type == "image/gif" && prepared.width == 3)
        #expect(throws: ImageError("That picture couldn’t be read.")) { try PrepareImage.prepare(Data("<svg/>".utf8)) }
    }
}
