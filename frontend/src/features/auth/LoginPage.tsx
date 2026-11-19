import { useState, type FormEvent, type ReactNode } from 'react'
import { Link, useLocation } from 'react-router'
import { Alert } from '../../components/ui/Alert'
import { Button } from '../../components/ui/Button'
import { PasswordField } from '../../components/ui/PasswordField'
import { TextField } from '../../components/ui/TextField'
import { errorMessage, getApiError } from '../../lib/apiError'
import { AuthHeader } from './AuthHeader'
import { useLogin } from './hooks'

export function LoginPage() {
  const location = useLocation()
  const notice = (location.state as { notice?: string } | null)?.notice
  const login = useLogin()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<ReactNode>(null)

  function onSubmit(event: FormEvent) {
    event.preventDefault()
    setError(null)
    if (!email.includes('@') || !password) return setError('Enter your email and password.')

    // On success AuthProvider sets the user, and PublicOnlyRoute sends us to the dashboard.
    login.mutate(
      { email: email.trim(), password },
      {
        onError: (err) => {
          const code = getApiError(err)?.code
          if (code === 'INVALID_CREDENTIALS') {
            setError('Email or password is incorrect.') // same text for unknown email: no account probing
          } else if (code === 'EMAIL_NOT_VERIFIED') {
            setError(
              <>
                Please verify your email first.{' '}
                <Link to={`/verify-email?email=${encodeURIComponent(email.trim())}`} className="font-semibold underline">
                  Enter your code
                </Link>
              </>,
            )
          } else {
            setError(errorMessage(err)) // e.g. LOGIN_RATE_LIMITED, network errors
          }
        },
      },
    )
  }

  return (
    <>
      <AuthHeader title="Log in" subtitle="Welcome back. Log in to manage your shop." />
      {notice && !error && <Alert tone="success" className="mb-4">{notice}</Alert>}
      {error && <Alert tone="error" className="mb-4">{error}</Alert>}

      <form onSubmit={onSubmit} noValidate className="space-y-4">
        <TextField label="Email" type="email" name="email" autoComplete="email" inputMode="email" value={email} onChange={(e) => setEmail(e.target.value)} autoFocus />
        <div>
          <PasswordField label="Password" name="password" autoComplete="current-password" value={password} onChange={(e) => setPassword(e.target.value)} />
          <div className="mt-2 text-right">
            <Link to="/forgot-password" className="text-sm font-medium text-indigo-600 hover:text-indigo-500">
              Forgot password?
            </Link>
          </div>
        </div>
        <Button type="submit" fullWidth loading={login.isPending}>
          {login.isPending ? 'Logging in…' : 'Log in'}
        </Button>
      </form>

      <p className="mt-6 text-center text-sm text-slate-600">
        New to SellerDesk?{' '}
        <Link to="/register" className="font-semibold text-indigo-600 hover:text-indigo-500">
          Create your shop
        </Link>
      </p>
    </>
  )
}
