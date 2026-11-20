import type { Page } from '../types/api'
import type {
  AddVariantRequest,
  CategoryCount,
  CreateProductRequest,
  Product,
  ProductListParams,
  ProductStatus,
  ProductSummary,
  UpdateProductRequest,
  UpdateVariantRequest,
} from '../types/product'
import { api } from './client'

const BASE = '/api/v1/products'
const path = (id: string) => `${BASE}/${encodeURIComponent(id)}`

/** Thin wrappers around the product endpoints (docs/API.md §4). Every change returns the whole product. */
export const productsApi = {
  async list(params: ProductListParams): Promise<Page<ProductSummary>> {
    const { data } = await api.get<Page<ProductSummary>>(BASE, {
      // Empty strings are left out, so the URL stays clean and the backend uses its defaults.
      params: Object.fromEntries(Object.entries(params).filter(([, v]) => v !== undefined && v !== '')),
    })
    return data
  },

  async categories(): Promise<CategoryCount[]> {
    const { data } = await api.get<CategoryCount[]>(`${BASE}/categories`)
    return data
  },

  async get(id: string): Promise<Product> {
    const { data } = await api.get<Product>(path(id))
    return data
  },

  async create(body: CreateProductRequest): Promise<Product> {
    const { data } = await api.post<Product>(BASE, body)
    return data
  },

  async update(id: string, body: UpdateProductRequest): Promise<Product> {
    const { data } = await api.put<Product>(path(id), body)
    return data
  },

  async changeStatus(id: string, status: ProductStatus, version: number): Promise<Product> {
    const { data } = await api.patch<Product>(`${path(id)}/status`, { status, version })
    return data
  },

  async addVariant(id: string, body: AddVariantRequest): Promise<Product> {
    const { data } = await api.post<Product>(`${path(id)}/variants`, body)
    return data
  },

  async updateVariant(id: string, variantId: string, body: UpdateVariantRequest): Promise<Product> {
    const { data } = await api.patch<Product>(`${path(id)}/variants/${encodeURIComponent(variantId)}`, body)
    return data
  },

  /**
   * Multipart upload of one image (field `file`).
   * - Content-Type multipart/form-data replaces the instance's JSON default; Axios then lets the
   *   browser add the boundary itself.
   * - Longer timeout: 5 MB over a slow mobile connection can take well over 20 s.
   */
  async uploadImage(id: string, file: File, onProgress?: (percent: number) => void): Promise<Product> {
    const form = new FormData()
    form.append('file', file)
    const { data } = await api.post<Product>(`${path(id)}/images`, form, {
      headers: { 'Content-Type': 'multipart/form-data' },
      timeout: 120_000,
      onUploadProgress: (event) => {
        if (event.total) onProgress?.(Math.round((event.loaded * 100) / event.total))
      },
    })
    return data
  },

  async deleteImage(id: string, imageId: string): Promise<Product> {
    const { data } = await api.delete<Product>(`${path(id)}/images/${encodeURIComponent(imageId)}`)
    return data
  },

  async reorderImages(id: string, imageIds: string[], version: number): Promise<Product> {
    const { data } = await api.put<Product>(`${path(id)}/images/order`, { imageIds, version })
    return data
  },
}
