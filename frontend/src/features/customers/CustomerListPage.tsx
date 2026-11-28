import { useEffect, useState, type ReactNode } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router'
import { Can } from '../../components/Can'
import { PlusIcon, SearchIcon, UsersIcon } from '../../components/icons'
import { Alert } from '../../components/ui/Alert'
import { Button } from '../../components/ui/Button'
import { Pagination } from '../../components/ui/Pagination'
import { Select } from '../../components/ui/Select'
import { formatTaka } from '../../lib/money'
import { useDebouncedValue } from '../../lib/useDebouncedValue'
import type { CustomerListParams, CustomerSort, CustomerStatusFilter, CustomerSummary } from '../../types/customer'
import { CustomerStatusBadge, TagChips } from './components/CustomerStatusBadge'
import { customerErrorMessage } from './customerErrors'
import { useCustomerList, useCustomerTags } from './queries'

const PAGE_SIZE = 20

const SORTS: Array<{ value: CustomerSort; label: string }> = [
  { value: 'createdAt,desc', label: 'Newest first' },
  { value: 'name,asc', label: 'Name A–Z' },
  { value: 'lastOrderAt,desc', label: 'Recent orders first' },
]

const STATUSES: Array<{ value: CustomerStatusFilter; label: string }> = [
  { value: 'ACTIVE', label: 'Active customers' },
  { value: 'ARCHIVED', label: 'Archived' },
  { value: 'ALL', label: 'All customers' },
]

/**
 * /customers. Filters, sort and page live in the URL (?q=&tag=&status=&sort=&page=). Search: a
 * name prefix, or phone digits (start of the number, or its last 4+ digits).
 */
export function CustomerListPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const navigate = useNavigate()

  const params: CustomerListParams = {
    q: searchParams.get('q') ?? undefined,
    tag: searchParams.get('tag') ?? undefined,
    status: STATUSES.find((s) => s.value === searchParams.get('status'))?.value ?? 'ACTIVE',
    sort: SORTS.find((s) => s.value === searchParams.get('sort'))?.value ?? 'createdAt,desc',
    page: Math.max(0, Number(searchParams.get('page')) || 0),
    size: PAGE_SIZE,
  }
  const hasFilters = !!params.q || !!params.tag || params.status !== 'ACTIVE'

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

  const list = useCustomerList(params)
  const tags = useCustomerTags()

  function clearFilters() {
    setSearchText('')
    setSearchParams(new URLSearchParams(params.sort === 'createdAt,desc' ? {} : { sort: params.sort }), { replace: true })
  }

  const page = list.data

  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-2xl font-semibold text-slate-900">Customers</h1>
          {page && <p className="text-sm text-slate-500">{page.totalElements} customer{page.totalElements === 1 ? '' : 's'}</p>}
        </div>
        <Can permission="CUSTOMER_EDIT">
          <Button onClick={() => navigate('/customers/new')}>
            <PlusIcon /> Add customer
          </Button>
        </Can>
      </div>

      <div className="grid gap-3 rounded-xl border border-slate-200 bg-white p-3 sm:grid-cols-2 lg:grid-cols-[2fr_1fr_1fr_1fr]">
        <div className="relative sm:col-span-2 lg:col-span-1">
          <label htmlFor="customer-search" className="sr-only">
            Search customers
          </label>
          <span className="pointer-events-none absolute inset-y-0 left-3 flex items-center text-slate-400">
            <SearchIcon />
          </span>
          <input
            id="customer-search"
            type="search"
            inputMode="search"
            value={searchText}
            onChange={(e) => setSearchText(e.target.value)}
            placeholder="Name, phone or last 4 digits"
            className="block w-full rounded-lg border border-slate-300 bg-white py-2.5 pl-10 pr-3 text-sm outline-none focus:border-indigo-500 focus:ring-2 focus:ring-indigo-100"
          />
        </div>
        <Select
          label="Tag"
          hideLabel
          value={params.tag ?? ''}
          onChange={(e) => setParams({ tag: e.target.value })}
          options={[{ value: '', label: 'All tags' }, ...(tags.data ?? []).map((t) => ({ value: t.name, label: `${t.name} (${t.customerCount})` }))]}
        />
        <Select label="Status" hideLabel value={params.status} onChange={(e) => setParams({ status: e.target.value === 'ACTIVE' ? null : e.target.value })} options={STATUSES} />
        <Select label="Sort" hideLabel value={params.sort} onChange={(e) => setParams({ sort: e.target.value === 'createdAt,desc' ? null : e.target.value })} options={SORTS} />
      </div>

      {list.isPending ? (
        <ListSkeleton />
      ) : list.isError ? (
        <Alert tone="error">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <span>{customerErrorMessage(list.error, 'Could not load customers.')}</span>
            <Button variant="secondary" onClick={() => void list.refetch()}>
              Try again
            </Button>
          </div>
        </Alert>
      ) : page && page.content.length === 0 ? (
        hasFilters ? (
          <EmptyState title="No customers match" text="Try another name, phone number or filter.">
            <Button variant="secondary" onClick={clearFilters}>
              Clear filters
            </Button>
          </EmptyState>
        ) : (
          <EmptyState title="No customers yet" text="Customers are also added automatically when you take an order.">
            <Can permission="CUSTOMER_EDIT">
              <Button onClick={() => navigate('/customers/new')}>
                <PlusIcon /> Add customer
              </Button>
            </Can>
          </EmptyState>
        )
      ) : page ? (
        <div className={list.isFetching ? 'opacity-60 transition-opacity' : undefined} aria-busy={list.isFetching}>
          <CustomerTable customers={page.content} />
          <CustomerCards customers={page.content} />
          <Pagination page={page.page} totalPages={page.totalPages} loading={list.isFetching} onPage={(p) => setParams({ page: p === 0 ? null : String(p) }, false)} />
        </div>
      ) : null}
    </div>
  )
}

