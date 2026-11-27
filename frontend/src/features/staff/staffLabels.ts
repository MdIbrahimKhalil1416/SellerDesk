import type { Role } from '../../types/auth'
import type { StaffRole, StaffRowStatus } from '../../types/staff'

export const ROLE_LABELS: Record<Role, string> = {
  PLATFORM_ADMIN: 'Platform admin',
  SHOP_OWNER: 'Owner',
  MODERATOR: 'Moderator',
  PACKER: 'Packer',
  ACCOUNTANT: 'Accountant',
}

/** Roles that can be given to staff, with a one-line hint for the invite form and drawer. */
export const STAFF_ROLES: Array<{ value: StaffRole; label: string; hint: string }> = [
  { value: 'MODERATOR', label: 'Moderator', hint: 'Creates, edits and confirms orders; manages customers.' },
  { value: 'PACKER', label: 'Packer', hint: 'Packs confirmed orders and hands them to the courier.' },
  { value: 'ACCOUNTANT', label: 'Accountant', hint: 'Sees orders and reports; marks orders delivered or returned.' },
]

export const STATUS_LABELS: Record<StaffRowStatus, string> = {
  ACTIVE: 'Active',
  DISABLED: 'Deactivated',
  INVITED: 'Invited',
  EXPIRED: 'Invite expired',
}

export function roleLabel(role: Role): string {
  return ROLE_LABELS[role] ?? role
}

/** "PRODUCT_EDIT" → "Product edit" (the description from the catalog explains it). */
export function permissionLabel(code: string): string {
  const text = code.replaceAll('_', ' ').toLowerCase()
  return text.charAt(0).toUpperCase() + text.slice(1)
}

const dateTime = new Intl.DateTimeFormat('en-GB', { day: 'numeric', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit' })
const dateOnly = new Intl.DateTimeFormat('en-GB', { day: 'numeric', month: 'short', year: 'numeric' })

export function formatDateTime(iso: string | null | undefined): string {
  return iso ? dateTime.format(new Date(iso)) : '—'
}

export function formatDate(iso: string | null | undefined): string {
  return iso ? dateOnly.format(new Date(iso)) : '—'
}

/** "5 min ago", "3 days ago"; older than 30 days: the date. */
export function timeAgo(iso: string | null | undefined, now = Date.now()): string {
  if (!iso) return 'Never'
  const seconds = Math.max(0, Math.round((now - new Date(iso).getTime()) / 1000))
  if (seconds < 60) return 'Just now'
  const minutes = Math.round(seconds / 60)
  if (minutes < 60) return `${minutes} min ago`
  const hours = Math.round(minutes / 60)
  if (hours < 24) return `${hours} h ago`
  const days = Math.round(hours / 24)
  if (days <= 30) return `${days} day${days === 1 ? '' : 's'} ago`
  return formatDate(iso)
}
