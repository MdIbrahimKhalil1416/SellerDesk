import { useId, type InputHTMLAttributes, type ReactNode } from 'react'
import { cn } from '../../lib/cn'

export interface TextFieldProps extends InputHTMLAttributes<HTMLInputElement> {
  label: string
  /** Shown in red under the input; also marks the input invalid for screen readers. */
  error?: string
  /** Grey help text under the input (hidden while an error is shown). */
  hint?: ReactNode
  /** Something inside the right edge of the input, e.g. a show-password button. */
  trailing?: ReactNode
}

export function TextField({ label, error, hint, trailing, className, id, ...rest }: TextFieldProps) {
  const autoId = useId()
  const inputId = id ?? autoId
  const messageId = `${inputId}-message`

  return (
    <div className={className}>
      <label htmlFor={inputId} className="mb-1.5 block text-sm font-medium text-slate-700">
        {label}
      </label>
      <div className="relative">
        <input
          id={inputId}
          aria-invalid={error ? true : undefined}
          aria-describedby={error || hint ? messageId : undefined}
          className={cn(
            'block w-full rounded-lg border bg-white px-3 py-2.5 text-sm text-slate-900 shadow-sm outline-none transition',
            'placeholder:text-slate-400 focus:ring-2',
            error
              ? 'border-red-400 focus:border-red-500 focus:ring-red-100'
              : 'border-slate-300 focus:border-indigo-500 focus:ring-indigo-100',
            trailing ? 'pr-11' : undefined,
          )}
          {...rest}
        />
        {trailing && <div className="absolute inset-y-0 right-0 flex items-center pr-2">{trailing}</div>}
      </div>
      {error ? (
        <p id={messageId} className="mt-1.5 text-sm text-red-600">
          {error}
        </p>
      ) : hint ? (
        <div id={messageId} className="mt-1.5 text-xs text-slate-500">
          {hint}
        </div>
      ) : null}
    </div>
  )
}
