import { useState, type ChangeEvent, type FormEvent, type ReactNode } from 'react'
import { Alert } from '../../components/ui/Alert'
import { Button } from '../../components/ui/Button'
import { ConfirmDialog } from '../../components/ui/ConfirmDialog'
import { MoneyInput } from '../../components/ui/MoneyInput'
import { Select } from '../../components/ui/Select'
import { Spinner } from '../../components/ui/Spinner'
import { TextField } from '../../components/ui/TextField'
import { fieldErrorsOf } from '../../lib/apiError'
import { BD_PHONE_EXAMPLE, normalizeBdPhone } from '../../lib/bdPhone'
import { formatTaka, parseTakaToPoisha } from '../../lib/money'
import { useUnsavedChangesGuard } from '../../lib/useUnsavedChangesGuard'
import type { Shop } from '../../types/shop'
import { LogoUploader } from './components/LogoUploader'
import { useShop, useUpdateShop } from './queries'
import { shopErrorMessage } from './shopErrors'
import {
  exampleOrderNumber,
  formFromShop,
  isFormChanged,
  isShopFormField,
  toUpdateRequest,
  validateShopForm,
  type ShopForm,
  type ShopFormErrors,
  type ShopFormField,
} from './shopForm'

const ZONE_OPTIONS = [
  { value: 'INSIDE_DHAKA', label: 'Inside Dhaka' },
  { value: 'OUTSIDE_DHAKA', label: 'Outside Dhaka' },
]

/** /settings (SHOP_SETTINGS). Loads the shop, then shows the form. */
export function SettingsPage() {
  const shop = useShop()

  if (shop.isPending) {
    return (
      <div className="flex items-center gap-3 py-12 text-slate-600" role="status">
        <Spinner /> Loading settings…
      </div>
    )
  }
  if (shop.isError) {
    return (
      <div className="mx-auto max-w-md rounded-xl border border-slate-200 bg-white p-6 text-center">
        <h1 className="text-lg font-semibold text-slate-900">Could not load the settings</h1>
        <p className="mt-2 text-sm text-slate-600">{shopErrorMessage(shop.error)}</p>
        <Button variant="secondary" className="mt-4" onClick={() => void shop.refetch()}>
          Try again
        </Button>
      </div>
    )
  }
  // key: fresh form state for another shop (e.g. after logging in as someone else).
  return <SettingsForm key={shop.data.id} saved={shop.data} />
}

/**
 * `saved` is always the latest shop from the cache (also after a logo upload). The form keeps
 * its own copy while the user types; "changed" means it differs from `saved`.
 */
