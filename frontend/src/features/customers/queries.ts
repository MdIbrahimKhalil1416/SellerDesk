import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useCallback } from 'react'
import { customersApi } from '../../api/customers'
import type { AddressRequest, CreateCustomerRequest, Customer, CustomerListParams, CustomerStatus, UpdateCustomerRequest } from '../../types/customer'

/** Cache keys. Everything under ['customers'] belongs to this feature. */
export const customerKeys = {
  all: ['customers'] as const,
  lists: () => [...customerKeys.all, 'list'] as const,
  list: (params: CustomerListParams) => [...customerKeys.lists(), params] as const,
  tags: () => [...customerKeys.all, 'tags'] as const,
  detail: (id: string) => [...customerKeys.all, 'detail', id] as const,
}

export function useCustomerList(params: CustomerListParams) {
  return useQuery({ queryKey: customerKeys.list(params), queryFn: () => customersApi.list(params), placeholderData: keepPreviousData })
}

export function useCustomerTags() {
  return useQuery({ queryKey: customerKeys.tags(), queryFn: () => customersApi.tags(), staleTime: 60_000 })
}

export function useCustomer(id: string | undefined) {
  return useQuery({ queryKey: customerKeys.detail(id ?? ''), queryFn: () => customersApi.get(id ?? ''), enabled: !!id })
}

/** Every write returns the whole customer: put it in the cache, mark lists and tags as stale. */
export function useStoreCustomer() {
  const queryClient = useQueryClient()
  return useCallback(
    (customer: Customer) => {
      queryClient.setQueryData(customerKeys.detail(customer.id), customer)
      void queryClient.invalidateQueries({ queryKey: customerKeys.lists() })
      void queryClient.invalidateQueries({ queryKey: customerKeys.tags() })
    },
    [queryClient],
  )
}

export function useCreateCustomer() {
  const store = useStoreCustomer()
  return useMutation({ mutationFn: (body: CreateCustomerRequest) => customersApi.create(body), onSuccess: store })
}

export function useUpdateCustomer(id: string) {
  const store = useStoreCustomer()
  return useMutation({ mutationFn: (body: UpdateCustomerRequest) => customersApi.update(id, body), onSuccess: store })
}

export function useChangeCustomerStatus(id: string) {
  const store = useStoreCustomer()
  return useMutation({
    mutationFn: ({ status, version }: { status: CustomerStatus; version: number }) => customersApi.changeStatus(id, status, version),
    onSuccess: store,
  })
}

/** Add (addressId undefined) or replace an address. */
export function useSaveAddress(id: string) {
  const store = useStoreCustomer()
  return useMutation({
    mutationFn: ({ addressId, body }: { addressId?: string; body: AddressRequest }) =>
      addressId ? customersApi.updateAddress(id, addressId, body) : customersApi.addAddress(id, body),
    onSuccess: store,
  })
}

export function useRemoveAddress(id: string) {
  const store = useStoreCustomer()
  return useMutation({ mutationFn: (addressId: string) => customersApi.removeAddress(id, addressId), onSuccess: store })
}
