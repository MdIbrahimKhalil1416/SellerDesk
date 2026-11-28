import { useState, type FormEvent } from 'react'
import { Alert } from '../../../components/ui/Alert'
import { Button } from '../../../components/ui/Button'
import { Modal } from '../../../components/ui/Modal'
import { fieldErrorsOf } from '../../../lib/apiError'
import type { CustomerAddress } from '../../../types/customer'
import { addressFieldErrors, addressFormOf, addressRequest, EMPTY_ADDRESS, validateAddress, type AddressErrors, type AddressForm } from '../customerForm'
import { customerErrorMessage } from '../customerErrors'
import { useSaveAddress } from '../queries'
import { AddressFields } from './AddressFields'

/**
 * Add an address (`address` undefined) or edit one. Open while `open` is true. The default mark
 * can only be moved to an address (checkbox), never removed from the current default.
 */
export function AddressDialog({ open, customerId, address, isFirst, onClose }: {
  open: boolean
  customerId: string
  address?: CustomerAddress
  /** The customer has no address yet: this one becomes the default anyway. */
  isFirst: boolean
  onClose: () => void
}) {
  return (
    <Modal open={open} title={address ? 'Edit address' : 'Add address'} onClose={onClose}>
      {open && <AddressDialogForm key={address?.addressId ?? 'new'} customerId={customerId} address={address} isFirst={isFirst} onClose={onClose} />}
    </Modal>
  )
}

function AddressDialogForm({ customerId, address, isFirst, onClose }: { customerId: string; address?: CustomerAddress; isFirst: boolean; onClose: () => void }) {
  const save = useSaveAddress(customerId)
  const [form, setForm] = useState<AddressForm>(() => (address ? addressFormOf(address) : { ...EMPTY_ADDRESS, isDefault: isFirst }))
  const [errors, setErrors] = useState<AddressErrors>({})
  const [formError, setFormError] = useState<string | null>(null)
  const wasDefault = address?.isDefault ?? false

  function onSubmit(event: FormEvent) {
    event.preventDefault()
    setFormError(null)
    const clientErrors = validateAddress(form)
    setErrors(clientErrors)
    if (Object.keys(clientErrors).length > 0) return
    save.mutate(
      { addressId: address?.addressId, body: addressRequest(form) },
      {
        onSuccess: onClose,
        onError: (e) => {
          setErrors(addressFieldErrors(fieldErrorsOf(e)))
          setFormError(customerErrorMessage(e, 'Could not save the address.'))
        },
      },
    )
  }

  return (
    <form onSubmit={onSubmit} noValidate className="space-y-4">
      {formError && <Alert tone="error">{formError}</Alert>}
      <AddressFields value={form} onChange={setForm} errors={errors} showDefault={!wasDefault && !isFirst} />
      {(wasDefault || isFirst) && <p className="text-xs text-slate-500">This is the default address for new orders.</p>}
      <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
        <Button variant="secondary" onClick={onClose} disabled={save.isPending}>
          Cancel
        </Button>
        <Button type="submit" loading={save.isPending}>
          {address ? 'Save address' : 'Add address'}
        </Button>
      </div>
    </form>
  )
}