function SettingsForm({ saved }: { saved: Shop }) {
  const update = useUpdateShop()
  const [form, setForm] = useState<ShopForm>(() => formFromShop(saved))
  const [errors, setErrors] = useState<ShopFormErrors>({})
  const [message, setMessage] = useState<{ tone: 'success' | 'error'; text: ReactNode } | null>(null)

  const changed = isFormChanged(form, saved)
  const guard = useUnsavedChangesGuard(changed && !update.isPending)

  const set = (field: ShopFormField) => (e: ChangeEvent<HTMLInputElement | HTMLSelectElement>) => {
    setForm((f) => ({ ...f, [field]: e.target.value }))
    setErrors((errs) => ({ ...errs, [field]: undefined }))
    if (message?.tone === 'success') setMessage(null)
  }

  /** Common props for one text input: value, onChange, error. */
  const bind = (field: ShopFormField) => ({ name: field, value: form[field], onChange: set(field), error: errors[field] })

  function onSubmit(event: FormEvent) {
    event.preventDefault()
    setMessage(null)
    const clientErrors = validateShopForm(form)
    setErrors(clientErrors)
    if (Object.keys(clientErrors).length > 0) {
      setMessage({ tone: 'error', text: 'Some fields are not valid. Please check the highlighted fields.' })
      return
    }
    update.mutate(toUpdateRequest(form), {
      onSuccess: (shop) => {
        setForm(formFromShop(shop)) // shows what the server stored: "01712345678", "RS"
        setErrors({})
        setMessage({ tone: 'success', text: 'Settings saved.' })
      },
      onError: (err) => {
        // Backend field paths are the form's keys, so most errors land under their input.
        const all = fieldErrorsOf(err)
        const known: ShopFormErrors = {}
        const other: string[] = []
        for (const [field, text] of Object.entries(all)) {
          if (isShopFormField(field)) known[field] = text
          else other.push(`${field}: ${text}`)
        }
        setErrors(known)
        setMessage({
          tone: 'error',
          text: (
            <>
              {shopErrorMessage(err)}
              {other.length > 0 && (
                <ul className="mt-1 list-disc pl-5">
                  {other.map((o) => (
                    <li key={o}>{o}</li>
                  ))}
                </ul>
              )}
            </>
          ),
        })
      },
    })
  }

  const phoneNormalized = normalizeBdPhone(form.phone)
  const insidePoisha = parseTakaToPoisha(form['settings.deliveryChargeInsideDhaka'])
  const outsidePoisha = parseTakaToPoisha(form['settings.deliveryChargeOutsideDhaka'])

  return (
    <form onSubmit={onSubmit} noValidate className="mx-auto max-w-3xl space-y-6 pb-24 sm:pb-0">
      <div>
        <h1 className="text-2xl font-semibold text-slate-900">Settings</h1>
        <p className="mt-1 text-sm text-slate-600">Your shop profile and the defaults used for orders and stock.</p>
      </div>

      {message && <Alert tone={message.tone}>{message.text}</Alert>}

      <Section title="Shop profile" description="Your shop's name, contact details and logo. The name and logo appear in the top bar.">
        <LogoUploader shop={saved} />
        <div className="grid gap-4 sm:grid-cols-2">
          <TextField label="Shop name" autoComplete="organization" maxLength={100} {...bind('name')} />
          <TextField
            label="Phone"
            type="tel"
            inputMode="tel"
            autoComplete="tel"
            placeholder={BD_PHONE_EXAMPLE}
            {...bind('phone')}
            hint={
              phoneNormalized && phoneNormalized !== form.phone.trim()
                ? `Will be saved as ${phoneNormalized}`
                : `Bangladeshi mobile, e.g. ${BD_PHONE_EXAMPLE} or +880 1712-345678`
            }
          />
          <TextField label="Email (optional)" type="email" autoComplete="email" {...bind('email')} />
          <TextField label="Facebook page (optional)" type="url" placeholder="https://facebook.com/yourshop" {...bind('facebookPageUrl')} />
        </div>

        <fieldset className="space-y-4">
          <legend className="text-sm font-medium text-slate-700">Address (optional)</legend>
          <TextField label="Street address" placeholder="House 12, Road 5" autoComplete="street-address" {...bind('address.line')} />
          <div className="grid gap-4 sm:grid-cols-3">
            <TextField label="Area / thana (optional)" placeholder="Dhanmondi" {...bind('address.area')} />
            <TextField label="District" placeholder="Dhaka" {...bind('address.district')} />
            <Select label="Delivery zone" options={ZONE_OPTIONS} name="address.zone" value={form['address.zone']} onChange={set('address.zone')} />
          </div>
          <TextField label="Landmark (optional)" placeholder="Opposite the mosque" {...bind('address.note')} />
        </fieldset>
      </Section>

      <Section title="Delivery charges" description="The default charge added to a new order, based on the customer's delivery zone. You can still change it on each order.">
        <div className="grid gap-4 sm:grid-cols-2">
          <MoneyInput
            label="Inside Dhaka (৳)"
            {...bind('settings.deliveryChargeInsideDhaka')}
            hint={insidePoisha !== null ? formatTaka(insidePoisha) : 'Amount in taka, e.g. 60'}
          />
          <MoneyInput
            label="Outside Dhaka (৳)"
            {...bind('settings.deliveryChargeOutsideDhaka')}
            hint={outsidePoisha !== null ? formatTaka(outsidePoisha) : 'Amount in taka, e.g. 120'}
          />
        </div>
      </Section>

      <Section title="Inventory" description="When the available stock of a variant drops to this number or below, it is shown as low stock so you know to restock. A variant with its own threshold uses that instead.">
        <TextField
          label="Default low-stock threshold"
          inputMode="numeric"
          className="sm:max-w-xs"
          {...bind('settings.defaultLowStockThreshold')}
          hint="Number of items, e.g. 5. Use 0 to warn only when a variant is sold out."
        />
      </Section>

      <Section title="Order numbering" description="Every order number starts with this prefix.">
        <div className="grid gap-4 sm:grid-cols-2 sm:items-start">
          <TextField
            label="Prefix"
            maxLength={6}
            autoCapitalize="characters"
            autoComplete="off"
            className="[&_input]:uppercase"
            {...bind('settings.orderNumberPrefix')}
            hint="2 to 6 letters or digits, e.g. SD or RF24"
          />
          <div className="rounded-lg border border-slate-200 bg-slate-50 px-3 py-2.5">
            <p className="text-xs text-slate-500">Example</p>
            <p className="font-mono text-base font-semibold text-slate-900" aria-live="polite">
              {exampleOrderNumber(form['settings.orderNumberPrefix'])}
            </p>
          </div>
        </div>
        <Alert tone="info">A new prefix is used only for orders created after you save. Existing orders keep their current numbers.</Alert>
      </Section>

      {/* Sticky on phones so Save is always in reach; a normal row on larger screens. */}
      <div className="fixed inset-x-0 bottom-0 z-20 border-t border-slate-200 bg-white/95 px-4 py-3 backdrop-blur sm:static sm:border-0 sm:bg-transparent sm:p-0">
        <div className="mx-auto flex max-w-3xl items-center justify-end gap-2">
          {changed && <span className="mr-auto text-sm text-amber-700">Unsaved changes</span>}
          {changed && (
            <Button
              variant="secondary"
              disabled={update.isPending}
              onClick={() => {
                setForm(formFromShop(saved))
                setErrors({})
                setMessage(null)
              }}
            >
              Discard
            </Button>
          )}
          <Button type="submit" loading={update.isPending} disabled={!changed}>
            Save settings
          </Button>
        </div>
      </div>

      <ConfirmDialog open={guard.pendingPath !== null} title="Leave without saving?" confirmLabel="Leave" danger onConfirm={guard.leave} onCancel={guard.stay}>
        You have changes that are not saved. If you leave this page, they are lost.
      </ConfirmDialog>
    </form>
  )
}

function Section({ title, description, children }: { title: string; description: string; children: ReactNode }) {
  return (
    <section className="space-y-4 rounded-xl border border-slate-200 bg-white p-4 sm:p-5">
      <div>
        <h2 className="font-semibold text-slate-900">{title}</h2>
        <p className="mt-1 text-sm text-slate-600">{description}</p>
      </div>
      {children}
    </section>
  )
}
