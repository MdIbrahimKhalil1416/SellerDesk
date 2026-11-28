import type { Page } from '../types/api'
import type {
  AddressRequest,
  CreateCustomerRequest,
  Customer,
  CustomerListParams,
  CustomerStatus,
  CustomerSummary,
  TagCount,
  UpdateCustomerRequest,
} from '../types/customer'
import { api } from './client'

const BASE = '/api/v1/customers'
const path = (id: string) => `${BASE}/${encodeURIComponent(id)}`
const addressPath = (id: string, addressId: string) => `${path(id)}/addresses/${encodeURIComponent(addressId)}`

/**
 * Thin wrappers around the customer endpoints (docs/API.md §6). No shopId anywhere: the backend
 * takes it from the token. Every write returns the whole, updated customer.
 */
export const customersApi = {
  async list(params: CustomerListParams): Promise<Page<CustomerSummary>> {
    const { data } = await api.get<Page<CustomerSummary>>(BASE, {
      params: Object.fromEntries(Object.entries(params).filter(([, v]) => v !== undefined && v !== '')),
    })
    return data
  },

  async tags(): Promise<TagCount[]> {
    const { data } = await api.get<TagCount[]>(`${BASE}/tags`)
    return data
  },

  /** Exact match on the main phone; rejects with 404 when there is none. */
  async lookup(phone: string): Promise<Customer> {
    const { data } = await api.get<Customer>(`${BASE}/lookup`, { params: { phone } })
    return data
  },

  async get(id: string): Promise<Customer> {
    const { data } = await api.get<Customer>(path(id))
    return data
  },

  async create(body: CreateCustomerRequest): Promise<Customer> {
    const { data } = await api.post<Customer>(BASE, body)
    return data
  },

  async update(id: string, body: UpdateCustomerRequest): Promise<Customer> {
    const { data } = await api.put<Customer>(path(id), body)
    return data
  },

  async changeStatus(id: string, status: CustomerStatus, version: number): Promise<Customer> {
    const { data } = await api.patch<Customer>(`${path(id)}/status`, { status, version })
    return data
  },

  async addAddress(id: string, body: AddressRequest): Promise<Customer> {
    const { data } = await api.post<Customer>(`${path(id)}/addresses`, body)
    return data
  },

  async updateAddress(id: string, addressId: string, body: AddressRequest): Promise<Customer> {
    const { data } = await api.put<Customer>(addressPath(id, addressId), body)
    return data
  },

  async removeAddress(id: string, addressId: string): Promise<Customer> {
    const { data } = await api.delete<Customer>(addressPath(id, addressId))
    return data
  },
}
