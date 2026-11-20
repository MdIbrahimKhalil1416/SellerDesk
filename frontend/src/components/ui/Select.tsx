import { useId, type SelectHTMLAttributes } from 'react'
import { cn } from '../../lib/cn'

interface SelectProps extends SelectHTMLAttributes<HTMLSelectElement> {
  label: string
  /** Visually hide the label (it stays for screen readers). */
  hideLabel?: boolean
  options: Array<{ value: string; label: string }>
}

export function Select({ label, hideLabel, options, className, id, ...rest }: SelectProps) {
  const autoId = useId()
  const selectId = id ?? autoId
  return (
    <div className={className}>
      <label htmlFor={selectId} className={cn('mb-1.5 block text-sm font-medium text-slate-700', hideLabel && 'sr-only')}>
        {label}
      </label>
      <select
        id={selectId}
        className="block w-full rounded-lg border border-slate-300 bg-white px-3 py-2.5 text-sm text-slate-900 shadow-sm outline-none focus:border-indigo-500 focus:ring-2 focus:ring-indigo-100 disabled:bg-slate-50"
        {...rest}
      >
        {options.map((o) => (
          <option key={o.value} value={o.value}>
            {o.label}
          </option>
        ))}
      </select>
    </div>
  )
}
