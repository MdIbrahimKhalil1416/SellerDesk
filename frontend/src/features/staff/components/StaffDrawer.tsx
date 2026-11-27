import { useState } from 'react'
import { Alert } from '../../../components/ui/Alert'
import { Button } from '../../../components/ui/Button'
import { ConfirmDialog } from '../../../components/ui/ConfirmDialog'
import { Drawer } from '../../../components/ui/Drawer'
import { Select } from '../../../components/ui/Select'
import { Spinner } from '../../../components/ui/Spinner'
import { cn } from '../../../lib/cn'
import type { Permission } from '../../../types/auth'
import type { PermissionCatalog, StaffMember, StaffRole } from '../../../types/staff'
import { useAuth } from '../../auth/authContext'
import { usePermissions } from '../../auth/usePermissions'
import { useChangeStaffStatus, usePermissionCatalog, useStaffMember, useUpdateStaff } from '../queries'
import { staffErrorCode, staffErrorMessage } from '../staffErrors'
import { formatDate, formatDateTime, permissionLabel, roleLabel, STAFF_ROLES } from '../staffLabels'
import { StaffStatusBadge } from './StaffStatusBadge'

interface StaffDrawerProps {
  /** userId of the member to show; null = closed. */
  memberId: string | null
  /** Shown as the title while the member loads. */
  fallbackTitle?: string
  onClose: () => void
}

/**
 * Member details. Owner and staff with STAFF_MANAGE can change role, single permissions and
 * status here. The owner's row and your own row are read-only (the backend refuses those changes too).
 */
export function StaffDrawer({ memberId, fallbackTitle, onClose }: StaffDrawerProps) {
  const member = useStaffMember(memberId)
  const catalog = usePermissionCatalog()
  const [confirmOpen, setConfirmOpen] = useState(false)
  // Success message of the last change. Kept here, because the form below starts fresh after a save.
  const [flash, setFlash] = useState<string | null>(null)

  function close() {
    setFlash(null)
    onClose()
  }

  const data = member.data
  return (
    <Drawer open={!!memberId} title={data?.name ?? fallbackTitle ?? 'Staff member'} onClose={close} locked={confirmOpen}>
      {flash && <Alert tone="success" className="mb-4">{flash}</Alert>}
      {member.isPending || catalog.isPending ? (
        <div className="flex items-center justify-center gap-2 py-16 text-sm text-slate-500" role="status">
          <Spinner /> Loading…
        </div>
      ) : member.isError || catalog.isError ? (
        <Alert tone="error">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <span>{staffErrorMessage(member.error ?? catalog.error, 'Could not load this staff member.')}</span>
            {staffErrorCode(member.error) !== 'NOT_FOUND' && (
              <Button variant="secondary" onClick={() => void (member.isError ? member.refetch() : catalog.refetch())}>
                Try again
              </Button>
            )}
          </div>
        </Alert>
      ) : data && catalog.data ? (
        <MemberDetails member={data} catalog={catalog.data} onConfirmOpenChange={setConfirmOpen} onChanged={setFlash} />
      ) : null}
    </Drawer>
  )
}

function MemberDetails({
  member,
  catalog,
  onConfirmOpenChange,
  onChanged,
}: {
  member: StaffMember
  catalog: PermissionCatalog
  onConfirmOpenChange: (open: boolean) => void
  /** Called with a success message (or null when a new change starts). */
  onChanged: (message: string | null) => void
}) {
  const { user } = useAuth()
  const { can } = usePermissions()

  const isOwner = member.role === 'SHOP_OWNER'
  const isSelf = member.id === user?.id
  const readOnly = isOwner || isSelf

  return (
    <div className="space-y-6">
      <section>
        <div className="flex items-start justify-between gap-3">
          <div className="min-w-0">
            <p className="truncate text-sm text-slate-600">{member.email}</p>
            {member.phone && <p className="text-sm text-slate-600">{member.phone}</p>}
          </div>
          <StaffStatusBadge status={member.status === 'DISABLED' ? 'DISABLED' : 'ACTIVE'} />
        </div>
        <dl className="mt-4 grid grid-cols-2 gap-x-4 gap-y-3 text-sm">
          <Info label="Role" value={roleLabel(member.role)} />
          <Info label="Last login" value={member.lastLoginAt ? formatDateTime(member.lastLoginAt) : 'Never'} />
          <Info label="Joined" value={formatDate(member.createdAt)} />
          <Info label="Invited by" value={member.invitedBy ? (member.invitedBy.name ?? 'Former member') : '—'} />
        </dl>
      </section>

      {readOnly ? (
        <>
          <Alert tone="info">
            {isOwner
              ? 'The shop owner always has every permission. This row cannot be changed.'
              : 'This is you. Your own role, permissions and status can only be changed by the shop owner.'}
          </Alert>
          <PermissionList catalog={catalog} granted={member.permissions} />
        </>
      ) : (
        <>
          {/* key: after a save, the server's answer becomes the new starting point of the form. */}
          <EditAccess key={`${member.role}|${member.permissions.join(',')}`} member={member} catalog={catalog} can={can} onChanged={onChanged} />
          <StatusSection member={member} onConfirmOpenChange={onConfirmOpenChange} onChanged={onChanged} />
        </>
      )}
    </div>
  )
}

