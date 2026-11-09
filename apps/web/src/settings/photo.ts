import { ImageError } from '../images/prepare'

/** The server's largest photo side (auth.AvatarSize). */
export const PHOTO_SIDE = 512
const QUALITY = 0.88

/**
 * A profile photo as the server takes it: the middle square of the picture, at most PHOTO_SIDE a side, as a JPEG.
 * Drawing it again also drops EXIF, such as where it was taken.
 */
export async function preparePhoto(file: Blob): Promise<Uint8Array<ArrayBuffer>> {
  if (!file.type.startsWith('image/') || file.type === 'image/svg+xml') {
    throw new ImageError('Choose a photo or picture (JPEG, PNG, WebP, HEIC where supported).')
  }
  let bitmap: ImageBitmap
  try {
    bitmap = await createImageBitmap(file, { imageOrientation: 'from-image' })
  } catch {
    throw new ImageError('This browser can’t read that picture.')
  }
  try {
    const crop = Math.min(bitmap.width, bitmap.height)
    const side = Math.min(crop, PHOTO_SIDE)
    const canvas = document.createElement('canvas')
    canvas.width = side
    canvas.height = side
    const ctx = canvas.getContext('2d')
    if (!ctx) throw new ImageError('This browser can’t resize pictures.')
    // JPEG has no transparency: what's see-through in a PNG shows white, not black.
    ctx.fillStyle = '#fff'
    ctx.fillRect(0, 0, side, side)
    ctx.drawImage(bitmap, (bitmap.width - crop) / 2, (bitmap.height - crop) / 2, crop, crop, 0, 0, side, side)
    const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, 'image/jpeg', QUALITY))
    if (!blob) throw new ImageError('This browser can’t resize pictures.')
    return new Uint8Array(await blob.arrayBuffer())
  } finally {
    bitmap.close()
  }
}
