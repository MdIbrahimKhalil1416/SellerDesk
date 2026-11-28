import { useState, type FormEvent } from 'react'
import { Link, useParams } from 'react-router'
import { ArrowLeftIcon, PlusIcon } from '../../components/icons'
import { Alert } from '../../components/ui/Alert'
import { Button } from '../../components/ui/Button'
import { ConfirmDialog } from '../../components/ui/ConfirmDialog'
import { Spinner } from '../../components/ui/Spinner'
import { fieldErrorsOf, getApiError } from '../../lib/apiError'
import { formatTaka } from '../../lib/money'
import { useUnsavedChangesGuard } from '../../lib/useUnsavedChangesGuard'
import type { Customer, CustomerAddress } from '../../types/customer'
import { usePermissions } from '../auth/usePermissions'
import { AddressDialog } from './components/AddressDialog'
import { CustomerStatusBadge, TagChips } from './components/CustomerStatusBadge'
import { ProfileFields } from './components/ProfileFields'
import {
  addressFormOf,
  addressRequest,
  MAX_ADDRESSES,
  profileFieldErrors,
  profileRequest,
  validateProfile,
  ZONE_LABELS,
  type ProfileErrors,
  type ProfileForm,
} from './customerForm'
import { customerErrorMessage } from './customerErrors'
import { useChangeCustomerStatus, useCustomer, useRemoveAddress, useSaveAddress, useUpdateCustomer } from './queries'

/** /customers/:id. Read-only without CUSTOMER_EDIT. */
export function CustomerDetailPage() {
  const { id = '' } = useParams()
  const customer = useCustomer(id)

  if (customer.isPending) {
    return (
      <div className="flex items-center gap-3 py-12 text-slate-600" role="status">
        <Spinner /> Loading customer…
      </div>
    )
  }
  if (customer.isError) {
    const notFound = getApiError(customer.error)?.code === 'NOT_FOUND'
    return (
      <div className="mx-auto max-w-md rounded-xl border border-slate-200 bg-white p-6 text-center">
        <h1 className="text-lg font-semibold text-slate-900">{notFound ? 'Customer not found' : 'Could not load the customer'}</h1>
        <p className="mt-2 text-sm text-slate-600">{notFound ? 'The link may be wrong.' : customerErrorMessage(customer.error)}</p>
        <div className="mt-4 flex justify-center gap-2">
          <Link to="/customers" className="text-sm font-semibold text-indigo-600 hover:text-indigo-500">
            Back to customers
          </Link>
          {!notFound && (
            <Button variant="secondary" onClick={() => void customer.refetch()}>
              Try again
            </Button>
          )}
        </div>
      </div>
    )
  }
  return <CustomerDetail key={customer.data.id} customer={customer.data} />
}

function CustomerDetail({ customer }: { customer: Customer }) {
  const { can } = usePermissions()
  const canEdit = can('CUSTOMER_EDIT')
  const [editing, setEditing] = useState(false)
  const [message, setMessage] = useState<{ tone: 'success' | 'error'; text: string } | null>(null)

  return (
    <div className="mx-auto max-w-4xl space-y-6">
      <Link to="/customers" className="inline-flex items-center gap-1 text-sm font-medium text-slate-600 hover:text-slate-900">
        <ArrowLeftIcon /> Customers
      </Link>

      {message && <Alert tone={message.tone}>{message.text}</Alert>}
      {customer.status === 'ARCHIVED' && (
        <Alert tone="info">This customer is archived: hidden from the customer list. A new order with this phone restores it automatically.</Alert>
      )}

      {editing ? (
        <ProfileEditForm
          customer={customer}
          onDone={(text) => {
            setEditing(false)
            if (text) setMessage({ tone: 'success', text })
          }}
        />
      ) : (
        <ProfileView customer={customer} canEdit={canEdit} onEdit={() => {
          setMessage(null)
          setEditing(true)
        }} onMessage={setMessage} />
      )}

      <StatsTiles customer={customer} />
      <AddressesSection customer={customer} canEdit={canEdit} />
    </div>
  )
}

