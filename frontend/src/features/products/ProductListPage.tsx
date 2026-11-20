import { useEffect, useState, type ReactNode } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router'
import { Can } from '../../components/Can'
import { ArrowLeftIcon, ArrowRightIcon, BoxIcon, PlusIcon, SearchIcon } from '../../components/icons'
import { Alert } from '../../components/ui/Alert'
import { Button } from '../../components/ui/Button'
import { Select } from '../../components/ui/Select'
import { Spinner } from '../../components/ui/Spinner'
import { formatPriceRange } from '../../lib/money'
import { useDebouncedValue } from '../../lib/useDebouncedValue'
import type { ProductListParams, ProductSort, ProductStatusFilter, ProductSummary } from '../../types/product'
import { ProductStatusBadge } from './components/ProductStatusBadge'
import { ProductThumb } from './components/ProductThumb'
import { productErrorMessage } from './productErrors'
import { useCategories, useProductList } from './queries'

const PAGE_SIZE = 20

const SORTS: Array<{ value: ProductSort; label: string }> = [
  { value: 'updatedAt,desc', label: 'Recently updated' },
  { value: 'createdAt,desc', label: 'Newest first' },
  { value: 'name,asc', label: 'Name A–Z' },
  { value: 'name,desc', label: 'Name Z–A' },
  { value: 'minPrice,asc', label: 'Price: low to high' },
  { value: 'minPrice,desc', label: 'Price: high to low' },
]

const STATUSES: Array<{ value: ProductStatusFilter; label: string }> = [
  { value: 'ACTIVE', label: 'Active' },
  { value: 'ARCHIVED', label: 'Archived' },
  { value: 'ALL', label: 'All' },
]

/**
 * /products. Filters, sort and page live in the URL (?q=&category=&status=&sort=&page=), so a
 * reload, the back button or a shared link shows the same list.
 */
