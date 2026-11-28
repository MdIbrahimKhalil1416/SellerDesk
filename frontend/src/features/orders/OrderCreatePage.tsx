import { useQuery } from '@tanstack/react-query'
import { useState, type FormEvent, type ReactNode } from 'react'
import { Link, useNavigate } from 'react-router'
import { customersApi } from '../../api/customers'
import { ArrowLeftIcon, TrashIcon } from '../../components/icons'
import { Alert } from '../../components/ui/Alert'
import { Button } from '../../components/ui/Button'
import { ConfirmDialog } from '../../components/ui/ConfirmDialog'
import { MoneyInput } from '../../components/ui/MoneyInput'
import { Select } from '../../components/ui/Select'
import { Spinner } from '../../components/ui/Spinner'
import { TextArea } from '../../components/ui/TextArea'
import { TextField } from '../../components/ui/TextField'
import { fieldErrorsOf, httpStatusOf } from '../../lib/apiError'
import { normalizeBdPhone } from '../../lib/bdPhone'
import { cn } from '../../lib/cn'
import { formatTaka, parseTakaToPoisha, poishaToTakaInput } from '../../lib/money'
import { useDebouncedValue } from '../../lib/useDebouncedValue'
import { useUnsavedChangesGuard } from '../../lib/useUnsavedChangesGuard'
import type { Customer } from '../../types/customer'
import type { CreateOrderRequest, OrderSource } from '../../types/order'
import type { DeliveryZone } from '../../types/shop'
import { AddressFields } from '../customers/components/AddressFields'
import { addressFieldErrors, EMPTY_ADDRESS, validateAddress, ZONE_LABELS, type AddressErrors, type AddressForm } from '../customers/customerForm'
import { useShop } from '../settings/queries'
import { MoneyLines } from './components/MoneyLines'
import { ProductPicker } from './components/ProductPicker'
import { itemRequests, MAX_DELIVERY_CHARGE, MAX_ITEMS, MAX_NOTE, newLine, parseOptionalTaka, parseQuantity, previewTotals, savedAddress, type LineForm } from './orderForm'
import { SOURCE_OPTIONS } from './orderLabels'
import { orderErrorMessage } from './orderErrors'
import { useCreateOrder } from './queries'

/** Which delivery address: one of the customer's saved ones (its addressId) or a new one. */
const NEW_ADDRESS = 'new'

type Errors = Record<string, string | undefined>

/**
 * /orders/new (ORDER_CREATE). Phone first: an existing customer is found and filled in, a new
 * phone creates the customer with the order. Totals are previewed here and computed again by the
 * server. Stock is not reserved yet: that happens when the order is confirmed.
 */
