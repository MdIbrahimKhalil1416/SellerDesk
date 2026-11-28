import { useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router'
import { customersApi } from '../../api/customers'
import { ArrowLeftIcon } from '../../components/icons'
import { Alert } from '../../components/ui/Alert'
import { Button } from '../../components/ui/Button'
import { ConfirmDialog } from '../../components/ui/ConfirmDialog'
import { fieldErrorsOf } from '../../lib/apiError'
import { normalizeBdPhone } from '../../lib/bdPhone'
import { useUnsavedChangesGuard } from '../../lib/useUnsavedChangesGuard'
import type { Customer } from '../../types/customer'
import { AddressFields } from './components/AddressFields'
import { ProfileFields } from './components/ProfileFields'
import {
  addressFieldErrors,
  addressRequest,
  EMPTY_ADDRESS,
  EMPTY_PROFILE,
  isAddressStarted,
  profileFieldErrors,
  profileRequest,
  validateAddress,
  validateProfile,
  type AddressErrors,
  type AddressForm,
  type ProfileErrors,
  type ProfileForm,
} from './customerForm'
import { customerErrorMessage, errorCodeOf } from './customerErrors'
import { useCreateCustomer } from './queries'

/** /customers/new (CUSTOMER_EDIT): profile and an optional first address. */
export function CustomerCreatePage() {
  const navigate = useNavigate()
  const create = useCreateCustomer()
  const [profile, setProfile] = useState<ProfileForm>(EMPTY_PROFILE)
  const [address, setAddress] = useState<AddressForm>(EMPTY_ADDRESS)
  const [profileErrors, setProfileErrors] = useState<ProfileErrors>({})
  const [addressErrors, setAddressErrors] = useState<AddressErrors>({})
  const [formError, setFormError] = useState<string | null>(null)
  /** The phone already belongs to this customer (409): offer to open it instead. */
  const [existing, setExisting] = useState<Customer | null>(null)
  const [saved, setSaved] = useState(false)

  const dirty = !saved && (Object.values(profile).some((v) => v.trim() !== '') || isAddressStarted(address))
  const guard = useUnsavedChangesGuard(dirty && !create.isPending)

  function onProfileChange(field: keyof ProfileForm, value: string) {
    setProfile((p) => ({ ...p, [field]: value }))
    setProfileErrors((e) => ({ ...e, [field]: undefined }))
    if (field === 'phone') setExisting(null)
  }

  function onSubmit(event: FormEvent) {
    event.preventDefault()
    setFormError(null)
    setExisting(null)
    const pe = validateProfile(profile)
    const withAddress = isAddressStarted(address)
    const ae = withAddress ? validateAddress(address) : {}
    setProfileErrors(pe)
    setAddressErrors(ae)
    if (Object.keys(pe).length > 0 || Object.keys(ae).length > 0) {
      setFormError('Some fields are not valid. Please check the highlighted fields.')
      return
    }
    create.mutate(
      { ...profileRequest(profile), addresses: withAddress ? [{ ...addressRequest(address), isDefault: true }] : [] },
      {
        onSuccess: (customer) => {
          setSaved(true)
          navigate(`/customers/${customer.id}`, { replace: true })
        },
        onError: (e) => {
          const fields = fieldErrorsOf(e)
          setProfileErrors(profileFieldErrors(fields))
          setAddressErrors(addressFieldErrors(fields, 'addresses[0].'))
          setFormError(customerErrorMessage(e, 'Could not save the customer.'))
          if (errorCodeOf(e) === 'PHONE_ALREADY_EXISTS') {
            const phone = normalizeBdPhone(profile.phone)
            if (phone) {
              customersApi.lookup(phone).then(setExisting, () => setExisting(null))
            }
          }
        },
      },
    )
  }

  return (
    <form onSubmit={onSubmit} noValidate className="mx-auto max-w-3xl space-y-6 pb-24 sm:pb-0">
      <div>
        <Link to="/customers" className="inline-flex items-center gap-1 text-sm font-medium text-slate-600 hover:text-slate-900">
          <ArrowLeftIcon /> Customers
        </Link>
        <h1 className="mt-2 text-2xl font-semibold text-slate-900">Add customer</h1>
      </div>

      {formError && (
        <Alert tone="error">
          {formError}
          {existing && (
            <p className="mt-1">
              <Link to={`/customers/${existing.id}`} className="font-semibold underline">
                Open {existing.name}
              </Link>
              {existing.status === 'ARCHIVED' && ' (archived: you can restore it there)'}
            </p>
          )}
        </Alert>
      )}

      <section className="space-y-4 rounded-xl border border-slate-200 bg-white p-4 sm:p-5">
        <h2 className="font-semibold text-slate-900">Customer</h2>
        <ProfileFields value={profile} onChange={onProfileChange} errors={profileErrors} />
      </section>

      <section className="space-y-4 rounded-xl border border-slate-200 bg-white p-4 sm:p-5">
        <div>
          <h2 className="font-semibold text-slate-900">Address (optional)</h2>
          <p className="mt-1 text-sm text-slate-600">Becomes the default delivery address. You can add more later.</p>
        </div>
        <AddressFields
          value={address}
          onChange={(next) => {
            setAddress(next)
            setAddressErrors({})
          }}
          errors={addressErrors}
        />
      </section>

      <div className="fixed inset-x-0 bottom-0 z-20 border-t border-slate-200 bg-white/95 px-4 py-3 backdrop-blur sm:static sm:border-0 sm:bg-transparent sm:p-0">
        <div className="mx-auto flex max-w-3xl justify-end gap-2">
          <Button variant="secondary" onClick={() => navigate('/customers')} disabled={create.isPending}>
            Cancel
          </Button>
          <Button type="submit" loading={create.isPending}>
            Save customer
          </Button>
        </div>
      </div>

      <ConfirmDialog open={guard.pendingPath !== null} title="Leave without saving?" confirmLabel="Leave" danger onConfirm={guard.leave} onCancel={guard.stay}>
        The customer is not saved yet. If you leave this page, what you typed is lost.
      </ConfirmDialog>
    </form>
  )
}