function place(c: CustomerSummary): string {
  if (!c.defaultAddress) return '—'
  return [c.defaultAddress.area, c.defaultAddress.district].filter(Boolean).join(', ')
}

function CustomerTable({ customers }: { customers: CustomerSummary[] }) {
  return (
    <div className="hidden overflow-x-auto rounded-xl border border-slate-200 bg-white md:block">
      <table className="w-full text-left text-sm">
        <thead className="border-b border-slate-200 bg-slate-50 text-xs uppercase tracking-wide text-slate-500">
          <tr>
            <th className="px-4 py-3 font-medium">Customer</th>
            <th className="px-4 py-3 font-medium">Phone</th>
            <th className="px-4 py-3 font-medium">Area</th>
            <th className="px-4 py-3 text-right font-medium">Orders</th>
            <th className="px-4 py-3 text-right font-medium">Spent</th>
            <th className="px-4 py-3 font-medium">Tags</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-slate-100">
          {customers.map((c) => (
            <tr key={c.id} className="hover:bg-slate-50">
              <td className="px-4 py-3">
                <Link to={`/customers/${c.id}`} className="flex items-center gap-2 font-medium text-slate-900 hover:text-indigo-600">
                  <span className="truncate">{c.name}</span>
                  <CustomerStatusBadge status={c.status} />
                </Link>
              </td>
              <td className="whitespace-nowrap px-4 py-3 tabular-nums text-slate-700">
                {c.phone}
                {c.altPhone && <span className="block text-xs text-slate-400">{c.altPhone}</span>}
              </td>
              <td className="px-4 py-3 text-slate-600">{place(c)}</td>
              <td className="px-4 py-3 text-right tabular-nums text-slate-700">
                {c.stats.totalOrders}
                {c.stats.returnedOrders > 0 && <span className="block text-xs text-amber-700">{c.stats.returnedOrders} returned</span>}
              </td>
              <td className="whitespace-nowrap px-4 py-3 text-right tabular-nums text-slate-700">{formatTaka(c.stats.totalSpent)}</td>
              <td className="px-4 py-3">
                <TagChips tags={c.tags} />
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function CustomerCards({ customers }: { customers: CustomerSummary[] }) {
  return (
    <ul className="space-y-2 md:hidden">
      {customers.map((c) => (
        <li key={c.id}>
          <Link to={`/customers/${c.id}`} className="block rounded-xl border border-slate-200 bg-white p-3 active:bg-slate-50">
            <div className="flex items-start justify-between gap-2">
              <p className="truncate font-medium text-slate-900">{c.name}</p>
              <CustomerStatusBadge status={c.status} />
            </div>
            <p className="text-sm tabular-nums text-slate-600">{c.phone}</p>
            <p className="mt-1 text-sm text-slate-500">
              {place(c)} · {c.stats.totalOrders} order{c.stats.totalOrders === 1 ? '' : 's'} · {formatTaka(c.stats.totalSpent)}
            </p>
            {c.tags.length > 0 && (
              <div className="mt-2">
                <TagChips tags={c.tags} />
              </div>
            )}
          </Link>
        </li>
      ))}
    </ul>
  )
}

function EmptyState({ title, text, children }: { title: string; text: string; children?: ReactNode }) {
  return (
    <div className="flex flex-col items-center rounded-xl border border-dashed border-slate-300 bg-white px-6 py-12 text-center">
      <span className="mb-3 rounded-full bg-indigo-50 p-3 text-indigo-600">
        <UsersIcon />
      </span>
      <h2 className="font-semibold text-slate-900">{title}</h2>
      <p className="mt-1 max-w-sm text-sm text-slate-500">{text}</p>
      {children && <div className="mt-4">{children}</div>}
    </div>
  )
}

function ListSkeleton() {
  return (
    <div className="space-y-2" role="status" aria-label="Loading customers">
      {Array.from({ length: 5 }, (_, i) => (
        <div key={i} className="animate-pulse space-y-2 rounded-xl border border-slate-200 bg-white p-3">
          <div className="h-3 w-1/3 rounded bg-slate-200" />
          <div className="h-3 w-1/4 rounded bg-slate-100" />
        </div>
      ))}
    </div>
  )
}
