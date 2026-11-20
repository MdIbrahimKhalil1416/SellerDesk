import { ImageIcon } from '../../../components/icons'
import { cn } from '../../../lib/cn'
import { thumbnailUrl } from '../imageUrl'

/** Square cover image, or a grey placeholder when the product has no photo. */
export function ProductThumb({ url, size = 48, className }: { url: string | null; size?: number; className?: string }) {
  const src = thumbnailUrl(url, size)
  return (
    <div
      className={cn('flex shrink-0 items-center justify-center overflow-hidden rounded-lg bg-slate-100 text-slate-400', className)}
      style={{ width: size, height: size }}
    >
      {src ? <img src={src} alt="" loading="lazy" className="h-full w-full object-cover" /> : <ImageIcon />}
    </div>
  )
}
