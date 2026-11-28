import { normalizeBdPhone } from '../../lib/bdPhone'
import { parseTakaToPoisha, poishaToTakaInput } from '../../lib/money'
import type { DeliveryZone, Shop, UpdateShopRequest } from '../../types/shop'

/**
 * The settings form. Every field is a string while the user types ("450.", "RS"), and each key
 * is the backend's field path ("address.line", "settings.orderNumberPrefix"). So a backend
 * VALIDATION_FAILED answer can be put next to the right input without any renaming.
 */
export const SHOP_FORM_FIELDS = [
  'name',
  'phone',
  'email',
  'facebookPageUrl',
  'address.line',
  'address.area',
  'address.district',
  'address.zone',
  'address.note',
  'settings.deliveryChargeInsideDhaka',
  'settings.deliveryChargeOutsideDhaka',
  'settings.defaultLowStockThreshold',
  'settings.orderNumberPrefix',
] as const

export type ShopFormField = (typeof SHOP_FORM_FIELDS)[number]
export type ShopForm = Record<ShopFormField, string>
export type ShopFormErrors = Partial<Record<ShopFormField, string>>

/** Backend limits (com.sellerdesk.shop.service.ShopRules). */
export const MAX_DELIVERY_CHARGE_POISHA = 500_000 // ৳5,000
export const MAX_LOW_STOCK_THRESHOLD = 100_000
export const ORDER_PREFIX_PATTERN = /^[A-Za-z0-9]{2,6}$/

export function isShopFormField(field: string): field is ShopFormField {
  return (SHOP_FORM_FIELDS as readonly string[]).includes(field)
}

export function formFromShop(shop: Shop): ShopForm {
  return {
    name: shop.name,
    phone: shop.phone,
    email: shop.email ?? '',
    facebookPageUrl: shop.facebookPageUrl ?? '',
    'address.line': shop.address?.line ?? '',
    'address.area': shop.address?.area ?? '',
    'address.district': shop.address?.district ?? '',
    'address.zone': shop.address?.zone ?? 'INSIDE_DHAKA',
    'address.note': shop.address?.note ?? '',
    'settings.deliveryChargeInsideDhaka': poishaToTakaInput(shop.settings.deliveryChargeInsideDhaka),
    'settings.deliveryChargeOutsideDhaka': poishaToTakaInput(shop.settings.deliveryChargeOutsideDhaka),
    'settings.defaultLowStockThreshold': String(shop.settings.defaultLowStockThreshold),
    'settings.orderNumberPrefix': shop.settings.orderNumberPrefix,
  }
}

/** The address is optional: all text fields empty means "no address". */
function hasAddress(form: ShopForm): boolean {
  return ['address.line', 'address.area', 'address.district', 'address.note'].some((f) => form[f as ShopFormField].trim() !== '')
}

/** Quick checks for instant feedback, same rules as the backend (docs/API.md §2). */
export function validateShopForm(form: ShopForm): ShopFormErrors {
  const e: ShopFormErrors = {}
  const name = form.name.trim()
  if (name.length < 2 || name.length > 100) e.name = 'Enter a shop name of 2 to 100 characters'

  if (!normalizeBdPhone(form.phone)) e.phone = 'Enter a Bangladeshi mobile number like 01712345678'

  const email = form.email.trim()
  if (email && (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email) || email.length > 254)) e.email = 'Enter a valid email address, or leave it empty'

  const fb = form.facebookPageUrl.trim()
  if (fb && (!/^https?:\/\/\S+$/.test(fb) || fb.length > 300)) e.facebookPageUrl = 'Enter a full link starting with https://, or leave it empty'

  if (hasAddress(form)) {
    if (!form['address.line'].trim()) e['address.line'] = 'Enter the street address, or clear all address fields'
    if (!form['address.district'].trim()) e['address.district'] = 'Enter the district, or clear all address fields'
  }

  for (const field of ['settings.deliveryChargeInsideDhaka', 'settings.deliveryChargeOutsideDhaka'] as const) {
    const poisha = parseTakaToPoisha(form[field])
    if (poisha === null) e[field] = 'Enter an amount in taka, e.g. 60 or 60.50'
    else if (poisha > MAX_DELIVERY_CHARGE_POISHA) e[field] = 'The maximum is ৳5,000'
  }

  const threshold = form['settings.defaultLowStockThreshold'].trim()
  if (!/^\d+$/.test(threshold) || Number(threshold) > MAX_LOW_STOCK_THRESHOLD) {
    e['settings.defaultLowStockThreshold'] = `Enter a whole number from 0 to ${MAX_LOW_STOCK_THRESHOLD.toLocaleString('en-IN')}`
  }

  if (!ORDER_PREFIX_PATTERN.test(form['settings.orderNumberPrefix'].trim())) {
    e['settings.orderNumberPrefix'] = 'Use 2 to 6 letters (A-Z) or digits, e.g. SD or RF24'
  }
  return e
}

