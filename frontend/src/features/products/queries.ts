import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useCallback } from 'react'
import { productsApi } from '../../api/products'
import type { CreateProductRequest, Product, ProductListParams, ProductStatus, UpdateProductRequest } from '../../types/product'

/** Cache keys. Everything under ['products'] belongs to this feature. */
export const productKeys = {
  all: ['products'] as const,
  lists: () => [...productKeys.all, 'list'] as const,
  list: (params: ProductListParams) => [...productKeys.lists(), params] as const,
  categories: () => [...productKeys.all, 'categories'] as const,
  detail: (id: string) => [...productKeys.all, 'detail', id] as const,
}

/** keepPreviousData: while the next page loads, the old one stays visible (no flicker). */
export function useProductList(params: ProductListParams) {
  return useQuery({
    queryKey: productKeys.list(params),
    queryFn: () => productsApi.list(params),
    placeholderData: keepPreviousData,
  })
}

export function useCategories() {
  return useQuery({ queryKey: productKeys.categories(), queryFn: () => productsApi.categories(), staleTime: 60_000 })
}

export function useProduct(id: string | undefined) {
  return useQuery({
    queryKey: productKeys.detail(id ?? ''),
    queryFn: () => productsApi.get(id ?? ''),
    enabled: !!id,
  })
}

/**
 * Every product write returns the whole, updated product. This puts it in the cache (so the
 * page shows it and the next request sends its new `version`) and marks lists as stale.
 */
export function useStoreProduct() {
  const queryClient = useQueryClient()
  return useCallback(
    (product: Product) => {
      queryClient.setQueryData(productKeys.detail(product.id), product)
      void queryClient.invalidateQueries({ queryKey: productKeys.lists() })
      void queryClient.invalidateQueries({ queryKey: productKeys.categories() })
    },
    [queryClient],
  )
}

/** The newest `version` we know of. Read right before a request, after earlier writes finished. */
export function useLatestVersion() {
  const queryClient = useQueryClient()
  return useCallback(
    (id: string) => queryClient.getQueryData<Product>(productKeys.detail(id))?.version ?? 0,
    [queryClient],
  )
}

export function useCreateProduct() {
  const store = useStoreProduct()
  return useMutation({ mutationFn: (body: CreateProductRequest) => productsApi.create(body), onSuccess: store })
}

export function useUpdateProduct(id: string) {
  const store = useStoreProduct()
  return useMutation({ mutationFn: (body: UpdateProductRequest) => productsApi.update(id, body), onSuccess: store })
}

export function useChangeProductStatus(id: string) {
  const store = useStoreProduct()
  const latestVersion = useLatestVersion()
  return useMutation({
    mutationFn: (status: ProductStatus) => productsApi.changeStatus(id, status, latestVersion(id)),
    onSuccess: store,
  })
}