// ==== Profile ===============================================================================

function ProfileView({ customer, canEdit, onEdit, onMessage }: {
  customer: Customer
  canEdit: boolean
  onEdit: () => void
  onMessage: (m: { tone: 'success' | 'error'; text: string } | null) => void
}) {
  const changeStatus = useChangeCustomerStatus(customer.id)
  const [confirmArchive, setConfirmArchive] = useState(false)
  const archived = customer.status === 'ARCHIVED'

  function setStatus(status: 'ACTIVE' | 'ARCHIVED') {
    onMessage(null)
    changeStatus.mutate(
      { status, version: customer.version },
      {
        onSuccess: () => onMessage({ tone: 'success', text: status === 'ARCHIVED' ? 'Customer archived.' : 'Customer restored.' }),
        onError: (e) => onMessage({ tone: 'error', text: customerErrorMessage(e) }),
        onSettled: () => setConfirmArchive(false),
      },
    )
  }

  return (
    <section className="rounded-xl border border-slate-200 bg-white p-4 sm:p-5">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <div className="flex flex-wrap items-center gap-2">
            <h1 className="truncate text-2xl font-semibold text-slate-900">{customer.name}</h1>
            <CustomerStatusBadge status={customer.status} />
          </div>
          <p className="mt-1 text-sm tabular-nums text-slate-700">
            <a href={`tel:${customer.phone}`} className="hover:text-indigo-600">
              {customer.phone}
            </a>
            {customer.altPhone && (
              <>
                {' · '}
                <a href={`tel:${customer.altPhone}`} className="text-slate-500 hover:text-indigo-600">
                  {customer.altPhone}
                </a>
              </>
            )}
          </p>
          <div className="mt-2">
            <TagChips tags={customer.tags} />
          </div>
        </div>
        {canEdit && (
          <div className="flex gap-2">
            <Button variant="secondary" onClick={onEdit}>
              Edit
            </Button>
            {archived ? (
              <Button variant="secondary" loading={changeStatus.isPending} onClick={() => setStatus('ACTIVE')}>
                Restore
              </Button>
            ) : (
              <Button variant="ghost" className="text-red-600 hover:bg-red-50" onClick={() => setConfirmArchive(true)}>
                Archive
              </Button>
            )}
          </div>
        )}
      </div>

      {(customer.note || customer.facebookProfileUrl) && (
        <dl className="mt-4 space-y-2 border-t border-slate-100 pt-4 text-sm">
          {customer.note && (
            <div>
              <dt className="text-xs font-medium uppercase tracking-wide text-slate-500">Note</dt>
              <dd className="whitespace-pre-line text-slate-700">{customer.note}</dd>
            </div>
          )}
          {customer.facebookProfileUrl && (
            <div>
              <dt className="text-xs font-medium uppercase tracking-wide text-slate-500">Facebook</dt>
              <dd>
                <a href={customer.facebookProfileUrl} target="_blank" rel="noopener noreferrer" className="break-all text-indigo-600 hover:text-indigo-500">
                  {customer.facebookProfileUrl}
                </a>
              </dd>
            </div>
          )}
        </dl>
      )}

      <ConfirmDialog
        open={confirmArchive}
        title="Archive this customer?"
        confirmLabel="Archive"
        danger
        loading={changeStatus.isPending}
        onConfirm={() => setStatus('ARCHIVED')}
        onCancel={() => setConfirmArchive(false)}
      >
        The customer is hidden from the list. Their order history stays, and you can restore them at any time.
      </ConfirmDialog>
    </section>
  )
}

function profileOf(c: Customer): ProfileForm {
  return {
    name: c.name,
    phone: c.phone,
    altPhone: c.altPhone ?? '',
    facebookProfileUrl: c.facebookProfileUrl ?? '',
    note: c.note ?? '',
    tags: c.tags.join(', '),
  }
}

