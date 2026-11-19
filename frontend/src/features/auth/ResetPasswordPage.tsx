import { useState, type FormEvent } from 'react'
import { Link, useLocation, useNavigate, useSearchParams } from 'react-router'
import { Alert } from '../../components/ui/Alert'
import { Button } from '../../components/ui/Button'
import { PasswordField } from '../../components/ui/PasswordField'
import { TextField } from '../../components/ui/TextField'
import { errorMessage, fieldErrorsOf, getApiError } from '../../lib/apiError'
import { useCountdown } from '../../lib/useCountdown'
import { AuthHeader } from './AuthHeader'
import { useForgotPassword, useResetPassword } from './hooks'
import { isPasswordValid, SIX_DIGITS } from './passwordPolicy'
import { PasswordRules } from './PasswordRules'

type Errors = Partial<Record<'email' | 'code' | 'newPassword' | 'confirmPassword', string>>

export function ResetPasswordPage() {
  const [params] = useSearchParams()
  const location = useLocation()
  const navigate = useNavigate()
  const initialCooldown = (location.state as { resendAfterSeconds?: number } | null)?.resendAfterSeconds ?? 0

  const [email, setEmail] = useState(params.get('email') ?? '')
  const [code, setCode] = useState('')
  const [newPassword, setNewPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const [errors, setErrors] = useState<Errors>({})
  const [formError, setFormError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(
    initialCooldown > 0 ? 'If an account exists for this email, we sent a 6-digit code to it.' : null,
  )

  const reset = useResetPassword()
  const resend = useForgotPassword()
  const cooldown = useCountdown(initialCooldown)

  function onSubmit(event: FormEvent) {
    event.preventDefault()
    setFormError(null)
    const e: Errors = {}
    if (!email.includes('@')) e.email = 'Enter your email address'
    if (!SIX_DIGITS.test(code)) e.code = 'Enter the 6-digit code from the email'
    if (!isPasswordValid(newPassword)) e.newPassword = 'Password does not meet the rules below'
    if (confirmPassword !== newPassword) e.confirmPassword = 'Passwords do not match'
    setErrors(e)
    if (Object.keys(e).length > 0) return

    reset.mutate(
      { email: email.trim(), code, newPassword },
      {
        onSuccess: () =>
          navigate('/login', {
            replace: true,
            state: { notice: 'Password changed. Log in with your new password. You were logged out on all devices.' },
          }),
        onError: (err) => {
          const apiCode = getApiError(err)?.code
          if (apiCode === 'INVALID_OTP') {
            setErrors({ code: 'That code is wrong or has expired. Request a new code if needed.' })
            setCode('')
          } else if (apiCode === 'VALIDATION_FAILED') {
            setErrors(fieldErrorsOf(err))
          } else {
            setFormError(errorMessage(err))
          }
        },
      },
    )
  }

  function onResend() {
    setFormError(null)
    if (!email.includes('@')) return setErrors({ email: 'Enter your email address first' })
    resend.mutate(email.trim(), {
      onSuccess: (result) => {
        cooldown.start(result.resendAfterSeconds)
        setNotice('If an account exists for this email, a new code is on its way. Only the newest code works.')
      },
      onError: (err) => setFormError(errorMessage(err)),
    })
  }

  return (
    <>
      <AuthHeader title="Set a new password" subtitle="Enter the code from the email and choose a new password." />
      {notice && <Alert tone="success" className="mb-4">{notice}</Alert>}
      {formError && <Alert tone="error" className="mb-4">{formError}</Alert>}

      <form onSubmit={onSubmit} noValidate className="space-y-4">
        <TextField label="Email" type="email" autoComplete="email" inputMode="email" value={email} onChange={(e) => setEmail(e.target.value)} error={errors.email} />
        <TextField
          label="Code"
          inputMode="numeric"
          autoComplete="one-time-code"
          maxLength={6}
          placeholder="••••••"
          value={code}
          onChange={(e) => setCode(e.target.value.replace(/\D/g, '').slice(0, 6))}
          error={errors.code}
          className="[&_input]:font-mono [&_input]:tracking-[0.4em]"
          autoFocus
        />
        <div>
          <PasswordField label="New password" autoComplete="new-password" value={newPassword} onChange={(e) => setNewPassword(e.target.value)} error={errors.newPassword} />
          <PasswordRules password={newPassword} />
        </div>
        <PasswordField
          label="Confirm new password"
          autoComplete="new-password"
          value={confirmPassword}
          onChange={(e) => setConfirmPassword(e.target.value)}
          error={errors.confirmPassword}
        />
        <Button type="submit" fullWidth loading={reset.isPending}>
          {reset.isPending ? 'Saving…' : 'Change password'}
        </Button>
      </form>

      <div className="mt-5 flex justify-center">
        <Button variant="ghost" onClick={onResend} loading={resend.isPending} disabled={cooldown.secondsLeft > 0}>
          {cooldown.secondsLeft > 0 ? `Resend code in ${cooldown.secondsLeft}s` : 'Resend code'}
        </Button>
      </div>
      <p className="mt-3 text-center text-sm text-slate-600">
        <Link to="/login" className="font-semibold text-indigo-600 hover:text-indigo-500">
          Back to login
        </Link>
      </p>
    </>
  )
}
