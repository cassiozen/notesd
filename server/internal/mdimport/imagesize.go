package mdimport

import (
	"bytes"
	"encoding/binary"
	"errors"
	"image"
	_ "image/gif"  // registers the decoder DecodeConfig uses
	_ "image/jpeg" // registers the decoder DecodeConfig uses
	_ "image/png"  // registers the decoder DecodeConfig uses
)

// maxSide is the largest width or height an image reference may carry (§7.6).
const maxSide = 999_999

// imageSize is an image's size as shown: a JPEG whose EXIF orientation
// turns it sideways shows with width and height swapped.
func imageSize(contentType string, data []byte) (w, h int, err error) {
	if contentType == "image/webp" {
		w, h, err = webpSize(data)
	} else {
		var cfg image.Config
		cfg, _, err = image.DecodeConfig(bytes.NewReader(data))
		w, h = cfg.Width, cfg.Height
	}
	if err != nil {
		return 0, 0, err
	}
	if w < 1 || h < 1 || w > maxSide || h > maxSide {
		return 0, 0, errors.New("image size out of range")
	}
	if contentType == "image/jpeg" && jpegOrientation(data) >= 5 {
		w, h = h, w
	}
	return w, h, nil
}

var errWebP = errors.New("not a WebP image it can read")

// webpSize reads the canvas size from a WebP header: lossy (VP8), lossless
// (VP8L) or extended (VP8X).
func webpSize(b []byte) (int, int, error) {
	if len(b) < 30 || string(b[0:4]) != "RIFF" || string(b[8:12]) != "WEBP" {
		return 0, 0, errWebP
	}
	chunk := b[20:]
	switch string(b[12:16]) {
	case "VP8 ":
		if chunk[3] != 0x9d || chunk[4] != 0x01 || chunk[5] != 0x2a {
			return 0, 0, errWebP
		}
		w := int(binary.LittleEndian.Uint16(chunk[6:8]) & 0x3fff)
		h := int(binary.LittleEndian.Uint16(chunk[8:10]) & 0x3fff)
		return w, h, nil
	case "VP8L":
		if chunk[0] != 0x2f {
			return 0, 0, errWebP
		}
		bits := binary.LittleEndian.Uint32(chunk[1:5])
		return int(bits&0x3fff) + 1, int(bits>>14&0x3fff) + 1, nil
	case "VP8X":
		w := int(uint32(chunk[4]) | uint32(chunk[5])<<8 | uint32(chunk[6])<<16)
		h := int(uint32(chunk[7]) | uint32(chunk[8])<<8 | uint32(chunk[9])<<16)
		return w + 1, h + 1, nil
	}
	return 0, 0, errWebP
}

// jpegOrientation is the EXIF orientation (1–8) in a JPEG's APP1 segment, or
// 0 if it has none.
func jpegOrientation(b []byte) int {
	i := 2 // after SOI
	for i+4 <= len(b) && b[i] == 0xFF {
		marker := b[i+1]
		size := int(binary.BigEndian.Uint16(b[i+2 : i+4]))
		if marker == 0xDA || size < 2 || i+2+size > len(b) {
			return 0 // image data starts: no EXIF before it
		}
		seg := b[i+4 : i+2+size]
		if marker == 0xE1 && len(seg) > 14 && string(seg[:6]) == "Exif\x00\x00" {
			return exifOrientation(seg[6:])
		}
		i += 2 + size
	}
	return 0
}

func exifOrientation(tiff []byte) int {
	var order binary.ByteOrder
	switch string(tiff[:2]) {
	case "II":
		order = binary.LittleEndian
	case "MM":
		order = binary.BigEndian
	default:
		return 0
	}
	ifd := int(order.Uint32(tiff[4:8]))
	if ifd < 8 || ifd+2 > len(tiff) {
		return 0
	}
	n := int(order.Uint16(tiff[ifd : ifd+2]))
	for k := 0; k < n; k++ {
		e := ifd + 2 + 12*k
		if e+12 > len(tiff) {
			return 0
		}
		if order.Uint16(tiff[e:e+2]) == 0x0112 {
			if v := int(order.Uint16(tiff[e+8 : e+10])); v >= 1 && v <= 8 {
				return v
			}
			return 0
		}
	}
	return 0
}