function ProfileEditForm({ customer, onDone }: { customer: Customer; onDone: (message?: string) => void }) {
  const update = useUpdateCustomer(customer.id)
  const [form, setForm] = useState<ProfileForm>(() => profileOf(customer))
  const [errors, setErrors] = useState<ProfileErrors>({})
  const [formError, setFormError] = useState<string | null>(null)
  const original = profileOf(customer)
  const dirty = (Object.keys(form) as Array<keyof ProfileForm>).some((k) => form[k].trim() !== original[k].trim())
  const guard = useUnsavedChangesGuard(dirty && !update.isPending)

  function onSubmit(event: FormEvent) {
    event.preventDefault()
    setFormError(null)
    const clientErrors = validateProfile(form)
    setErrors(clientErrors)
    if (Object.keys(clientErrors).length > 0) return
    update.mutate(
      { ...profileRequest(form), version: customer.version },
      {
        onSuccess: () => onDone('Customer saved.'),
        onError: (e) => {
          setErrors(profileFieldErrors(fieldErrorsOf(e)))
          setFormError(customerErrorMessage(e, 'Could not save the customer.'))
        },
      },
    )
  }

  return (
    <form onSubmit={onSubmit} noValidate className="space-y-4 rounded-xl border border-slate-200 bg-white p-4 sm:p-5">
      <h2 className="font-semibold text-slate-900">Edit customer</h2>
      {formError && <Alert tone="error">{formError}</Alert>}
      <ProfileFields
        value={form}
        onChange={(field, v) => {
          setForm((f) => ({ ...f, [field]: v }))
          setErrors((e) => ({ ...e, [field]: undefined }))
        }}
        errors={errors}
      />
      <div className="flex justify-end gap-2">
        <Button variant="secondary" onClick={() => onDone()} disabled={update.isPending}>
          Cancel
        </Button>
        <Button type="submit" loading={update.isPending} disabled={!dirty}>
          Save
        </Button>
      </div>
      <ConfirmDialog open={guard.pendingPath !== null} title="Leave without saving?" confirmLabel="Leave" danger onConfirm={guard.leave} onCancel={guard.stay}>
        You have changes that are not saved. If you leave this page, they are lost.
      </ConfirmDialog>
    </form>
  )
}

// ==== Counters ==============================================================================

function StatsTiles({ customer }: { customer: Customer }) {
  const s = customer.stats
  const last = s.lastOrderAt ? new Date(s.lastOrderAt).toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric' }) : '—'
  return (
    <section>
      <h2 className="mb-3 font-semibold text-slate-900">Orders</h2>
      <dl className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-6">
        <Tile label="Total" value={String(s.totalOrders)} />
        <Tile label="Delivered" value={String(s.deliveredOrders)} />
        <Tile label="Returned" value={String(s.returnedOrders)} warn={s.returnedOrders > 0} />
        <Tile label="Cancelled" value={String(s.cancelledOrders)} warn={s.cancelledOrders > 0} />
        <Tile label="Spent" value={formatTaka(s.totalSpent)} hint="Delivered orders" />
        <Tile label="Last order" value={last} />
      </dl>
    </section>
  )
}

function Tile({ label, value, hint, warn }: { label: string; value: string; hint?: string; warn?: boolean }) {
  return (
    <div className="rounded-xl border border-slate-200 bg-white p-3">
      <dt className="text-xs font-medium uppercase tracking-wide text-slate-500">{label}</dt>
      <dd className={warn ? 'mt-1 text-lg font-semibold tabular-nums text-amber-700' : 'mt-1 text-lg font-semibold tabular-nums text-slate-900'}>{value}</dd>
      {hint && <p className="text-xs text-slate-400">{hint}</p>}
    </div>
  )
}

// ==== Addresses =============================================================================

