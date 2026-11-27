import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useState, type FormEvent } from 'react'
import { Link, useLocation, useNavigate } from 'react-router'
import { staffApi } from '../../api/staff'
import { Alert } from '../../components/ui/Alert'
import { Button } from '../../components/ui/Button'
import { PasswordField } from '../../components/ui/PasswordField'
import { Spinner } from '../../components/ui/Spinner'
import { TextField } from '../../components/ui/TextField'
import { errorMessage, fieldErrorsOf, getApiError } from '../../lib/apiError'
import type { InvitationPreview } from '../../types/staff'
import { AuthHeader } from '../auth/AuthHeader'
import { useAuth } from '../auth/authContext'
import { isPasswordValid } from '../auth/passwordPolicy'
import { PasswordRules } from '../auth/PasswordRules'
import { formatDate, roleLabel } from './staffLabels'

type Errors = Partial<Record<'name' | 'password' | 'confirmPassword', string>>

/** Reads `token` from the address bar fragment: /accept-invite#token=abc */
function readTokenFromHash(): string {
  return new URLSearchParams(window.location.hash.slice(1)).get('token')?.trim() ?? ''
}

/** Texts for "this link cannot be used", per backend code. */
function deadLinkText(code: string | undefined): { title: string; text: string } | null {
  if (code === 'INVITATION_EXPIRED') {
    return { title: 'This invitation has expired', text: 'Invitation links are valid for 7 days. Ask the person who invited you to send it again.' }
  }
  if (code === 'INVALID_INVITATION') {
    return {
      title: 'This invitation link does not work',
      text: 'It may have been cancelled, already used, or replaced by a newer email. Use the link from the newest invitation email, or ask the shop to invite you again.',
    }
  }
  return null
}

/**
 * /accept-invite (public). The invite email links to /accept-invite#token=...
 *
 * Why the token is after `#`: browsers never send the fragment to a server, so it does not end up
 * in access logs or Referer headers. We read it once, keep it in memory, and remove it from the
 * address bar (so it is not in the history or a screenshot of the URL).
 */
export function AcceptInvitePage() {
  const navigate = useNavigate()
  const location = useLocation()
  const { user } = useAuth()

  // useState initializer: read once on the first render, before the effect below clears the hash.
  const [token] = useState(readTokenFromHash)

  useEffect(() => {
    if (location.hash) navigate({ pathname: location.pathname, search: location.search }, { replace: true })
  }, [location.hash, location.pathname, location.search, navigate])

  const preview = useQuery({
    queryKey: ['invitation-preview', token],
    queryFn: () => staffApi.previewInvitation(token),
    enabled: !!token,
    retry: false, // 400 = bad link; retrying will not help
    staleTime: Infinity,
    refetchOnWindowFocus: false,
  })

  if (!token) {
    return (
      <DeadLink
        title="Invitation link is incomplete"
        text="Open the link from the invitation email again. If you copied it by hand, make sure you copied all of it."
      />
    )
  }

  if (preview.isPending) {
    return (
      <div className="flex items-center justify-center gap-2 py-10 text-sm text-slate-500" role="status">
        <Spinner /> Checking your invitation…
      </div>
    )
  }

  if (preview.isError) {
    const dead = deadLinkText(getApiError(preview.error)?.code)
    if (dead) return <DeadLink {...dead} />
    return (
      <>
        <AuthHeader title="Something went wrong" />
        <Alert tone="error" className="mb-4">
          {errorMessage(preview.error, 'Could not check the invitation.')}
        </Alert>
        <Button fullWidth onClick={() => void preview.refetch()}>
          Try again
        </Button>
      </>
    )
  }

  return <AcceptForm token={token} invitation={preview.data} loggedInAs={user?.email ?? null} />
}

