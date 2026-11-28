import type { ShopStatus } from './auth'

/** Mirrors com.sellerdesk.common.model.DeliveryZone. Decides the default delivery charge. */
export type DeliveryZone = 'INSIDE_DHAKA' | 'OUTSIDE_DHAKA'

/** Mirrors com.sellerdesk.common.dto.AddressDto. */
export interface Address {
  line: string
  area: string | null
  district: string
  zone: DeliveryZone
  /** Landmark, e.g. "Opposite the mosque". */
  note: string | null
}

/** Money in POISHA (৳1 = 100 poisha). */
export interface ShopSettings {
  deliveryChargeInsideDhaka: number
  deliveryChargeOutsideDhaka: number
  /** Used for variants that have no threshold of their own. */
  defaultLowStockThreshold: number
  /** 2–6 uppercase letters/digits, e.g. "SD". */
  orderNumberPrefix: string
}

/** GET /shop, and the answer of every shop write (docs/API.md §2). */
export interface Shop {
  id: string
  name: string
  /** Always 01XXXXXXXXX. */
  phone: string
  email: string | null
  address: Address | null
  facebookPageUrl: string | null
  /** HTTPS URL, or null if the shop has no logo. */
  logoUrl: string | null
  status: ShopStatus
  settings: ShopSettings
}

/** PUT /shop. Full replace: an optional field sent as null is cleared. No shopId: always the user's shop. */
export interface UpdateShopRequest {
  name: string
  phone: string
  email: string | null
  address: Address | null
  facebookPageUrl: string | null
  settings: ShopSettings
}
