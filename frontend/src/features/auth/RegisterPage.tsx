import { useState, type ChangeEvent, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router'
import { Alert } from '../../components/ui/Alert'
import { Button } from '../../components/ui/Button'
import { PasswordField } from '../../components/ui/PasswordField'
import { TextField } from '../../components/ui/TextField'
import { errorMessage, fieldErrorsOf, getApiError, httpStatusOf } from '../../lib/apiError'
import { AuthHeader } from './AuthHeader'
import { BD_MOBILE, isPasswordValid } from './passwordPolicy'
import { PasswordRules } from './PasswordRules'
import { useRegisterShop } from './hooks'

type Field = 'shopName' | 'shopPhone' | 'ownerName' | 'email' | 'password' | 'confirmPassword'
type Errors = Partial<Record<Field, string>>

export function RegisterPage() {
  const navigate = useNavigate()
  const register = useRegisterShop()
  const [values, setValues] = useState<Record<Field, string>>({
    shopName: '',
    shopPhone: '',
    ownerName: '',
    email: '',
    password: '',
    confirmPassword: '',
  })
  const [errors, setErrors] = useState<Errors>({})
  const [formError, setFormError] = useState<string | null>(null)

  const set = (field: Field) => (e: ChangeEvent<HTMLInputElement>) => {
    setValues((v) => ({ ...v, [field]: e.target.value }))
    setErrors((errs) => ({ ...errs, [field]: undefined }))
  }

  /** Quick checks for instant feedback. The backend validates again and has the last word. */
  function validate(): Errors {
    const e: Errors = {}
    if (values.shopName.trim().length < 2) e.shopName = 'Enter your shop name'
    if (!BD_MOBILE.test(values.shopPhone.replace(/[\s-]/g, ''))) e.shopPhone = 'Enter a mobile number like 01712345678'
    if (values.ownerName.trim().length < 2) e.ownerName = 'Enter your name'
    if (!values.email.includes('@')) e.email = 'Enter a valid email address'
    if (!isPasswordValid(values.password)) e.password = 'Password does not meet the rules below'
    if (values.confirmPassword !== values.password) e.confirmPassword = 'Passwords do not match'
    return e
  }

  function onSubmit(event: FormEvent) {
    event.preventDefault()
    setFormError(null)
    const clientErrors = validate()
    setErrors(clientErrors)
    if (Object.keys(clientErrors).length > 0) return

    register.mutate(
      {
        shopName: values.shopName.trim(),
        shopPhone: values.shopPhone.replace(/[\s-]/g, ''),
        ownerName: values.ownerName.trim(),
        email: values.email.trim(),
        password: values.password,
      },
      {
        onSuccess: (result) =>
          navigate(`/verify-email?email=${encodeURIComponent(result.email)}`, {
            state: { resendAfterSeconds: result.resendAfterSeconds, justRegistered: true },
          }),
        onError: (error) => {
          const apiError = getApiError(error)
          const status = httpStatusOf(error)
          // The only 409 this endpoint returns is EMAIL_ALREADY_REGISTERED (docs/API.md §1), so
          // the status alone is enough, even if the body is missing or not an ApiError.
          if (apiError?.code === 'EMAIL_ALREADY_REGISTERED' || status === 409) {
            setErrors({ email: 'An account with this email already exists. Try logging in.' })
          } else if (apiError?.code === 'VALIDATION_FAILED') {
            setErrors(fieldErrorsOf(error)) // backend field names = our field names
          } else {
            // 429, network errors, timeouts and 5xx get their own message (lib/apiError.ts).
            setFormError(errorMessage(error))
          }
        },
      },
    )
  }

  return (
    <>
      <AuthHeader title="Create your shop" subtitle="Start managing orders in a few minutes." />
      {formError && <Alert tone="error" className="mb-4">{formError}</Alert>}

      <form onSubmit={onSubmit} noValidate className="space-y-4">
        <div className="grid gap-4 sm:grid-cols-2">
          <TextField label="Shop name" name="shopName" autoComplete="organization" value={values.shopName} onChange={set('shopName')} error={errors.shopName} />
          <TextField
            label="Shop phone"
            name="shopPhone"
            type="tel"
            inputMode="tel"
            autoComplete="tel"
            placeholder="01712345678"
            value={values.shopPhone}
            onChange={set('shopPhone')}
            error={errors.shopPhone}
          />
        </div>
        <TextField label="Your name" name="ownerName" autoComplete="name" value={values.ownerName} onChange={set('ownerName')} error={errors.ownerName} />
        <TextField label="Email" name="email" type="email" autoComplete="email" inputMode="email" value={values.email} onChange={set('email')} error={errors.email} />
        <div>
          <PasswordField label="Password" name="password" autoComplete="new-password" value={values.password} onChange={set('password')} error={errors.password} />
          <PasswordRules password={values.password} />
        </div>
        <PasswordField
          label="Confirm password"
          name="confirmPassword"
          autoComplete="new-password"
          value={values.confirmPassword}
          onChange={set('confirmPassword')}
          error={errors.confirmPassword}
        />

        <Button type="submit" fullWidth loading={register.isPending}>
          {register.isPending ? 'Creating shop…' : 'Create shop'}
        </Button>
      </form>

      <p className="mt-6 text-center text-sm text-slate-600">
        Already have an account?{' '}
        <Link to="/login" className="font-semibold text-indigo-600 hover:text-indigo-500">
          Log in
        </Link>
      </p>
    </>
  )
}
