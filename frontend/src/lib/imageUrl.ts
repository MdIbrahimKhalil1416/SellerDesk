/**
 * A smaller version of a Cloudinary image for thumbnails, e.g. 96 × 96 instead of the 2000 px
 * original: much less mobile data in long lists. Other URLs are returned unchanged.
 */
export function thumbnailUrl(url: string | null, size: number): string | null {
  if (!url) return null
  const marker = '/image/upload/'
  const at = url.indexOf(marker)
  if (at === -1) return url
  const px = size * 2 // sharp on high-density phone screens
  return `${url.slice(0, at + marker.length)}c_fill,w_${px},h_${px},q_auto,f_auto/${url.slice(at + marker.length)}`
}
