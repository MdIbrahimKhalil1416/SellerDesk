import { cn } from '../../../lib/cn'
import type { OrderStatus } from '../../../types/order'
import { STATUS_LABELS, STATUS_STYLES } from '../orderLabels'

export function OrderStatusBadge({ status, className }: { status: OrderStatus; className?: string }) {
  return (
    <span className={cn('inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium ring-1', STATUS_STYLES[status], className)}>
      {STATUS_LABELS[status]}
    </span>
  )
}