export function ProductListPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const navigate = useNavigate()

  const params: ProductListParams = {
    q: searchParams.get('q') ?? undefined,
    category: searchParams.get('category') ?? undefined,
    status: (STATUSES.find((s) => s.value === searchParams.get('status'))?.value ?? 'ACTIVE'),
    sort: (SORTS.find((s) => s.value === searchParams.get('sort'))?.value ?? 'updatedAt,desc'),
    page: Math.max(0, Number(searchParams.get('page')) || 0),
    size: PAGE_SIZE,
  }
  const hasFilters = !!params.q || !!params.category || params.status !== 'ACTIVE'

  /** Changes URL parameters; any filter change goes back to page 1. `replace` keeps history short. */
  function setParams(changes: Record<string, string | null>, resetPage = true) {
    setSearchParams(
      (current) => {
        const next = new URLSearchParams(current)
        for (const [key, value] of Object.entries(changes)) {
          if (value === null || value === '') next.delete(key)
          else next.set(key, value)
        }
        if (resetPage) next.delete('page')
        return next
      },
      { replace: true },
    )
  }

  // Search box: typing is local; the URL (and the request) follow 300 ms after the last key.
  const [searchText, setSearchText] = useState(params.q ?? '')
  const debouncedSearch = useDebouncedValue(searchText.trim(), 300)
  const urlQuery = params.q ?? ''
  useEffect(() => {
    if (debouncedSearch !== urlQuery) {
      setSearchParams(
        (current) => {
          const next = new URLSearchParams(current)
          if (debouncedSearch) next.set('q', debouncedSearch)
          else next.delete('q')
          next.delete('page')
          return next
        },
        { replace: true },
      )
    }
  }, [debouncedSearch, urlQuery, setSearchParams])

  const list = useProductList(params)
  const categories = useCategories()

  function clearFilters() {
    setSearchText('')
    setSearchParams(new URLSearchParams(params.sort === 'updatedAt,desc' ? {} : { sort: params.sort }), { replace: true })
  }

  const page = list.data

  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-2xl font-semibold text-slate-900">Products</h1>
          {page && <p className="text-sm text-slate-500">{page.totalElements} product{page.totalElements === 1 ? '' : 's'}</p>}
        </div>
        <Can permission="PRODUCT_EDIT">
          <Button onClick={() => navigate('/products/new')}>
            <PlusIcon /> Add product
          </Button>
        </Can>
      </div>

      {/* Filters */}
      <div className="grid gap-3 rounded-xl border border-slate-200 bg-white p-3 sm:grid-cols-2 lg:grid-cols-[2fr_1fr_1fr_1fr]">
        <div className="relative sm:col-span-2 lg:col-span-1">
          <label htmlFor="product-search" className="sr-only">
            Search products
          </label>
          <span className="pointer-events-none absolute inset-y-0 left-3 flex items-center text-slate-400">
            <SearchIcon />
          </span>
          <input
            id="product-search"
            type="search"
            value={searchText}
            onChange={(e) => setSearchText(e.target.value)}
            placeholder="Search by name or exact SKU"
            className="block w-full rounded-lg border border-slate-300 bg-white py-2.5 pl-10 pr-3 text-sm outline-none focus:border-indigo-500 focus:ring-2 focus:ring-indigo-100"
          />
        </div>
        <Select
          label="Category"
          hideLabel
          value={params.category ?? ''}
          onChange={(e) => setParams({ category: e.target.value })}
          options={[{ value: '', label: 'All categories' }, ...(categories.data ?? []).map((c) => ({ value: c.name, label: `${c.name} (${c.productCount})` }))]}
        />
        <Select
          label="Status"
          hideLabel
          value={params.status}
          onChange={(e) => setParams({ status: e.target.value === 'ACTIVE' ? null : e.target.value })}
          options={STATUSES.map((s) => ({ value: s.value, label: s.value === 'ALL' ? 'All statuses' : s.label }))}
        />
        <Select
          label="Sort"
          hideLabel
          value={params.sort}
          onChange={(e) => setParams({ sort: e.target.value === 'updatedAt,desc' ? null : e.target.value })}
          options={SORTS}
        />
      </div>

      {list.isPending ? (
        <ListSkeleton />
      ) : list.isError ? (
        <Alert tone="error">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <span>{productErrorMessage(list.error, 'Could not load products.')}</span>
            <Button variant="secondary" onClick={() => void list.refetch()}>
              Try again
            </Button>
          </div>
        </Alert>
      ) : page && page.content.length === 0 ? (
        hasFilters ? (
          <EmptyState title="No products match" text="Try another search or filter.">
            <Button variant="secondary" onClick={clearFilters}>
              Clear filters
            </Button>
          </EmptyState>
        ) : (
          <EmptyState title="No products yet" text="Add your first product with its sizes, colors and prices.">
            <Can permission="PRODUCT_EDIT">
              <Button onClick={() => navigate('/products/new')}>
                <PlusIcon /> Add product
              </Button>
            </Can>
          </EmptyState>
        )
      ) : page ? (
        <div className={list.isFetching ? 'opacity-60 transition-opacity' : undefined} aria-busy={list.isFetching}>
          <ProductTable products={page.content} />
          <ProductCards products={page.content} />
          <Pagination
            page={page.page}
            totalPages={page.totalPages}
            loading={list.isFetching}
            onPage={(p) => setParams({ page: p === 0 ? null : String(p) }, false)}
          />
        </div>
      ) : null}
    </div>
  )
}