export function OrderCreatePage() {
  const navigate = useNavigate()
  const create = useCreateOrder()
  const shop = useShop()
  /** One id per form: a double tap or a retry returns the same order instead of a second one. */
  const [requestId] = useState(() => crypto.randomUUID())

  const [phone, setPhone] = useState('')
  const [name, setName] = useState('')
  const [addressChoice, setAddressChoice] = useState<string>(NEW_ADDRESS)
  const [address, setAddress] = useState<AddressForm>(EMPTY_ADDRESS)
  const [lines, setLines] = useState<LineForm[]>([])
  const [discount, setDiscount] = useState('')
  const [deliveryCharge, setDeliveryCharge] = useState<string | null>(null) // null = follow the zone
  const [advancePaid, setAdvancePaid] = useState('')
  const [source, setSource] = useState<OrderSource>('MESSENGER')
  const [note, setNote] = useState('')
  const [errors, setErrors] = useState<Errors>({})
  const [addressErrors, setAddressErrors] = useState<AddressErrors>({})
  const [formError, setFormError] = useState<string | null>(null)
  const [saved, setSaved] = useState(false)

  // ---- Customer lookup by phone -------------------------------------------------------
  const normalizedPhone = normalizeBdPhone(useDebouncedValue(phone, 400))
  const lookup = useQuery({
    queryKey: ['orders', 'customer-lookup', normalizedPhone],
    queryFn: () => customersApi.lookup(normalizedPhone ?? ''),
    enabled: !!normalizedPhone,
    retry: false,
    staleTime: 30_000,
  })
  const notFound = lookup.isError && httpStatusOf(lookup.error) === 404
  const customer: Customer | null = normalizedPhone && lookup.data?.phone === normalizedPhone ? lookup.data : null

  // When a different customer is found, fill in the name and the default address. Done during
  // render with the "previous value" pattern instead of an effect (no extra render).
  const [filledFor, setFilledFor] = useState<string | null>(null)
  if ((customer?.id ?? null) !== filledFor) {
    setFilledFor(customer?.id ?? null)
    if (customer) {
      setName(customer.name)
      setAddressChoice(customer.addresses.find((a) => a.isDefault)?.addressId ?? NEW_ADDRESS)
    } else {
      setAddressChoice(NEW_ADDRESS)
    }
  }

  const chosenSaved = customer?.addresses.find((a) => a.addressId === addressChoice) ?? null
  const zone: DeliveryZone = chosenSaved ? chosenSaved.zone : address.zone

  // ---- Money ----------------------------------------------------------------------------
  const settings = shop.data?.settings
  const defaultCharge = settings ? (zone === 'INSIDE_DHAKA' ? settings.deliveryChargeInsideDhaka : settings.deliveryChargeOutsideDhaka) : 0
  const chargeText = deliveryCharge ?? poishaToTakaInput(defaultCharge)
  const discountPoisha = parseOptionalTaka(discount)
  const chargePoisha = parseOptionalTaka(chargeText)
  const advancePoisha = parseOptionalTaka(advancePaid)
  const preview = previewTotals(lines, discountPoisha ?? 0, chargePoisha ?? 0, advancePoisha ?? 0)

  const dirty = !saved && (phone.trim() !== '' || lines.length > 0)
  const guard = useUnsavedChangesGuard(dirty && !create.isPending)

  function clearError(key: string) {
    setErrors((e) => ({ ...e, [key]: undefined }))
  }

  function updateLine(index: number, change: Partial<LineForm>) {
    setLines((current) => current.map((line, i) => (i === index ? { ...line, ...change } : line)))
    for (const key of Object.keys(change)) clearError(`items[${index}].${key}`)
    clearError(`items[${index}].variantId`)
  }

  function validate(): Errors {
    const e: Errors = {}
    if (!normalizeBdPhone(phone)) e['customer.phone'] = 'Enter a Bangladeshi mobile number, e.g. 01712345678'
    if (!customer && !name.trim()) e['customer.name'] = 'Enter the customer name'
    else if (name.trim().length > 100) e['customer.name'] = 'At most 100 characters'
    if (lines.length === 0) e.items = 'Add at least one product'
    if (lines.length > MAX_ITEMS) e.items = `At most ${MAX_ITEMS} products in one order`
    lines.forEach((line, i) => {
      if (parseQuantity(line.quantity) === null) e[`items[${i}].quantity`] = '1–999'
      if (parseTakaToPoisha(line.unitPrice) === null) e[`items[${i}].unitPrice`] = 'Enter a price'
    })
    if (discountPoisha === null) e.discount = 'Enter an amount like 50 or 49.50'
    else if (discountPoisha > preview.subtotal) e.discount = 'Cannot be more than the subtotal'
    if (chargePoisha === null) e.deliveryCharge = 'Enter an amount like 60'
    else if (chargePoisha > MAX_DELIVERY_CHARGE) e.deliveryCharge = 'At most ৳5,000'
    if (advancePoisha === null) e.advancePaid = 'Enter an amount like 200'
    else if (advancePoisha > preview.total) e.advancePaid = 'Cannot be more than the total'
    if (note.trim().length > MAX_NOTE) e.note = `At most ${MAX_NOTE} characters`
    return e
  }

  function onSubmit(event: FormEvent) {
    event.preventDefault()
    setFormError(null)
    const e = validate()
    const ae = addressChoice === NEW_ADDRESS ? validateAddress(address) : {}
    setErrors(e)
    setAddressErrors(ae)
    if (Object.values(e).some(Boolean) || Object.keys(ae).length > 0) {
      setFormError('Some fields are not valid. Please check the highlighted fields.')
      return
    }
    const newAddress = {
      line: address.line.trim(),
      area: address.area.trim() || null,
      district: address.district.trim(),
      zone: address.zone,
      note: address.note.trim() || null,
    }
    const body: CreateOrderRequest = {
      requestId,
      customer: {
        name: name.trim(),
        phone: normalizeBdPhone(phone) ?? phone,
        // A new address is also saved on the customer (if there is room for it).
        ...(chosenSaved ? {} : { address: newAddress }),
      },
      ...(chosenSaved ? { deliveryAddress: savedAddress(chosenSaved) } : {}),
      items: itemRequests(lines),
      // Left out while it follows the zone: the server applies the same default.
      ...(deliveryCharge !== null ? { deliveryCharge: chargePoisha ?? 0 } : {}),
      discount: discountPoisha ?? 0,
      advancePaid: advancePoisha ?? 0,
      source,
      ...(note.trim() ? { note: note.trim() } : {}),
    }
    create.mutate(body, {
      onSuccess: (order) => {
        setSaved(true)
        navigate(`/orders/${order.id}`, { replace: true })
      },
      onError: (err) => {
        const fields = fieldErrorsOf(err)
        setErrors(fields)
        setAddressErrors(addressFieldErrors(fields, 'customer.address.'))
        setFormError(orderErrorMessage(err, 'Could not save the order.'))
      },
    })
  }

  return (
    <form onSubmit={onSubmit} noValidate className="mx-auto max-w-5xl space-y-6 pb-24 sm:pb-0">
      <div>
        <Link to="/orders" className="inline-flex items-center gap-1 text-sm font-medium text-slate-600 hover:text-slate-900">
          <ArrowLeftIcon /> Orders
        </Link>
        <h1 className="mt-2 text-2xl font-semibold text-slate-900">New order</h1>
      </div>

      {formError && <Alert tone="error">{formError}</Alert>}

      <div className="grid gap-6 lg:grid-cols-[3fr_2fr]">
        <div className="space-y-6">
          {/* ---- Customer ---- */}
          <section className="space-y-4 rounded-xl border border-slate-200 bg-white p-4 sm:p-5">
            <h2 className="font-semibold text-slate-900">Customer</h2>
            <div className="grid gap-4 sm:grid-cols-2">
              <TextField
                label="Phone"
                type="tel"
                inputMode="tel"
                autoComplete="off"
                placeholder="01712345678"
                value={phone}
                onChange={(e) => {
                  setPhone(e.target.value)
                  clearError('customer.phone')
                }}
                error={errors['customer.phone']}
                hint={
                  !normalizedPhone ? (
                    'Type the phone first: an existing customer is filled in.'
                  ) : lookup.isFetching ? (
                    <span className="inline-flex items-center gap-1">
                      <Spinner /> Looking up…
                    </span>
                  ) : customer ? (
                    <span className="text-emerald-700">
                      Existing customer · {customer.stats.totalOrders} order{customer.stats.totalOrders === 1 ? '' : 's'}
                      {customer.stats.returnedOrders > 0 && `, ${customer.stats.returnedOrders} returned`}
                    </span>
                  ) : notFound ? (
                    'New customer: saved together with the order.'
                  ) : lookup.isError ? (
                    <span className="text-amber-700">Could not look up the phone. You can still continue.</span>
                  ) : undefined
                }
              />
              <TextField
                label="Name"
                autoComplete="off"
                placeholder="Rahim Uddin"
                value={name}
                onChange={(e) => {
                  setName(e.target.value)
                  clearError('customer.name')
                }}
                error={errors['customer.name']}
                hint={customer ? 'Shown on this order. The saved customer name does not change.' : undefined}
              />
            </div>

            <div className="space-y-3">
              <h3 className="text-sm font-medium text-slate-700">Delivery address</h3>
              {customer && customer.addresses.length > 0 && (
                <div className="space-y-2">
                  {customer.addresses.map((a) => (
                    <AddressOption key={a.addressId} checked={addressChoice === a.addressId} onChange={() => setAddressChoice(a.addressId)}>
                      <span className="font-medium text-slate-900">{a.label ?? a.line}</span>
                      <span className="block text-slate-600">
                        {a.label && `${a.line}, `}
                        {[a.area, a.district].filter(Boolean).join(', ')} · {ZONE_LABELS[a.zone]}
                      </span>
                    </AddressOption>
                  ))}
                  <AddressOption checked={addressChoice === NEW_ADDRESS} onChange={() => setAddressChoice(NEW_ADDRESS)}>
                    <span className="font-medium text-slate-900">Another address</span>
                  </AddressOption>
                </div>
              )}
              {addressChoice === NEW_ADDRESS && (
                <AddressFields
                  value={address}
                  onChange={(next) => {
                    setAddress(next)
                    setAddressErrors({})
                    clearError('deliveryAddress')
                  }}
                  errors={addressErrors}
                />
              )}
              {errors.deliveryAddress && <p className="text-sm text-red-600">{errors.deliveryAddress}</p>}
            </div>
          </section>

          {/* ---- Items ---- */}
          <section className="space-y-4 rounded-xl border border-slate-200 bg-white p-4 sm:p-5">
            <div>
              <h2 className="font-semibold text-slate-900">Products</h2>
              <p className="mt-1 text-sm text-slate-600">Stock is reserved when the order is confirmed, not now.</p>
            </div>
            <ProductPicker
              addedIds={new Set(lines.map((l) => l.variantId))}
              onAdd={(v) => {
                setLines((current) => [...current, newLine(v)])
                clearError('items')
              }}
            />
            {errors.items && <p className="text-sm text-red-600">{errors.items}</p>}
            {lines.length > 0 && (
              <ul className="divide-y divide-slate-100 rounded-lg border border-slate-200">
                {lines.map((line, i) => (
                  <LineRow
                    key={line.variantId}
                    line={line}
                    index={i}
                    errors={errors}
                    onChange={(change) => updateLine(i, change)}
                    onRemove={() => setLines((current) => current.filter((_, j) => j !== i))}
                  />
                ))}
              </ul>
            )}
          </section>

          <section className="grid gap-4 rounded-xl border border-slate-200 bg-white p-4 sm:grid-cols-2 sm:p-5">
            <Select label="Source" value={source} onChange={(e) => setSource(e.target.value as OrderSource)} options={SOURCE_OPTIONS} />
            <TextArea
              label="Note (optional)"
              rows={2}
              className="sm:col-span-2"
              placeholder="Call before delivery"
              value={note}
              onChange={(e) => {
                setNote(e.target.value)
                clearError('note')
              }}
              error={errors.note}
            />
          </section>
        </div>

        {/* ---- Money + preview ---- */}
        <aside className="space-y-4 lg:sticky lg:top-20 lg:self-start">
          <section className="space-y-4 rounded-xl border border-slate-200 bg-white p-4 sm:p-5">
            <h2 className="font-semibold text-slate-900">Payment</h2>
            <MoneyInput
              label="Delivery charge (৳)"
              value={chargeText}
              onChange={(e) => {
                setDeliveryCharge(e.target.value)
                clearError('deliveryCharge')
              }}
              error={errors.deliveryCharge}
              hint={
                deliveryCharge === null ? (
                  `Shop default for ${ZONE_LABELS[zone].toLowerCase()}.`
                ) : (
                  <button type="button" className="font-medium text-indigo-600 hover:text-indigo-500" onClick={() => setDeliveryCharge(null)}>
                    Use the shop default ({formatTaka(defaultCharge)})
                  </button>
                )
              }
            />
            <MoneyInput
              label="Discount (৳, optional)"
              value={discount}
              onChange={(e) => {
                setDiscount(e.target.value)
                clearError('discount')
              }}
              error={errors.discount}
            />
            <MoneyInput
              label="Advance paid (৳, optional)"
              value={advancePaid}
              onChange={(e) => {
                setAdvancePaid(e.target.value)
                clearError('advancePaid')
              }}
              error={errors.advancePaid}
              hint="bKash / Nagad already received. The courier collects the rest."
            />
            <MoneyLines {...preview} />
            <p className="text-xs text-slate-500">Preview. The server calculates the totals again when you save.</p>
          </section>

          <div className="fixed inset-x-0 bottom-0 z-20 border-t border-slate-200 bg-white/95 px-4 py-3 backdrop-blur sm:static sm:border-0 sm:bg-transparent sm:p-0">
            <div className="flex justify-end gap-2">
              <Button variant="secondary" onClick={() => navigate('/orders')} disabled={create.isPending}>
                Cancel
              </Button>
              <Button type="submit" loading={create.isPending}>
                Save order
              </Button>
            </div>
          </div>
        </aside>
      </div>

      <ConfirmDialog open={guard.pendingPath !== null} title="Leave without saving?" confirmLabel="Leave" danger onConfirm={guard.leave} onCancel={guard.stay}>
        The order is not saved yet. If you leave this page, what you typed is lost.
      </ConfirmDialog>
    </form>
  )
}

