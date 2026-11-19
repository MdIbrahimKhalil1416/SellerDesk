import { cn } from '../../lib/cn'
import { passwordRuleChecks } from './passwordPolicy'

/** Live checklist under a password input. */
export function PasswordRules({ password }: { password: string }) {
  return (
    <ul className="mt-2 grid gap-1 text-xs sm:grid-cols-3" aria-label="Password rules">
      {passwordRuleChecks(password).map((rule) => (
        <li key={rule.label} className={cn('flex items-center gap-1.5', rule.ok ? 'text-emerald-600' : 'text-slate-500')}>
          <span aria-hidden="true">{rule.ok ? '✓' : '○'}</span>
          {rule.label}
          <span className="sr-only">{rule.ok ? '(met)' : '(not met)'}</span>
        </li>
      ))}
    </ul>
  )
}