function AddressesSection({ customer, canEdit }: { customer: Customer; canEdit: boolean }) {
  const [dialog, setDialog] = useState<{ address?: CustomerAddress } | null>(null)
  const [toRemove, setToRemove] = useState<CustomerAddress | null>(null)
  const [error, setError] = useState<string | null>(null)
  const remove = useRemoveAddress(customer.id)
  const save = useSaveAddress(customer.id)
  const full = customer.addresses.length >= MAX_ADDRESSES

  function makeDefault(address: CustomerAddress) {
    setError(null)
    save.mutate(
      { addressId: address.addressId, body: { ...addressRequest(addressFormOf(address)), isDefault: true } },
      { onError: (e) => setError(customerErrorMessage(e)) },
    )
  }

  function confirmRemove() {
    if (!toRemove) return
    setError(null)
    remove.mutate(toRemove.addressId, {
      onError: (e) => setError(customerErrorMessage(e)),
      onSettled: () => setToRemove(null),
    })
  }

  return (
    <section>
      <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
        <h2 className="font-semibold text-slate-900">
          Addresses <span className="text-sm font-normal text-slate-500">({customer.addresses.length} of {MAX_ADDRESSES})</span>
        </h2>
        {canEdit && (
          <Button variant="secondary" onClick={() => setDialog({})} disabled={full} title={full ? 'Remove an address first' : undefined}>
            <PlusIcon /> Add address
          </Button>
        )}
      </div>
      {error && <Alert tone="error" className="mb-3">{error}</Alert>}

      {customer.addresses.length === 0 ? (
        <p className="rounded-xl border border-dashed border-slate-300 bg-white px-4 py-8 text-center text-sm text-slate-500">
          No address yet.{canEdit ? ' Add one to pre-fill new orders.' : ''}
        </p>
      ) : (
        <ul className="grid gap-3 sm:grid-cols-2">
          {customer.addresses.map((a) => (
            <li key={a.addressId} className="rounded-xl border border-slate-200 bg-white p-4 text-sm">
              <div className="flex items-start justify-between gap-2">
                <p className="font-medium text-slate-900">{a.label ?? 'Address'}</p>
                {a.isDefault && <span className="rounded-full bg-indigo-600 px-2 py-0.5 text-xs font-semibold text-white">Default</span>}
              </div>
              <p className="mt-1 text-slate-700">{a.line}</p>
              <p className="text-slate-600">{[a.area, a.district].filter(Boolean).join(', ')}</p>
              <p className="text-xs text-slate-500">{ZONE_LABELS[a.zone]}</p>
              {a.note && <p className="mt-1 text-xs text-slate-500">Landmark: {a.note}</p>}
              {canEdit && (
                <div className="mt-3 flex flex-wrap gap-1">
                  <Button variant="ghost" className="px-2 py-1.5" onClick={() => setDialog({ address: a })}>
                    Edit
                  </Button>
                  {!a.isDefault && (
                    <Button variant="ghost" className="px-2 py-1.5" loading={save.isPending && save.variables?.addressId === a.addressId} onClick={() => makeDefault(a)}>
                      Make default
                    </Button>
                  )}
                  <Button variant="ghost" className="px-2 py-1.5 text-red-600 hover:bg-red-50" onClick={() => setToRemove(a)}>
                    Remove
                  </Button>
                </div>
              )}
            </li>
          ))}
        </ul>
      )}

      <AddressDialog
        open={dialog !== null}
        customerId={customer.id}
        address={dialog?.address}
        isFirst={customer.addresses.length === 0}
        onClose={() => setDialog(null)}
      />
      <ConfirmDialog
        open={toRemove !== null}
        title="Remove this address?"
        confirmLabel="Remove"
        danger
        loading={remove.isPending}
        onConfirm={confirmRemove}
        onCancel={() => setToRemove(null)}
      >
        {toRemove?.isDefault && customer.addresses.length > 1
          ? 'This is the default address. The oldest remaining address becomes the default.'
          : 'Past orders keep their own copy of the address.'}
      </ConfirmDialog>
    </section>
  )
}