function AcceptForm({ token, invitation, loggedInAs }: { token: string; invitation: InvitationPreview; loggedInAs: string | null }) {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const { startSession } = useAuth()

  const [name, setName] = useState('')
  const [password, setPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const [errors, setErrors] = useState<Errors>({})
  const [formError, setFormError] = useState<string | null>(null)
  const [deadCode, setDeadCode] = useState<string | undefined>(undefined)

  const accept = useMutation({
    mutationFn: async () => {
      const result = await staffApi.acceptInvitation({ token, name: name.trim(), password })
      // If someone else was logged in on this device, drop their cached data so the new user never
      // sees it. The preview query stays: removing it would refetch it (now "already used") and
      // swap this form for an error before we navigate away.
      queryClient.removeQueries({ predicate: (query) => query.queryKey[0] !== 'invitation-preview' })
      // The backend already logged the user in (refresh cookie set); keep the access token in
      // memory and load user + shop, exactly like after email verification.
      await startSession(result.accessToken)
    },
  })

  function onSubmit(event: FormEvent) {
    event.preventDefault()
    setFormError(null)
    const e: Errors = {}
    if (!name.trim()) e.name = 'Enter your name'
    else if (name.trim().length > 100) e.name = 'At most 100 characters'
    if (!isPasswordValid(password)) e.password = 'Password does not meet the rules below'
    else if (password.toLowerCase() === invitation.email.toLowerCase()) e.password = 'Password must not be your email address'
    // Confirm-password is checked only here; the API takes one password (docs/modules/STAFF.md §3.5).
    if (confirmPassword !== password) e.confirmPassword = 'Passwords do not match'
    setErrors(e)
    if (Object.keys(e).length > 0) return

    accept.mutate(undefined, {
      onSuccess: () => navigate('/dashboard', { replace: true }),
      onError: (err) => {
        const code = getApiError(err)?.code
        if (code === 'INVALID_INVITATION' || code === 'INVITATION_EXPIRED') {
          setDeadCode(code)
        } else if (code === 'VALIDATION_FAILED') {
          const fields = fieldErrorsOf(err)
          setErrors({ name: fields.name, password: fields.password })
          if (!fields.name && !fields.password) setFormError(errorMessage(err))
        } else if (code === 'EMAIL_ALREADY_REGISTERED') {
          setFormError(
            `${invitation.email} already has a SellerDesk account (for example from another shop's invitation). Log in with it instead, or ask the shop to invite a different email.`,
          )
        } else {
          setFormError(errorMessage(err, 'Could not accept the invitation.'))
        }
      },
    })
  }

  const dead = deadLinkText(deadCode)
  if (dead) return <DeadLink {...dead} />

  return (
    <>
      <AuthHeader
        title={`Join ${invitation.shopName}`}
        subtitle={
          <>
            {invitation.inviterName} invited you as <span className="font-medium text-slate-900">{roleLabel(invitation.role)}</span>. Choose your name and a
            password to finish. This invitation expires on {formatDate(invitation.expiresAt)}.
          </>
        }
      />

      {loggedInAs && (
        <Alert tone="info" className="mb-4">
          You are logged in as {loggedInAs} on this device. Joining logs you in as {invitation.email} instead.
        </Alert>
      )}
      {formError && (
        <Alert tone="error" className="mb-4">
          {formError}
        </Alert>
      )}

      <form onSubmit={onSubmit} noValidate className="space-y-4">
        {/* Read-only, but lets password managers save the new login under the right email. */}
        <TextField label="Email" type="email" autoComplete="username" value={invitation.email} readOnly className="[&_input]:bg-slate-50 [&_input]:text-slate-600" />
        <TextField label="Your name" autoComplete="name" maxLength={100} value={name} onChange={(e) => setName(e.target.value)} error={errors.name} autoFocus />
        <div>
          <PasswordField label="Password" autoComplete="new-password" value={password} onChange={(e) => setPassword(e.target.value)} error={errors.password} />
          <PasswordRules password={password} />
        </div>
        <PasswordField
          label="Confirm password"
          autoComplete="new-password"
          value={confirmPassword}
          onChange={(e) => setConfirmPassword(e.target.value)}
          error={errors.confirmPassword}
        />
        <Button type="submit" fullWidth loading={accept.isPending}>
          {accept.isPending ? 'Joining…' : 'Join shop'}
        </Button>
      </form>
    </>
  )
}

function DeadLink({ title, text }: { title: string; text: string }) {
  return (
    <>
      <AuthHeader title={title} />
      <Alert tone="error" className="mb-5">
        {text}
      </Alert>
      <p className="text-center text-sm text-slate-600">
        Already have an account?{' '}
        <Link to="/login" className="font-semibold text-indigo-600 hover:text-indigo-500">
          Log in
        </Link>
      </p>
    </>
  )
}