/** Desktop. */
function ProductTable({ products }: { products: ProductSummary[] }) {
  return (
    <div className="hidden overflow-hidden rounded-xl border border-slate-200 bg-white md:block">
      <table className="w-full text-left text-sm">
        <thead className="border-b border-slate-200 bg-slate-50 text-xs uppercase tracking-wide text-slate-500">
          <tr>
            <th className="px-4 py-3 font-medium">Product</th>
            <th className="px-4 py-3 font-medium">Category</th>
            <th className="px-4 py-3 font-medium">Price</th>
            <th className="px-4 py-3 text-right font-medium">Variants</th>
            <th className="px-4 py-3 font-medium">Status</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-slate-100">
          {products.map((p) => (
            <tr key={p.id} className="hover:bg-slate-50">
              <td className="px-4 py-3">
                <Link to={`/products/${p.id}`} className="flex items-center gap-3 font-medium text-slate-900 hover:text-indigo-600">
                  <ProductThumb url={p.coverImageUrl} size={40} />
                  <span className="min-w-0 truncate">{p.name}</span>
                </Link>
              </td>
              <td className="px-4 py-3 text-slate-600">{p.category ?? <span className="text-slate-400">—</span>}</td>
              <td className="whitespace-nowrap px-4 py-3 text-slate-900">{formatPriceRange(p.priceRange.min, p.priceRange.max)}</td>
              <td className="px-4 py-3 text-right text-slate-600">
                {p.variantCount}
                {p.totalAvailable !== undefined && <span className="block text-xs text-slate-400">{p.totalAvailable} in stock</span>}
              </td>
              <td className="px-4 py-3">
                <ProductStatusBadge status={p.status} />
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

/** Phones: one card per product, the whole card is the link. */
function ProductCards({ products }: { products: ProductSummary[] }) {
  return (
    <ul className="space-y-2 md:hidden">
      {products.map((p) => (
        <li key={p.id}>
          <Link to={`/products/${p.id}`} className="flex gap-3 rounded-xl border border-slate-200 bg-white p-3 active:bg-slate-50">
            <ProductThumb url={p.coverImageUrl} size={64} />
            <div className="min-w-0 flex-1">
              <div className="flex items-start justify-between gap-2">
                <p className="truncate font-medium text-slate-900">{p.name}</p>
                <ProductStatusBadge status={p.status} />
              </div>
              <p className="truncate text-sm text-slate-500">{p.category ?? 'No category'}</p>
              <p className="mt-1 text-sm">
                <span className="font-medium text-slate-900">{formatPriceRange(p.priceRange.min, p.priceRange.max)}</span>
                <span className="text-slate-500">
                  {' · '}
                  {p.variantCount} variant{p.variantCount === 1 ? '' : 's'}
                </span>
              </p>
            </div>
          </Link>
        </li>
      ))}
    </ul>
  )
}

function Pagination({ page, totalPages, loading, onPage }: { page: number; totalPages: number; loading: boolean; onPage: (page: number) => void }) {
  if (totalPages <= 1) return null
  return (
    <nav className="mt-4 flex items-center justify-between gap-3" aria-label="Pagination">
      <Button variant="secondary" disabled={page === 0 || loading} onClick={() => onPage(page - 1)}>
        <ArrowLeftIcon /> Previous
      </Button>
      <span className="flex items-center gap-2 text-sm text-slate-600">
        {loading && <Spinner />}
        Page {page + 1} of {totalPages}
      </span>
      <Button variant="secondary" disabled={page + 1 >= totalPages || loading} onClick={() => onPage(page + 1)}>
        Next <ArrowRightIcon />
      </Button>
    </nav>
  )
}

function EmptyState({ title, text, children }: { title: string; text: string; children?: ReactNode }) {
  return (
    <div className="flex flex-col items-center rounded-xl border border-dashed border-slate-300 bg-white px-6 py-12 text-center">
      <span className="mb-3 rounded-full bg-indigo-50 p-3 text-indigo-600">
        <BoxIcon />
      </span>
      <h2 className="font-semibold text-slate-900">{title}</h2>
      <p className="mt-1 max-w-sm text-sm text-slate-500">{text}</p>
      {children && <div className="mt-4">{children}</div>}
    </div>
  )
}

function ListSkeleton() {
  return (
    <div className="space-y-2" role="status" aria-label="Loading products">
      {Array.from({ length: 5 }, (_, i) => (
        <div key={i} className="flex animate-pulse items-center gap-3 rounded-xl border border-slate-200 bg-white p-3">
          <div className="h-12 w-12 rounded-lg bg-slate-200" />
          <div className="flex-1 space-y-2">
            <div className="h-3 w-1/3 rounded bg-slate-200" />
            <div className="h-3 w-1/5 rounded bg-slate-100" />
          </div>
        </div>
      ))}
    </div>
  )
}
