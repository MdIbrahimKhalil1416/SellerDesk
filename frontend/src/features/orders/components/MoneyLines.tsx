import { cn } from '../../../lib/cn'
import { formatTaka } from '../../../lib/money'

/** Subtotal → COD. Shared with the live preview on the New Order form. */
export function MoneyLines({ subtotal, discount, deliveryCharge, total, advancePaid, codAmount }: {
  subtotal: number
  discount: number
  deliveryCharge: number
  total: number
  advancePaid: number
  codAmount: number
}) {
  const row = (label: string, value: string, strong?: boolean) => (
    <div className={cn('flex justify-between gap-3', strong && 'font-semibold text-slate-900')}>
      <dt className={strong ? undefined : 'text-slate-600'}>{label}</dt>
      <dd className="tabular-nums">{value}</dd>
    </div>
  )
  return (
    <dl className="mt-3 space-y-1.5">
      {row('Subtotal', formatTaka(subtotal))}
      {discount > 0 && row('Discount', `− ${formatTaka(discount)}`)}
      {row('Delivery', formatTaka(deliveryCharge))}
      <div className="border-t border-slate-100 pt-1.5">{row('Total', formatTaka(total), true)}</div>
      {advancePaid > 0 && row('Advance paid', `− ${formatTaka(advancePaid)}`)}
      <div className="rounded-lg bg-indigo-50 px-2 py-1.5 text-indigo-900">{row('Cash on delivery', formatTaka(codAmount), true)}</div>
    </dl>
  )
}
