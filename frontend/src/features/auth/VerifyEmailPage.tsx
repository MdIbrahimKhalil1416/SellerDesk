import { useState, type FormEvent } from 'react'
import { Link, useLocation, useNavigate, useSearchParams } from 'react-router'
import { Alert } from '../../components/ui/Alert'
import { Button } from '../../components/ui/Button'
import { TextField } from '../../components/ui/TextField'
import { errorMessage, getApiError } from '../../lib/apiError'
import { useCountdown } from '../../lib/useCountdown'
import { AuthHeader } from './AuthHeader'
import { useResendVerificationCode, useVerifyEmail } from './hooks'
import { SIX_DIGITS } from './passwordPolicy'

interface LocationState {
  resendAfterSeconds?: number
  justRegistered?: boolean
}

export function VerifyEmailPage() {
  const [params] = useSearchParams()
  const location = useLocation()
  const navigate = useNavigate()
  const state = (location.state as LocationState | null) ?? {}

  const [email, setEmail] = useState(params.get('email') ?? '')
  const [code, setCode] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(
    state.justRegistered ? `We sent a 6-digit code to ${params.get('email') ?? 'your email'}.` : null,
  )

  const verify = useVerifyEmail()
  const resend = useResendVerificationCode()
  const cooldown = useCountdown(state.resendAfterSeconds ?? 0)

  function onSubmit(event: FormEvent) {
    event.preventDefault()
    setError(null)
    if (!email.includes('@')) return setError('Enter the email address you registered with.')
    if (!SIX_DIGITS.test(code)) return setError('Enter the 6-digit code from the email.')

    verify.mutate(
      { email: email.trim(), code },
      {
        onSuccess: () => navigate('/dashboard', { replace: true }),
        onError: (err) => {
          // The backend deliberately uses one code for wrong, expired, used-up or unknown.
          if (getApiError(err)?.code === 'INVALID_OTP') {
            setError(
              'That code is wrong or has expired. Codes are valid for 10 minutes and allow 5 tries. Check the newest email or request a new code.',
            )
          } else {
            setError(errorMessage(err))
          }
          setCode('')
        },
      },
    )
  }

  function onResend() {
    setError(null)
    if (!email.includes('@')) return setError('Enter your email address first.')
    resend.mutate(email.trim(), {
      onSuccess: (result) => {
        cooldown.start(result.resendAfterSeconds)
        setNotice('If this email is waiting for verification, a new code is on its way. Only the newest code works.')
      },
      onError: (err) => setError(errorMessage(err)),
    })
  }

  return (
    <>
      <AuthHeader title="Verify your email" subtitle="Enter the 6-digit code we emailed you to activate your account." />
      {notice && <Alert tone="success" className="mb-4">{notice}</Alert>}
      {error && <Alert tone="error" className="mb-4">{error}</Alert>}

      <form onSubmit={onSubmit} noValidate className="space-y-4">
        <TextField label="Email" type="email" autoComplete="email" inputMode="email" value={email} onChange={(e) => setEmail(e.target.value)} />
        <TextField
          label="Verification code"
          name="code"
          inputMode="numeric"
          autoComplete="one-time-code"
          maxLength={6}
          placeholder="••••••"
          value={code}
          onChange={(e) => setCode(e.target.value.replace(/\D/g, '').slice(0, 6))}
          className="[&_input]:text-center [&_input]:font-mono [&_input]:text-2xl [&_input]:tracking-[0.5em]"
          autoFocus
        />
        <Button type="submit" fullWidth loading={verify.isPending} disabled={code.length !== 6}>
          {verify.isPending ? 'Verifying…' : 'Verify and continue'}
        </Button>
      </form>

      <div className="mt-5 flex flex-col items-center gap-1 text-sm text-slate-600">
        <span>Didn&apos;t get the code? Check your spam folder.</span>
        <Button variant="ghost" onClick={onResend} loading={resend.isPending} disabled={cooldown.secondsLeft > 0}>
          {cooldown.secondsLeft > 0 ? `Resend code in ${cooldown.secondsLeft}s` : 'Resend code'}
        </Button>
      </div>

      <p className="mt-4 text-center text-sm text-slate-600">
        <Link to="/login" className="font-semibold text-indigo-600 hover:text-indigo-500">
          Back to login
        </Link>
      </p>
    </>
  )
}
