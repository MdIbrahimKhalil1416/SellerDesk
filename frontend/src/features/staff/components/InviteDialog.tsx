import { useState, type FormEvent } from 'react'
import { Alert } from '../../../components/ui/Alert'
import { Button } from '../../../components/ui/Button'
import { Modal } from '../../../components/ui/Modal'
import { Select } from '../../../components/ui/Select'
import { TextField } from '../../../components/ui/TextField'
import { fieldErrorsOf } from '../../../lib/apiError'
import type { StaffRole } from '../../../types/staff'
import { usePermissions } from '../../auth/usePermissions'
import { useInviteStaff, usePermissionCatalog } from '../queries'
import { staffErrorCode, staffErrorMessage, STAFF_ERROR_MESSAGES } from '../staffErrors'
import { permissionLabel, STAFF_ROLES } from '../staffLabels'

type Errors = Partial<Record<'email' | 'role', string>>

/** Codes that are about the email address: shown under the email field, not as a banner. */
const EMAIL_CODES = ['ALREADY_A_MEMBER', 'ALREADY_INVITED', 'EMAIL_ALREADY_REGISTERED']

/**
 * Invite by email + role. The invited person gets the role's default permissions; extra
 * permissions can be changed after they joined (staff drawer).
 */
export function InviteDialog({ open, onClose, onInvited }: { open: boolean; onClose: () => void; onInvited: (email: string) => void }) {
  const [email, setEmail] = useState('')
  const [role, setRole] = useState<StaffRole>('MODERATOR')
  const [errors, setErrors] = useState<Errors>({})
  const [formError, setFormError] = useState<string | null>(null)

  const invite = useInviteStaff()
  const catalog = usePermissionCatalog()
  const { can } = usePermissions()

  const defaults = catalog.data?.roleDefaults[role] ?? []
  // A non-owner manager can only give permissions they have themselves (backend: PERMISSION_NOT_GRANTABLE).
  const notGrantable = defaults.filter((p) => !can(p))

  function close() {
    setEmail('')
    setRole('MODERATOR')
    setErrors({})
    setFormError(null)
    invite.reset()
    onClose()
  }

  function onSubmit(event: FormEvent) {
    event.preventDefault()
    setFormError(null)
    const trimmed = email.trim()
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(trimmed)) {
      setErrors({ email: 'Enter a valid email address' })
      return
    }
    setErrors({})

    invite.mutate(
      { email: trimmed, role },
      {
        onSuccess: (invitation) => {
          onInvited(invitation.email)
          close()
        },
        onError: (err) => {
          const code = staffErrorCode(err)
          if (code && EMAIL_CODES.includes(code)) {
            setErrors({ email: STAFF_ERROR_MESSAGES[code] })
          } else if (code === 'VALIDATION_FAILED') {
            const fields = fieldErrorsOf(err)
            setErrors({ email: fields.email, role: fields.role })
            if (!fields.email && !fields.role) setFormError(staffErrorMessage(err))
          } else {
            setFormError(staffErrorMessage(err, 'Could not send the invitation.'))
          }
        },
      },
    )
  }

  return (
    <Modal open={open} title="Invite staff" onClose={close} busy={invite.isPending}>
      <form onSubmit={onSubmit} noValidate className="space-y-4">
        {formError && <Alert tone="error">{formError}</Alert>}
        <TextField
          label="Email"
          type="email"
          inputMode="email"
          autoComplete="off"
          placeholder="name@example.com"
          value={email}
          onChange={(e) => setEmail(e.target.value)}
          error={errors.email}
          autoFocus
        />
        <div>
          <Select
            label="Role"
            value={role}
            onChange={(e) => setRole(e.target.value as StaffRole)}
            options={STAFF_ROLES.map((r) => ({ value: r.value, label: r.label }))}
          />
          {errors.role ? (
            <p className="mt-1.5 text-sm text-red-600">{errors.role}</p>
          ) : (
            <p className="mt-1.5 text-xs text-slate-500">{STAFF_ROLES.find((r) => r.value === role)?.hint}</p>
          )}
        </div>

        {defaults.length > 0 && (
          <div className="rounded-lg bg-slate-50 p-3 text-xs text-slate-600">
            <p className="font-medium text-slate-700">Permissions with this role</p>
            <p className="mt-1">{defaults.map(permissionLabel).join(', ')}</p>
            <p className="mt-2 text-slate-500">You can add or remove single permissions after they join.</p>
          </div>
        )}

        {notGrantable.length > 0 && (
          <Alert tone="error">
            This role includes permissions you do not have ({notGrantable.map(permissionLabel).join(', ')}). Only the shop owner can invite with this role.
          </Alert>
        )}

        <p className="text-xs text-slate-500">We email them a link that is valid for 7 days. They choose their own name and password.</p>

        <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
          <Button variant="secondary" onClick={close} disabled={invite.isPending}>
            Cancel
          </Button>
          <Button type="submit" loading={invite.isPending} disabled={notGrantable.length > 0}>
            {invite.isPending ? 'Sending…' : 'Send invitation'}
          </Button>
        </div>
      </form>
    </Modal>
  )
}
