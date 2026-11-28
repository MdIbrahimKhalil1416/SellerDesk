import { cn } from '../../../lib/cn'
import type { StockStatus } from '../../../types/inventory'

const STYLES: Record<StockStatus, { label: string; className: string }> = {
  IN_STOCK: { label: 'In stock', className: 'bg-emerald-50 text-emerald-700 ring-emerald-200' },
  LOW: { label: 'Low stock', className: 'bg-amber-50 text-amber-800 ring-amber-200' },
  OUT: { label: 'Out of stock', className: 'bg-red-50 text-red-700 ring-red-200' },
}

/** In stock / Low stock / Out of stock. Status is computed by the backend from `available` and the threshold. */
export function StockStatusBadge({ status }: { status: StockStatus }) {
  const style = STYLES[status]
  return (
    <span className={cn('inline-flex items-center whitespace-nowrap rounded-full px-2 py-0.5 text-xs font-medium ring-1', style.className)}>
      {style.label}
    </span>
  )
}

/** Grey chip for variants that cannot be ordered any more (inactive variant or archived product). */
export function InactiveBadge({ archived }: { archived: boolean }) {
  return (
    <span className="inline-flex items-center whitespace-nowrap rounded-full bg-slate-100 px-2 py-0.5 text-xs font-medium text-slate-600 ring-1 ring-slate-200">
      {archived ? 'Archived' : 'Inactive'}
    </span>
  )
}
