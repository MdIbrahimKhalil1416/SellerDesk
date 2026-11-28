import type { Permission } from '../../types/auth'
import type { OrderAction, OrderSource, OrderStatus } from '../../types/order'

export const STATUS_LABELS: Record<OrderStatus, string> = {
  PENDING: 'Pending',
  CONFIRMED: 'Confirmed',
  PACKED: 'Packed',
  SHIPPED: 'Shipped',
  DELIVERED: 'Delivered',
  CANCELLED: 'Cancelled',
  RETURNED: 'Returned',
}

/** Pipeline order, for the status tabs. */
export const STATUSES: OrderStatus[] = ['PENDING', 'CONFIRMED', 'PACKED', 'SHIPPED', 'DELIVERED', 'CANCELLED', 'RETURNED']

export const STATUS_STYLES: Record<OrderStatus, string> = {
  PENDING: 'bg-amber-50 text-amber-800 ring-amber-200',
  CONFIRMED: 'bg-sky-50 text-sky-800 ring-sky-200',
  PACKED: 'bg-indigo-50 text-indigo-800 ring-indigo-200',
  SHIPPED: 'bg-violet-50 text-violet-800 ring-violet-200',
  DELIVERED: 'bg-emerald-50 text-emerald-800 ring-emerald-200',
  CANCELLED: 'bg-slate-100 text-slate-600 ring-slate-200',
  RETURNED: 'bg-red-50 text-red-800 ring-red-200',
}

export const SOURCE_LABELS: Record<OrderSource, string> = {
  FACEBOOK: 'Facebook',
  MESSENGER: 'Messenger',
  WHATSAPP: 'WhatsApp',
  PHONE: 'Phone',
  OTHER: 'Other',
}

export const SOURCE_OPTIONS = (Object.keys(SOURCE_LABELS) as OrderSource[]).map((value) => ({ value, label: SOURCE_LABELS[value] }))

export const ACTION_LABELS: Record<OrderAction, string> = {
  CONFIRM: 'Confirm',
  PACK: 'Mark packed',
  SHIP: 'Mark shipped',
  DELIVER: 'Mark delivered',
  RETURN: 'Mark returned',
  CANCEL: 'Cancel order',
}

/**
 * The status machine, mirrored from the backend's OrderAction (docs/modules/ORDER.md §4).
 *
 * The order detail page does NOT use this: it shows the `allowedActions` the server computed for
 * this user and this order. Only the list's bulk bar uses it, to offer actions that fit the
 * selected rows before asking the server. The server checks every order again.
 */
export const ACTION_RULES: Record<OrderAction, { from: OrderStatus[]; permission: Permission }> = {
  CONFIRM: { from: ['PENDING'], permission: 'ORDER_CONFIRM' },
  PACK: { from: ['CONFIRMED'], permission: 'ORDER_FULFILL' },
  SHIP: { from: ['PACKED'], permission: 'ORDER_FULFILL' },
  DELIVER: { from: ['SHIPPED'], permission: 'ORDER_CLOSE' },
  RETURN: { from: ['SHIPPED'], permission: 'ORDER_CLOSE' },
  CANCEL: { from: ['PENDING', 'CONFIRMED', 'PACKED'], permission: 'ORDER_CANCEL' },
}

/** Actions that can be done for many orders at once (RETURN needs a restock choice per item). */
export const BULK_ACTIONS = ['CONFIRM', 'PACK', 'SHIP', 'DELIVER', 'CANCEL'] as const
export type BulkAction = (typeof BULK_ACTIONS)[number]

const dateTime = new Intl.DateTimeFormat('en-GB', { timeZone: 'Asia/Dhaka', day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' })
const dateOnly = new Intl.DateTimeFormat('en-GB', { timeZone: 'Asia/Dhaka', day: 'numeric', month: 'short', year: 'numeric' })

/** "28 Nov, 14:05" in Dhaka time. */
export function formatDateTime(iso: string): string {
  return dateTime.format(new Date(iso))
}

export function formatDate(iso: string): string {
  return dateOnly.format(new Date(iso))
}