function AddressOption({ checked, onChange, children }: { checked: boolean; onChange: () => void; children: ReactNode }) {
  return (
    <label className={cn('flex cursor-pointer gap-3 rounded-lg border px-3 py-2 text-sm', checked ? 'border-indigo-400 bg-indigo-50/50' : 'border-slate-200')}>
      <input type="radio" name="delivery-address" checked={checked} onChange={onChange} className="mt-0.5 h-4 w-4 text-indigo-600 focus:ring-indigo-500" />
      <span className="min-w-0">{children}</span>
    </label>
  )
}

function LineRow({ line, index, errors, onChange, onRemove }: {
  line: LineForm
  index: number
  errors: Errors
  onChange: (change: Partial<LineForm>) => void
  onRemove: () => void
}) {
  const quantity = parseQuantity(line.quantity)
  const unitPrice = parseTakaToPoisha(line.unitPrice)
  const short = line.available !== undefined && quantity !== null && quantity > line.available
  const serverError = errors[`items[${index}].variantId`]

  return (
    <li className={cn('space-y-2 px-3 py-3', serverError && 'bg-red-50')}>
      <div className="flex items-start gap-3">
        <div className="min-w-0 flex-1">
          <p className="truncate text-sm font-medium text-slate-900">{line.productName}</p>
          <p className="text-xs text-slate-500">
            {line.variantName} · <span className="font-mono">{line.sku}</span>
            {line.available !== undefined && ` · ${line.available} available`}
          </p>
        </div>
        <button type="button" onClick={onRemove} className="rounded-md p-1.5 text-slate-500 hover:bg-slate-100 hover:text-red-600" aria-label={`Remove ${line.productName}`}>
          <TrashIcon />
        </button>
      </div>
      <div className="grid grid-cols-[6rem_1fr_auto] items-start gap-3">
        <TextField
          label="Qty"
          inputMode="numeric"
          value={line.quantity}
          onChange={(e) => onChange({ quantity: e.target.value })}
          error={errors[`items[${index}].quantity`]}
        />
        <MoneyInput
          label="Unit price (৳)"
          value={line.unitPrice}
          onChange={(e) => onChange({ unitPrice: e.target.value })}
          error={errors[`items[${index}].unitPrice`]}
          hint={unitPrice !== null && unitPrice !== line.price ? `List price ${formatTaka(line.price)}` : undefined}
        />
        <p className="pt-8 text-right text-sm font-medium tabular-nums text-slate-900">
          {quantity !== null && unitPrice !== null ? formatTaka(quantity * unitPrice) : '—'}
        </p>
      </div>
      {serverError && <p className="text-sm text-red-700">{serverError}</p>}
      {short && (
        <p className="text-sm text-amber-700">
          Only {line.available} available. You can take the order; it can be confirmed once stock is added.
        </p>
      )}
    </li>
  )
}
