import type { Shop, UpdateShopRequest } from '../types/shop'
import { api } from './client'

const BASE = '/api/v1/shop'

/**
 * Thin wrappers around the shop endpoints (docs/API.md §2). No shopId anywhere: the backend
 * always uses the logged-in user's shop. Every write returns the whole, updated shop.
 */
export const shopApi = {
  async get(): Promise<Shop> {
    const { data } = await api.get<Shop>(BASE)
    return data
  },

  async update(body: UpdateShopRequest): Promise<Shop> {
    const { data } = await api.put<Shop>(BASE, body)
    return data
  },

  /** Multipart upload (field `file`), same rules as product images. Longer timeout for slow mobile data. */
  async uploadLogo(file: File, onProgress?: (percent: number) => void): Promise<Shop> {
    const form = new FormData()
    form.append('file', file)
    const { data } = await api.put<Shop>(`${BASE}/logo`, form, {
      headers: { 'Content-Type': 'multipart/form-data' },
      timeout: 120_000,
      onUploadProgress: (event) => {
        if (event.total) onProgress?.(Math.round((event.loaded * 100) / event.total))
      },
    })
    return data
  },

  async deleteLogo(): Promise<Shop> {
    const { data } = await api.delete<Shop>(`${BASE}/logo`)
    return data
  },
}