function Info({ label, value }: { label: string; value: string }) {
  return (
    <div className="min-w-0">
      <dt className="text-xs text-slate-500">{label}</dt>
      <dd className="truncate font-medium text-slate-900">{value}</dd>
    </div>
  )
}

/** Read-only list (owner / yourself). */
function PermissionList({ catalog, granted }: { catalog: PermissionCatalog; granted: Permission[] }) {
  return (
    <section>
      <h3 className="text-sm font-semibold text-slate-900">Permissions</h3>
      <ul className="mt-2 space-y-2">
        {catalog.permissions
          .filter((p) => granted.includes(p.code))
          .map((p) => (
            <li key={p.code} className="text-sm">
              <span className="font-medium text-slate-800">{permissionLabel(p.code)}</span>
              <span className="block text-xs text-slate-500">{p.description}</span>
            </li>
          ))}
      </ul>
    </section>
  )
}

/** Role + permission checkboxes, saved together with one PATCH. */
function EditAccess({
  member,
  catalog,
  can,
  onChanged,
}: {
  member: StaffMember
  catalog: PermissionCatalog
  can: (p: Permission) => boolean
  onChanged: (message: string | null) => void
}) {
  const [role, setRole] = useState<StaffRole>(member.role as StaffRole)
  const [selected, setSelected] = useState<Set<Permission>>(() => new Set(member.permissions))
  const [notice, setNotice] = useState<{ tone: 'success' | 'error' | 'info'; text: string } | null>(null)
  const update = useUpdateStaff(member.id)

  const roleDefaults = catalog.roleDefaults[role] ?? []
  /**
   * "Grant" = in the new set but not in the member's current set. A manager may only grant what
   * they have; keeping or removing a permission they lack is fine (docs/modules/STAFF.md §4).
   */
  const canToggle = (p: Permission) => can(p) || member.permissions.includes(p)

  const roleChanged = role !== member.role
  const permissionsChanged =
    selected.size !== member.permissions.length || member.permissions.some((p) => !selected.has(p))
  const dirty = roleChanged || permissionsChanged

  function changeRole(next: StaffRole) {
    setRole(next)
    const defaults = catalog.roleDefaults[next] ?? []
    const allowed = defaults.filter(canToggle)
    setSelected(new Set(allowed))
    const skipped = defaults.filter((p) => !canToggle(p))
    setNotice(
      skipped.length > 0
        ? { tone: 'info', text: `Permissions reset to the ${roleLabel(next)} defaults, except ${skipped.map(permissionLabel).join(', ')} (you do not have it yourself).` }
        : { tone: 'info', text: `Permissions reset to the ${roleLabel(next)} defaults. Adjust them below if needed.` },
    )
  }

  function toggle(p: Permission) {
    setSelected((current) => {
      const next = new Set(current)
      if (next.has(p)) next.delete(p)
      else next.add(p)
      return next
    })
  }

  function reset() {
    setRole(member.role as StaffRole)
    setSelected(new Set(member.permissions))
    setNotice(null)
  }

  async function save() {
    setNotice(null)
    onChanged(null)
    try {
      // mutateAsync, not mutate(..., {onSuccess}): after a save this component is re-created with
      // the new data (see `key`), and TanStack skips per-call callbacks of an unmounted component.
      await update.mutateAsync({ ...(roleChanged ? { role } : {}), permissions: [...selected] })
      onChanged(`Saved. ${member.name} will log in again with the new rights.`)
    } catch (err) {
      setNotice({ tone: 'error', text: staffErrorMessage(err, 'Could not save the changes.') })
    }
  }

  return (
    <section className="space-y-4">
      <div>
        <Select
          label="Role"
          value={role}
          onChange={(e) => changeRole(e.target.value as StaffRole)}
          options={STAFF_ROLES.map((r) => ({ value: r.value, label: r.label }))}
          disabled={update.isPending}
        />
        <p className="mt-1.5 text-xs text-slate-500">{STAFF_ROLES.find((r) => r.value === role)?.hint}</p>
      </div>

      <fieldset>
        <legend className="text-sm font-semibold text-slate-900">Permissions</legend>
        <p className="text-xs text-slate-500">Role defaults are marked. Tick extra permissions or untick ones they should not have.</p>
        <ul className="mt-3 divide-y divide-slate-100 rounded-lg border border-slate-200">
          {catalog.permissions.map((p) => {
            const checked = selected.has(p.code)
            const disabled = update.isPending || !canToggle(p.code)
            const isDefault = roleDefaults.includes(p.code)
            const id = `perm-${p.code}`
            return (
              <li key={p.code}>
                <label htmlFor={id} className={cn('flex gap-3 px-3 py-2.5', disabled ? 'cursor-not-allowed opacity-60' : 'cursor-pointer hover:bg-slate-50')}>
                  <input
                    id={id}
                    type="checkbox"
                    className="mt-0.5 h-4 w-4 shrink-0 rounded border-slate-300 accent-indigo-600"
                    checked={checked}
                    disabled={disabled}
                    onChange={() => toggle(p.code)}
                  />
                  <span className="min-w-0 flex-1">
                    <span className="flex flex-wrap items-center gap-2 text-sm font-medium text-slate-800">
                      {permissionLabel(p.code)}
                      {isDefault && <span className="rounded bg-slate-100 px-1.5 py-0.5 text-[11px] font-medium text-slate-500">Role default</span>}
                      {checked && !isDefault && <span className="rounded bg-indigo-50 px-1.5 py-0.5 text-[11px] font-medium text-indigo-700">Extra</span>}
                    </span>
                    <span className="block text-xs text-slate-500">{p.description}</span>
                    {!canToggle(p.code) && <span className="block text-xs text-amber-700">You cannot give this permission because you do not have it.</span>}
                  </span>
                </label>
              </li>
            )
          })}
        </ul>
      </fieldset>

      {notice && <Alert tone={notice.tone}>{notice.text}</Alert>}

      <div className="flex flex-col gap-2 rounded-lg bg-slate-50 p-3 sm:flex-row sm:items-center sm:justify-between">
        <p className="text-xs text-slate-600">Saving logs this person out on all devices. They log in again with the new rights.</p>
        <div className="flex shrink-0 gap-2">
          <Button variant="secondary" onClick={reset} disabled={!dirty || update.isPending}>
            Undo
          </Button>
          <Button onClick={() => void save()} disabled={!dirty} loading={update.isPending}>
            Save changes
          </Button>
        </div>
      </div>
    </section>
  )
}

