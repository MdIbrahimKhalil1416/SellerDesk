import type { DeliveryZone } from './shop'

/** Mirrors com.sellerdesk.customer.model.CustomerStatus. Customers are archived, never deleted. */
export type CustomerStatus = 'ACTIVE' | 'ARCHIVED'

export type CustomerStatusFilter = CustomerStatus | 'ALL'

export type CustomerSort = 'createdAt,desc' | 'name,asc' | 'lastOrderAt,desc'

export interface CustomerAddress {
  addressId: string
  label: string | null
  /** Full address (house, road...). */
  line: string
  /** Thana / upazila. */
  area: string | null
  district: string
  zone: DeliveryZone
  /** Landmark. */
  note: string | null
  isDefault: boolean
}

/** Order counters, kept up to date by the Order module. Money in POISHA. */
export interface CustomerStats {
  totalOrders: number
  deliveredOrders: number
  returnedOrders: number
  cancelledOrders: number
  totalSpent: number
  lastOrderAt: string | null
}

/** GET /customers/{id} (docs/API.md §6). Phones are always 01XXXXXXXXX. */
export interface Customer {
  id: string
  name: string
  phone: string
  altPhone: string | null
  facebookProfileUrl: string | null
  addresses: CustomerAddress[]
  note: string | null
  tags: string[]
  status: CustomerStatus
  stats: CustomerStats
  version: number
  createdAt: string
  updatedAt: string
}

export interface CustomerSummary {
  id: string
  name: string
  phone: string
  altPhone: string | null
  defaultAddress: { district: string; area: string | null; zone: DeliveryZone } | null
  tags: string[]
  status: CustomerStatus
  stats: CustomerStats
  createdAt: string
}

export interface TagCount {
  name: string
  customerCount: number
}

export interface CustomerListParams {
  q?: string
  tag?: string
  status: CustomerStatusFilter
  sort: CustomerSort
  page: number
  size: number
}

/** POST/PUT .../addresses. `isDefault: true` moves the default mark; false keeps the current one. */
export interface AddressRequest {
  label: string | null
  line: string
  area: string | null
  district: string
  zone: DeliveryZone
  note: string | null
  isDefault: boolean
}

/** POST /customers. No shopId, no stats: the server sets them. */
export interface CreateCustomerRequest {
  name: string
  phone: string
  altPhone: string | null
  facebookProfileUrl: string | null
  note: string | null
  tags: string[]
  addresses: AddressRequest[]
}

/** PUT /customers/{id}: profile only. Optional fields left out (null) are cleared. */
export interface UpdateCustomerRequest {
  name: string
  phone: string
  altPhone: string | null
  facebookProfileUrl: string | null
  note: string | null
  tags: string[]
  version: number
}
