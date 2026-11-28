import { cn } from '../../../lib/cn'
import type { Movement } from '../../../types/inventory'
import { formatDateTime, MOVEMENT_LABELS, REASON_LABELS, signed } from '../labels'

/** What a movement did, in one short line: "Damaged · water damage", "Order SD-1042", the note... */
function details(m: Movement): string | null {
  const parts: string[] = []
  if (m.reasonCode) parts.push(REASON_LABELS[m.reasonCode].replace(' (explain in the note)', ''))
  if (m.orderNumber) parts.push(`Order ${m.orderNumber}`)
  if (m.note) parts.push(m.note)
  return parts.length > 0 ? parts.join(' · ') : null
}

function Delta({ value }: { value: number }) {
  return <span className={cn('font-medium tabular-nums', value > 0 ? 'text-emerald-700' : value < 0 ? 'text-red-700' : 'text-slate-400')}>{signed(value)}</span>
}

/** Movement history: a table on desktop, cards on phones. Newest first (as the backend sends it). */
export function MovementList({ movements }: { movements: Movement[] }) {
  return (
    <>
      <div className="hidden overflow-x-auto rounded-xl border border-slate-200 bg-white md:block">
        <table className="w-full text-left text-sm">
          <thead className="border-b border-slate-200 bg-slate-50 text-xs uppercase tracking-wide text-slate-500">
            <tr>
              <th className="px-4 py-3 font-medium">When</th>
              <th className="px-4 py-3 font-medium">Type</th>
              <th className="px-4 py-3 text-right font-medium">On hand</th>
              <th className="px-4 py-3 text-right font-medium">Reserved</th>
              <th className="px-4 py-3 text-right font-medium">Available after</th>
              <th className="px-4 py-3 font-medium">Details</th>
              <th className="px-4 py-3 font-medium">By</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100">
            {movements.map((m) => (
              <tr key={m.id}>
                <td className="whitespace-nowrap px-4 py-3 text-slate-600">{formatDateTime(m.createdAt)}</td>
                <td className="whitespace-nowrap px-4 py-3 font-medium text-slate-900">{MOVEMENT_LABELS[m.type]}</td>
                <td className="px-4 py-3 text-right">
                  <Delta value={m.onHandDelta} />
                  <span className="block text-xs text-slate-400">→ {m.onHandAfter}</span>
                </td>
                <td className="px-4 py-3 text-right">
                  <Delta value={m.reservedDelta} />
                  <span className="block text-xs text-slate-400">→ {m.reservedAfter}</span>
                </td>
                <td className="px-4 py-3 text-right font-medium tabular-nums text-slate-900">{m.availableAfter}</td>
                <td className="max-w-xs px-4 py-3 text-slate-600">{details(m) ?? <span className="text-slate-400">—</span>}</td>
                <td className="whitespace-nowrap px-4 py-3 text-slate-600">{m.createdBy.name}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <ul className="space-y-2 md:hidden">
        {movements.map((m) => (
          <li key={m.id} className="rounded-xl border border-slate-200 bg-white p-3 text-sm">
            <div className="flex items-start justify-between gap-2">
              <p className="font-medium text-slate-900">{MOVEMENT_LABELS[m.type]}</p>
              <p className="text-right text-xs text-slate-500">{formatDateTime(m.createdAt)}</p>
            </div>
            <p className="mt-1 text-slate-600">
              On hand <Delta value={m.onHandDelta} /> → {m.onHandAfter} · Reserved <Delta value={m.reservedDelta} /> → {m.reservedAfter}
            </p>
            <p className="text-slate-600">
              Available after: <span className="font-medium text-slate-900">{m.availableAfter}</span>
            </p>
            {details(m) && <p className="mt-1 text-slate-500">{details(m)}</p>}
            <p className="mt-1 text-xs text-slate-400">By {m.createdBy.name}</p>
          </li>
        ))}
      </ul>
    </>
  )
}