/** Call only after validateShopForm returned no errors. */
export function toUpdateRequest(form: ShopForm): UpdateShopRequest {
  const orNull = (text: string) => text.trim() || null
  return {
    name: form.name.trim(),
    phone: normalizeBdPhone(form.phone) ?? form.phone.trim(),
    email: orNull(form.email),
    facebookPageUrl: orNull(form.facebookPageUrl),
    address: hasAddress(form)
      ? {
          line: form['address.line'].trim(),
          area: orNull(form['address.area']),
          district: form['address.district'].trim(),
          zone: form['address.zone'] as DeliveryZone,
          note: orNull(form['address.note']),
        }
      : null,
    settings: {
      deliveryChargeInsideDhaka: parseTakaToPoisha(form['settings.deliveryChargeInsideDhaka']) ?? 0,
      deliveryChargeOutsideDhaka: parseTakaToPoisha(form['settings.deliveryChargeOutsideDhaka']) ?? 0,
      defaultLowStockThreshold: Number(form['settings.defaultLowStockThreshold'].trim()),
      orderNumberPrefix: form['settings.orderNumberPrefix'].trim().toUpperCase(),
    },
  }
}

/**
 * What the form would mean to the backend, so "450" vs "450.00", "rs" vs "RS" or
 * "+8801712345678" vs "01712345678" do not count as changes. Invalid text is kept as typed.
 */
function comparable(form: ShopForm): Record<ShopFormField, string> {
  const result = {} as Record<ShopFormField, string>
  for (const field of SHOP_FORM_FIELDS) result[field] = form[field].trim()
  result.phone = normalizeBdPhone(form.phone) ?? result.phone
  for (const field of ['settings.deliveryChargeInsideDhaka', 'settings.deliveryChargeOutsideDhaka'] as const) {
    result[field] = String(parseTakaToPoisha(form[field]) ?? result[field])
  }
  result['settings.defaultLowStockThreshold'] = /^\d+$/.test(result['settings.defaultLowStockThreshold'])
    ? String(Number(result['settings.defaultLowStockThreshold']))
    : result['settings.defaultLowStockThreshold']
  result['settings.orderNumberPrefix'] = result['settings.orderNumberPrefix'].toUpperCase()
  // Without an address, the zone is not sent, so changing it alone is not a change.
  if (!hasAddress(form)) result['address.zone'] = ''
  return result
}

export function isFormChanged(form: ShopForm, saved: Shop): boolean {
  const a = comparable(form)
  const b = comparable(formFromShop(saved))
  return SHOP_FORM_FIELDS.some((field) => a[field] !== b[field])
}

/** "RS" → "RS-1042": the format orders are shown in (docs/SCHEMA.md, orders.orderNumberPrefix). */
export function exampleOrderNumber(prefix: string): string {
  const clean = prefix.trim().toUpperCase()
  return `${ORDER_PREFIX_PATTERN.test(clean) ? clean : 'SD'}-1042`
}
