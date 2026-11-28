import { normalizeBdPhone } from '../../lib/bdPhone'
import type { AddressRequest, CustomerAddress } from '../../types/customer'
import type { DeliveryZone } from '../../types/shop'

/** Backend limits (docs/modules/CUSTOMER.md §2). */
export const MAX_ADDRESSES = 5
export const MAX_TAGS = 10
export const MAX_TAG = 30

/** Profile form: strings while typing; keys = backend field names, so fieldErrorsOf() fits. */
export interface ProfileForm {
  name: string
  phone: string
  altPhone: string
  facebookProfileUrl: string
  note: string
  /** Comma-separated while typing: "VIP, Wholesale". */
  tags: string
}

export type ProfileErrors = Partial<Record<keyof ProfileForm, string>>

export const EMPTY_PROFILE: ProfileForm = { name: '', phone: '', altPhone: '', facebookProfileUrl: '', note: '', tags: '' }

export function splitTags(text: string): string[] {
  const seen = new Set<string>()
  const tags: string[] = []
  for (const raw of text.split(',')) {
    const tag = raw.trim().replace(/\s+/g, ' ')
    if (tag && !seen.has(tag.toLowerCase())) {
      seen.add(tag.toLowerCase())
      tags.push(tag)
    }
  }
  return tags
}

/** Quick checks for instant feedback; the backend validates again (same rules). */
export function validateProfile(form: ProfileForm): ProfileErrors {
  const e: ProfileErrors = {}
  const name = form.name.trim()
  if (!name || name.length > 100) e.name = 'Enter a name of 1 to 100 characters'
  const phone = normalizeBdPhone(form.phone)
  if (!phone) e.phone = 'Enter a Bangladeshi mobile number like 01712345678'
  if (form.altPhone.trim()) {
    const alt = normalizeBdPhone(form.altPhone)
    if (!alt) e.altPhone = 'Enter a Bangladeshi mobile number, or leave it empty'
    else if (alt === phone) e.altPhone = 'Must be different from the main phone'
  }
  const fb = form.facebookProfileUrl.trim()
  if (fb && (!/^https?:\/\/\S+$/.test(fb) || fb.length > 300)) e.facebookProfileUrl = 'Enter a full link starting with https://, or leave it empty'
  if (form.note.length > 1000) e.note = 'At most 1000 characters'
  const tags = splitTags(form.tags)
  if (tags.length > MAX_TAGS) e.tags = `At most ${MAX_TAGS} tags`
  else if (tags.some((t) => t.length > MAX_TAG)) e.tags = `Each tag can have at most ${MAX_TAG} characters`
  return e
}

/** The profile part of a create / update request. Call after validateProfile returned no errors. */
export function profileRequest(form: ProfileForm) {
  const orNull = (s: string) => s.trim() || null
  return {
    name: form.name.trim().replace(/\s+/g, ' '),
    phone: normalizeBdPhone(form.phone) ?? form.phone.trim(),
    altPhone: form.altPhone.trim() ? (normalizeBdPhone(form.altPhone) ?? form.altPhone.trim()) : null,
    facebookProfileUrl: orNull(form.facebookProfileUrl),
    note: orNull(form.note),
    tags: splitTags(form.tags),
  }
}

/**
 * Backend field errors → profile form keys. Tag errors come as "tags[2]"; the form has one tags
 * input, so they are shown there.
 */
export function profileFieldErrors(fieldErrors: Record<string, string>): ProfileErrors {
  const e: ProfileErrors = {}
  for (const [field, message] of Object.entries(fieldErrors)) {
    const key = field.startsWith('tags') ? 'tags' : field
    if (key in EMPTY_PROFILE) e[key as keyof ProfileForm] = message
  }
  return e
}

// ==== Address =============================================================================

export interface AddressForm {
  label: string
  line: string
  area: string
  district: string
  zone: DeliveryZone
  /** False until the user picks a zone: until then it follows the district. */
  zoneTouched: boolean
  note: string
  isDefault: boolean
}

export type AddressErrors = Partial<Record<'label' | 'line' | 'area' | 'district' | 'zone' | 'note', string>>

export const EMPTY_ADDRESS: AddressForm = {
  label: '',
  line: '',
  area: '',
  district: '',
  zone: 'OUTSIDE_DHAKA',
  zoneTouched: false,
  note: '',
  isDefault: false,
}

/**
 * Suggested delivery zone: Dhaka district → inside Dhaka, else outside. Only a suggestion: some
 * areas of Dhaka district (e.g. Savar) count as "outside" for couriers, so the user can change it.
 */
export function suggestZone(district: string): DeliveryZone {
  return district.trim().toLowerCase() === 'dhaka' ? 'INSIDE_DHAKA' : 'OUTSIDE_DHAKA'
}

export function addressFormOf(a: CustomerAddress): AddressForm {
  return {
    label: a.label ?? '',
    line: a.line,
    area: a.area ?? '',
    district: a.district,
    zone: a.zone,
    zoneTouched: true,
    note: a.note ?? '',
    isDefault: a.isDefault,
  }
}

export function validateAddress(form: AddressForm): AddressErrors {
  const e: AddressErrors = {}
  if (!form.line.trim()) e.line = 'Enter the full address'
  else if (form.line.trim().length > 200) e.line = 'At most 200 characters'
  if (!form.district.trim()) e.district = 'Enter the district'
  else if (form.district.trim().length > 50) e.district = 'At most 50 characters'
  if (form.area.trim().length > 100) e.area = 'At most 100 characters'
  if (form.label.trim().length > 30) e.label = 'At most 30 characters'
  if (form.note.trim().length > 200) e.note = 'At most 200 characters'
  return e
}

/** True if the user typed anything (an untouched address block on the create form is skipped). */
export function isAddressStarted(form: AddressForm): boolean {
  return [form.label, form.line, form.area, form.district, form.note].some((v) => v.trim() !== '')
}

export function addressRequest(form: AddressForm): AddressRequest {
  const orNull = (s: string) => s.trim() || null
  return {
    label: orNull(form.label),
    line: form.line.trim(),
    area: orNull(form.area),
    district: form.district.trim(),
    zone: form.zone,
    note: orNull(form.note),
    isDefault: form.isDefault,
  }
}

/** "addresses[0].line" → "line" (create form), "line" stays "line" (address dialog). */
export function addressFieldErrors(fieldErrors: Record<string, string>, prefix = ''): AddressErrors {
  const e: AddressErrors = {}
  for (const [field, message] of Object.entries(fieldErrors)) {
    if (!field.startsWith(prefix)) continue
    const key = field.slice(prefix.length)
    if (['label', 'line', 'area', 'district', 'zone', 'note'].includes(key)) e[key as keyof AddressErrors] = message
  }
  return e
}

export const ZONE_OPTIONS: Array<{ value: DeliveryZone; label: string }> = [
  { value: 'INSIDE_DHAKA', label: 'Inside Dhaka' },
  { value: 'OUTSIDE_DHAKA', label: 'Outside Dhaka' },
]

export const ZONE_LABELS: Record<DeliveryZone, string> = { INSIDE_DHAKA: 'Inside Dhaka', OUTSIDE_DHAKA: 'Outside Dhaka' }
