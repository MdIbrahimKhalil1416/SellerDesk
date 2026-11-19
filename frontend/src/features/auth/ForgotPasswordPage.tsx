import { useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router'
import { Alert } from '../../components/ui/Alert'
import { Button } from '../../components/ui/Button'
import { TextField } from '../../components/ui/TextField'
import { errorMessage } from '../../lib/apiError'
import { AuthHeader } from './AuthHeader'
import { useForgotPassword } from './hooks'

export function ForgotPasswordPage() {
  const navigate = useNavigate()
  const forgot = useForgotPassword()
  const [email, setEmail] = useState('')
  const [error, setError] = useState<string | null>(null)

  function onSubmit(event: FormEvent) {
    event.preventDefault()
    setError(null)
    if (!email.includes('@')) return setError('Enter a valid email address.')

    forgot.mutate(email.trim(), {
      // Same answer for every email (the backend never reveals which emails exist).
      onSuccess: (result) =>
        navigate(`/reset-password?email=${encodeURIComponent(email.trim())}`, {
          state: { resendAfterSeconds: result.resendAfterSeconds },
        }),
      onError: (err) => setError(errorMessage(err)),
    })
  }

  return (
    <>
      <AuthHeader title="Forgot your password?" subtitle="Enter your email and we'll send you a 6-digit code to set a new one." />
      {error && <Alert tone="error" className="mb-4">{error}</Alert>}

      <form onSubmit={onSubmit} noValidate className="space-y-4">
        <TextField label="Email" type="email" autoComplete="email" inputMode="email" value={email} onChange={(e) => setEmail(e.target.value)} autoFocus />
        <Button type="submit" fullWidth loading={forgot.isPending}>
          {forgot.isPending ? 'Sending…' : 'Send code'}
        </Button>
      </form>

      <p className="mt-6 text-center text-sm text-slate-600">
        Remembered it?{' '}
        <Link to="/login" className="font-semibold text-indigo-600 hover:text-indigo-500">
          Back to login
        </Link>
      </p>
    </>
  )
}
