import type { ReactNode } from 'react'

export function AuthHeader({ title, subtitle }: { title: string; subtitle?: ReactNode }) {
  return (
    <div className="mb-6">
      <h1 className="text-2xl font-semibold tracking-tight text-slate-900">{title}</h1>
      {subtitle && <p className="mt-1.5 text-sm text-slate-600">{subtitle}</p>}
    </div>
  )
}
