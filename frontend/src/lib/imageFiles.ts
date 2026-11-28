/** Image upload limits, the same as the backend (docs/modules/PRODUCT.md §4). Used for product photos and the shop logo. */
/** Photos per product. */
export const MAX_IMAGES = 8
export const MAX_IMAGE_MB = 5
export const MAX_IMAGE_BYTES = MAX_IMAGE_MB * 1024 * 1024

const ALLOWED_TYPES = ['image/jpeg', 'image/png', 'image/webp']

/** First bytes of real JPEG / PNG / WebP files (a renamed file still fails). */
async function hasImageSignature(file: File): Promise<boolean> {
  const b = new Uint8Array(await file.slice(0, 12).arrayBuffer())
  const at = (i: number) => b[i] ?? -1
  const ascii = (from: number, text: string) => [...text].every((c, i) => at(from + i) === c.charCodeAt(0))
  const jpeg = at(0) === 0xff && at(1) === 0xd8 && at(2) === 0xff
  const png = at(0) === 0x89 && ascii(1, 'PNG')
  const webp = ascii(0, 'RIFF') && ascii(8, 'WEBP')
  return jpeg || png || webp
}

/** null if the file looks fine, otherwise a message for the user. */
export async function checkImageFile(file: File): Promise<string | null> {
  if (!ALLOWED_TYPES.includes(file.type) || !(await hasImageSignature(file))) {
    return 'only JPEG, PNG and WebP images are allowed.'
  }
  if (file.size > MAX_IMAGE_BYTES) {
    return `the file is ${(file.size / 1024 / 1024).toFixed(1)} MB; the maximum is ${MAX_IMAGE_MB} MB.`
  }
  if (file.size === 0) return 'the file is empty.'
  return null
}
