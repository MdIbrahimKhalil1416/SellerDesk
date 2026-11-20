import { cn } from '../../../lib/cn'
import type { ProductStatus } from '../../../types/product'

export function ProductStatusBadge({ status }: { status: ProductStatus }) {
  return (
    <span
      className={cn(
        'inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium',
        status === 'ACTIVE' ? 'bg-emerald-50 text-emerald-700 ring-1 ring-emerald-200' : 'bg-slate-100 text-slate-600 ring-1 ring-slate-200',
      )}
    >
      {status === 'ACTIVE' ? 'Active' : 'Archived'}
    </span>
  )
}
