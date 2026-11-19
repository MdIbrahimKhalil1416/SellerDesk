import type { ReactNode } from 'react'
import { cn } from '../../lib/cn'

type Tone = 'error' | 'success' | 'info'

const tones: Record<Tone, string> = {
  error: 'border-red-200 bg-red-50 text-red-800',
  success: 'border-emerald-200 bg-emerald-50 text-emerald-800',
  info: 'border-indigo-200 bg-indigo-50 text-indigo-800',
}

export function Alert({ tone = 'info', children, className }: { tone?: Tone; children: ReactNode; className?: string }) {
  return (
    <div
      role={tone === 'error' ? 'alert' : 'status'}
      className={cn('rounded-lg border px-3 py-2.5 text-sm', tones[tone], className)}
    >
      {children}
    </div>
  )
}
