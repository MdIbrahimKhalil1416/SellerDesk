import type { CustomerStatus } from '../../../types/customer'

/** Shown only for archived customers; active is the normal case. */
export function CustomerStatusBadge({ status }: { status: CustomerStatus }) {
  if (status === 'ACTIVE') return null
  return (
    <span className="inline-flex items-center rounded-full bg-slate-100 px-2 py-0.5 text-xs font-medium text-slate-600 ring-1 ring-slate-200">
      Archived
    </span>
  )
}

export function TagChips({ tags }: { tags: string[] }) {
  if (tags.length === 0) return null
  return (
    <span className="flex flex-wrap gap-1">
      {tags.map((t) => (
        <span key={t} className="rounded-full bg-indigo-50 px-2 py-0.5 text-xs font-medium text-indigo-700">
          {t}
        </span>
      ))}
    </span>
  )
}
