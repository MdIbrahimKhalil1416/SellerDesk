import { cn } from '../../../lib/cn'
import type { StaffRowStatus } from '../../../types/staff'
import { STATUS_LABELS } from '../staffLabels'

const tones: Record<StaffRowStatus, string> = {
  ACTIVE: 'bg-emerald-50 text-emerald-700 ring-emerald-200',
  DISABLED: 'bg-slate-100 text-slate-600 ring-slate-200',
  INVITED: 'bg-indigo-50 text-indigo-700 ring-indigo-200',
  EXPIRED: 'bg-amber-50 text-amber-700 ring-amber-200',
}

export function StaffStatusBadge({ status }: { status: StaffRowStatus }) {
  return (
    <span className={cn('inline-flex shrink-0 items-center rounded-full px-2 py-0.5 text-xs font-medium ring-1 ring-inset', tones[status])}>
      {STATUS_LABELS[status]}
    </span>
  )
}
