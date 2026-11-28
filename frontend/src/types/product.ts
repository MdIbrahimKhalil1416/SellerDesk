/**
 * Product DTOs, mirroring com.sellerdesk.product.dto (docs/modules/PRODUCT.md §5).
 * Money is always an integer number of POISHA (৳1 = 100 poisha). Convert with lib/money.ts.
 */

export type ProductStatus = 'ACTIVE' | 'ARCHIVED'

/** `status` filter of the list: ALL shows both. */
export type ProductStatusFilter = ProductStatus | 'ALL'

export type ProductSort =
  | 'updatedAt,desc'
  | 'name,asc'
  | 'name,desc'
  | 'createdAt,desc'
  | 'minPrice,asc'
  | 'minPrice,desc'

export interface ProductImage {
  id: string
  url: string
  width: number
  height: number
}

export interface VariantStock {
  onHand: number
  reserved: number
  available: number
}

export interface Variant {
  variantId: string
  sku: string
  name: string
  /** e.g. { Color: 'Red', Size: 'M' }; empty for a product without options. */
  attributes: Record<string, string>
  /** Poisha. */
  price: number
  /** Poisha. Missing without PRODUCT_EDIT / REPORT_VIEW. */
  costPrice?: number | null
  /** null = shop default. */
  lowStockThreshold: number | null
  active: boolean
  /** Missing without STOCK_VIEW. */
  stock?: VariantStock
}

export interface Product {
  id: string
  name: string
  description: string | null
  category: string | null
  status: ProductStatus
  /** First one is the cover. */
  images: ProductImage[]
  variants: Variant[]
  /** Send back on every change; a stale value gives 409 CONCURRENT_MODIFICATION. */
  version: number
  createdAt: string
  updatedAt: string
}

export interface ProductSummary {
  id: string
  name: string
  category: string | null
  status: ProductStatus
  variantCount: number
  /** Over active variants; both null when no variant is active. */
  priceRange: { min: number | null; max: number | null }
  /** Missing without STOCK_VIEW. */
  totalAvailable?: number
  coverImageUrl: string | null
  updatedAt: string
}

/**
 * GET /products/lookup: active products with their active variants, for the order form. No stock
 * and no cost price (the list is cached per shop and the same for every user).
 */
export interface ProductLookupItem {
  productId: string
  name: string
  coverImageUrl: string | null
  variants: Array<{ variantId: string; sku: string; name: string; price: number }>
}

export interface CategoryCount {
  name: string
  productCount: number
}

export interface ProductListParams {
  q?: string
  category?: string
  status: ProductStatusFilter
  sort: ProductSort
  /** 0-based */
  page: number
  size: number
}

// ---- Requests -------------------------------------------------------------------------

export interface VariantRequest {
  sku: string
  name?: string
  attributes?: Record<string, string>
  price: number
  costPrice?: number
  lowStockThreshold?: number
}

export interface CreateProductRequest {
  name: string
  description?: string
  category?: string
  variants: VariantRequest[]
}

export interface UpdateProductRequest {
  name: string
  description: string | null
  category: string | null
  version: number
}

export type AddVariantRequest = VariantRequest & { version: number }

/** Only the fields that changed; SKU and stock cannot be changed. */
export interface UpdateVariantRequest {
  name?: string
  attributes?: Record<string, string>
  price?: number
  costPrice?: number
  lowStockThreshold?: number
  active?: boolean
  version: number
}