/** Deactivate / reactivate with a confirmation step. */
function StatusSection({
  member,
  onConfirmOpenChange,
  onChanged,
}: {
  member: StaffMember
  onConfirmOpenChange: (open: boolean) => void
  onChanged: (message: string | null) => void
}) {
  const [open, setOpen] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const changeStatus = useChangeStaffStatus(member.id)
  const deactivating = member.status !== 'DISABLED'

  function setDialog(next: boolean) {
    setOpen(next)
    onConfirmOpenChange(next)
  }

  async function confirm() {
    setError(null)
    onChanged(null)
    try {
      await changeStatus.mutateAsync(deactivating ? 'DISABLED' : 'ACTIVE')
      onChanged(deactivating ? `${member.name} was deactivated and logged out everywhere.` : `${member.name} can log in again.`)
    } catch (err) {
      setError(staffErrorMessage(err, 'Could not change the status.'))
    } finally {
      setDialog(false)
    }
  }

  return (
    <section className="border-t border-slate-200 pt-5">
      <h3 className="text-sm font-semibold text-slate-900">{deactivating ? 'Deactivate account' : 'Reactivate account'}</h3>
      <p className="mt-1 text-xs text-slate-500">
        {deactivating
          ? 'They are logged out right away and cannot log in until you reactivate them. Their past work stays.'
          : 'They can log in again with their old password. Role and permissions stay as they are.'}
      </p>
      {error && <Alert tone="error" className="mt-3">{error}</Alert>}
      <Button
        variant="secondary"
        className={cn('mt-3', deactivating && 'border-red-300 text-red-700 hover:bg-red-50')}
        onClick={() => setDialog(true)}
      >
        {deactivating ? 'Deactivate' : 'Reactivate'}
      </Button>

      <ConfirmDialog
        open={open}
        title={deactivating ? `Deactivate ${member.name}?` : `Reactivate ${member.name}?`}
        confirmLabel={deactivating ? 'Deactivate' : 'Reactivate'}
        danger={deactivating}
        loading={changeStatus.isPending}
        onConfirm={() => void confirm()}
        onCancel={() => setDialog(false)}
      >
        {deactivating
          ? `${member.name} is logged out on all devices and can no longer log in. You can reactivate the account later.`
          : `${member.name} can log in again with the same role and permissions.`}
      </ConfirmDialog>
    </section>
  )
}
