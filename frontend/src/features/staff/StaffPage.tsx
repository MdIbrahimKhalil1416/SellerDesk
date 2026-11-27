import { useEffect, useState, type ReactNode } from 'react'
import { useSearchParams } from 'react-router'
import { Can } from '../../components/Can'
import { SearchIcon, UserPlusIcon } from '../../components/icons'
import { Alert } from '../../components/ui/Alert'
import { Button } from '../../components/ui/Button'
import { ConfirmDialog } from '../../components/ui/ConfirmDialog'
import { Pagination } from '../../components/ui/Pagination'
import { Select } from '../../components/ui/Select'
import { useDebouncedValue } from '../../lib/useDebouncedValue'
import type { Role } from '../../types/auth'
import type { StaffListParams, StaffRow, StaffRowStatus } from '../../types/staff'
import { useAuth } from '../auth/authContext'
import { InviteDialog } from './components/InviteDialog'
import { StaffDrawer } from './components/StaffDrawer'
import { StaffStatusBadge } from './components/StaffStatusBadge'
import { useCancelInvitation, useResendInvitation, useStaffList } from './queries'
import { staffErrorMessage } from './staffErrors'
import { formatDate, roleLabel, STATUS_LABELS, timeAgo } from './staffLabels'

const PAGE_SIZE = 20

const ROLE_FILTERS: Role[] = ['SHOP_OWNER', 'MODERATOR', 'PACKER', 'ACCOUNTANT']
const STATUS_FILTERS: StaffRowStatus[] = ['ACTIVE', 'DISABLED', 'INVITED', 'EXPIRED']

/** Actions a row can trigger. Members open the drawer; invitations are resent or cancelled in place. */
interface RowActions {
  onOpen: (row: StaffRow) => void
  onResend: (row: StaffRow) => void
  onCancel: (row: StaffRow) => void
  /** id of the invitation being resent right now, to show a spinner on that row only. */
  resendingId: string | null
  currentUserId: string | undefined
}

/**
 * /staff (needs STAFF_MANAGE). One table of members and pending invitations.
 * Search, filters and page live in the URL (?q=&role=&status=&page=), so reload and back/forward keep them.
 */
export function StaffPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const { user } = useAuth()

  const params: StaffListParams = {
    q: searchParams.get('q') ?? undefined,
    role: ROLE_FILTERS.find((r) => r === searchParams.get('role')),
    status: STATUS_FILTERS.find((s) => s === searchParams.get('status')),
    page: Math.max(0, Number(searchParams.get('page')) || 0),
    size: PAGE_SIZE,
  }
  const hasFilters = !!params.q || !!params.role || !!params.status

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

  function clearFilters() {
    setSearchText('')
    setSearchParams(new URLSearchParams(), { replace: true })
  }

  const list = useStaffList(params)
  const resend = useResendInvitation()
  const cancel = useCancelInvitation()

  const [inviteOpen, setInviteOpen] = useState(false)
  const [openMember, setOpenMember] = useState<StaffRow | null>(null)
  const [cancelRow, setCancelRow] = useState<StaffRow | null>(null)
  const [notice, setNotice] = useState<{ tone: 'success' | 'error'; text: string } | null>(null)

  function onResend(row: StaffRow) {
    setNotice(null)
    resend.mutate(row.id, {
      onSuccess: () => setNotice({ tone: 'success', text: `Invitation sent again to ${row.email}. The link in older emails no longer works.` }),
      onError: (err) => setNotice({ tone: 'error', text: staffErrorMessage(err, 'Could not resend the invitation.') }),
    })
  }

  function confirmCancel() {
    if (!cancelRow) return
    const row = cancelRow
    setNotice(null)
    cancel.mutate(row.id, {
      onSuccess: () => setNotice({ tone: 'success', text: `Invitation for ${row.email} cancelled. The link no longer works.` }),
      onError: (err) => setNotice({ tone: 'error', text: staffErrorMessage(err, 'Could not cancel the invitation.') }),
      onSettled: () => setCancelRow(null),
    })
  }

  const actions: RowActions = {
    onOpen: setOpenMember,
    onResend,
    onCancel: setCancelRow,
    resendingId: resend.isPending ? (resend.variables ?? null) : null,
    currentUserId: user?.id,
  }

  const page = list.data

  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-2xl font-semibold text-slate-900">Staff</h1>
          <p className="text-sm text-slate-500">People who can log in to your shop, and open invitations.</p>
        </div>
        <Can permission="STAFF_MANAGE">
          <Button onClick={() => setInviteOpen(true)}>
            <UserPlusIcon /> Invite staff
          </Button>
        </Can>
      </div>

      {notice && (
        <Alert tone={notice.tone}>
          <div className="flex items-start justify-between gap-3">
            <span>{notice.text}</span>
            <button type="button" onClick={() => setNotice(null)} className="text-xs font-semibold underline" aria-label="Dismiss message">
              Dismiss
            </button>
          </div>
        </Alert>
      )}

      {/* Filters */}
      <div className="grid gap-3 rounded-xl border border-slate-200 bg-white p-3 sm:grid-cols-2 lg:grid-cols-[2fr_1fr_1fr]">
        <div className="relative sm:col-span-2 lg:col-span-1">
          <label htmlFor="staff-search" className="sr-only">
            Search staff
          </label>
          <span className="pointer-events-none absolute inset-y-0 left-3 flex items-center text-slate-400">
            <SearchIcon />
          </span>
          <input
            id="staff-search"
            type="search"
            value={searchText}
            onChange={(e) => setSearchText(e.target.value)}
            placeholder="Search by name or email"
            maxLength={100}
            className="block w-full rounded-lg border border-slate-300 bg-white py-2.5 pl-10 pr-3 text-sm outline-none focus:border-indigo-500 focus:ring-2 focus:ring-indigo-100"
          />
        </div>
        <Select
          label="Role"
          hideLabel
          value={params.role ?? ''}
          onChange={(e) => setParams({ role: e.target.value })}
          options={[{ value: '', label: 'All roles' }, ...ROLE_FILTERS.map((r) => ({ value: r, label: roleLabel(r) }))]}
        />
        <Select
          label="Status"
          hideLabel
          value={params.status ?? ''}
          onChange={(e) => setParams({ status: e.target.value })}
          options={[{ value: '', label: 'All statuses' }, ...STATUS_FILTERS.map((s) => ({ value: s, label: STATUS_LABELS[s] }))]}
        />
      </div>

      {list.isPending ? (
        <ListSkeleton />
      ) : list.isError ? (
        <Alert tone="error">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <span>{staffErrorMessage(list.error, 'Could not load staff.')}</span>
            <Button variant="secondary" onClick={() => void list.refetch()}>
              Try again
            </Button>
          </div>
        </Alert>
      ) : page && page.content.length === 0 ? (
        hasFilters ? (
          <EmptyState title="Nobody matches" text="Try another search, role or status.">
            <Button variant="secondary" onClick={clearFilters}>
              Clear filters
            </Button>
          </EmptyState>
        ) : (
          <EmptyState title="No staff yet" text="Invite moderators, packers or an accountant so they can work with their own login.">
            <Can permission="STAFF_MANAGE">
              <Button onClick={() => setInviteOpen(true)}>
                <UserPlusIcon /> Invite staff
              </Button>
            </Can>
          </EmptyState>
        )
      ) : page ? (
        <div className={list.isFetching ? 'opacity-60 transition-opacity' : undefined} aria-busy={list.isFetching}>
          <p className="mb-2 text-sm text-slate-500">
            {page.totalElements} {page.totalElements === 1 ? 'person' : 'people'}
          </p>
          <StaffTable rows={page.content} actions={actions} />
          <StaffCards rows={page.content} actions={actions} />
          {!hasFilters && page.totalElements === 1 && (
            <p className="mt-3 rounded-lg bg-indigo-50 px-3 py-2.5 text-sm text-indigo-800">
              Only you so far. Invite moderators, packers or an accountant so each of them works with their own login.
            </p>
          )}
          <Pagination
            page={page.page}
            totalPages={page.totalPages}
            loading={list.isFetching}
            onPage={(p) => setParams({ page: p === 0 ? null : String(p) }, false)}
          />
        </div>
      ) : null}

      <InviteDialog
        open={inviteOpen}
        onClose={() => setInviteOpen(false)}
        onInvited={(email) => setNotice({ tone: 'success', text: `Invitation sent to ${email}. The link is valid for 7 days.` })}
      />

      <StaffDrawer memberId={openMember?.id ?? null} fallbackTitle={openMember?.name ?? undefined} onClose={() => setOpenMember(null)} />

      <ConfirmDialog
        open={!!cancelRow}
        title="Cancel this invitation?"
        confirmLabel="Cancel invitation"
        danger
        loading={cancel.isPending}
        onConfirm={confirmCancel}
        onCancel={() => setCancelRow(null)}
      >
        The link sent to <span className="font-medium text-slate-900">{cancelRow?.email}</span> stops working. You can invite them again later.
      </ConfirmDialog>
    </div>
  )
}

// ---- Rows ---------------------------------------------------------------------------------

function isInvitation(row: StaffRow) {
  return row.kind === 'INVITATION'
}

/** Name line: members show their name, invitations the email (they have no name yet). */
function PersonCell({ row, currentUserId }: { row: StaffRow; currentUserId: string | undefined }) {
  return (
    <div className="min-w-0">
      <p className="truncate font-medium text-slate-900">
        {row.name ?? row.email}
        {row.id === currentUserId && <span className="ml-1.5 text-xs font-normal text-slate-500">(you)</span>}
      </p>
      {row.name && <p className="truncate text-xs text-slate-500">{row.email}</p>}
      {isInvitation(row) && <p className="text-xs text-slate-500">Not joined yet</p>}
    </div>
  )
}

/** "Last login" for members; invitation dates for invitations. */
function ActivityText({ row }: { row: StaffRow }) {
  if (!isInvitation(row)) {
    return <span title={row.lastLoginAt ?? undefined}>{timeAgo(row.lastLoginAt)}</span>
  }
  return (
    <span className="block text-xs leading-5">
      Sent {timeAgo(row.lastSentAt)}
      <br />
      {row.status === 'EXPIRED' ? 'Expired' : 'Expires'} {formatDate(row.expiresAt)}
    </span>
  )
}

function RowButtons({ row, actions }: { row: StaffRow; actions: RowActions }) {
  if (isInvitation(row)) {
    return (
      <Can permission="STAFF_MANAGE">
        <div className="flex flex-wrap justify-end gap-2">
          <Button variant="secondary" className="px-3 py-1.5" loading={actions.resendingId === row.id} disabled={!!actions.resendingId} onClick={() => actions.onResend(row)}>
            Resend
          </Button>
          <Button variant="ghost" className="px-3 py-1.5 text-red-600 hover:bg-red-50" onClick={() => actions.onCancel(row)}>
            Cancel
          </Button>
        </div>
      </Can>
    )
  }
  // Owner and your own row open read-only; the drawer explains why.
  const readOnly = row.role === 'SHOP_OWNER' || row.id === actions.currentUserId
  return (
    <Button variant="ghost" className="px-3 py-1.5" onClick={() => actions.onOpen(row)}>
      {readOnly ? 'View' : 'Manage'}
    </Button>
  )
}

/** Desktop (md and up). */
function StaffTable({ rows, actions }: { rows: StaffRow[]; actions: RowActions }) {
  return (
    <div className="hidden overflow-hidden rounded-xl border border-slate-200 bg-white md:block">
      <table className="w-full text-left text-sm">
        <thead className="border-b border-slate-200 bg-slate-50 text-xs uppercase tracking-wide text-slate-500">
          <tr>
            <th className="px-4 py-3 font-medium">Name</th>
            <th className="px-4 py-3 font-medium">Role</th>
            <th className="px-4 py-3 font-medium">Status</th>
            <th className="px-4 py-3 font-medium">Last login</th>
            <th className="px-4 py-3 text-right font-medium">
              <span className="sr-only">Actions</span>
            </th>
          </tr>
        </thead>
        <tbody className="divide-y divide-slate-100">
          {rows.map((row) => (
            <tr key={`${row.kind}-${row.id}`} className="hover:bg-slate-50">
              <td className="max-w-xs px-4 py-3">
                <PersonCell row={row} currentUserId={actions.currentUserId} />
              </td>
              <td className="px-4 py-3 text-slate-700">{roleLabel(row.role)}</td>
              <td className="px-4 py-3">
                <StaffStatusBadge status={row.status} />
              </td>
              <td className="px-4 py-3 text-slate-600">
                <ActivityText row={row} />
              </td>
              <td className="px-4 py-3 text-right">
                <RowButtons row={row} actions={actions} />
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

/** Phones: one card per person. */
function StaffCards({ rows, actions }: { rows: StaffRow[]; actions: RowActions }) {
  return (
    <ul className="space-y-2 md:hidden">
      {rows.map((row) => (
        <li key={`${row.kind}-${row.id}`} className="rounded-xl border border-slate-200 bg-white p-3">
          <div className="flex items-start justify-between gap-2">
            <PersonCell row={row} currentUserId={actions.currentUserId} />
            <StaffStatusBadge status={row.status} />
          </div>
          <div className="mt-2 flex items-end justify-between gap-2">
            <div className="text-sm text-slate-600">
              <p className="font-medium text-slate-800">{roleLabel(row.role)}</p>
              {isInvitation(row) ? <ActivityText row={row} /> : <p className="text-xs">Last login: {timeAgo(row.lastLoginAt)}</p>}
            </div>
            <RowButtons row={row} actions={actions} />
          </div>
        </li>
      ))}
    </ul>
  )
}

function EmptyState({ title, text, children }: { title: string; text: string; children?: ReactNode }) {
  return (
    <div className="flex flex-col items-center rounded-xl border border-dashed border-slate-300 bg-white px-6 py-12 text-center">
      <span className="mb-3 rounded-full bg-indigo-50 p-3 text-indigo-600">
        <UserPlusIcon />
      </span>
      <h2 className="font-semibold text-slate-900">{title}</h2>
      <p className="mt-1 max-w-sm text-sm text-slate-500">{text}</p>
      {children && <div className="mt-4">{children}</div>}
    </div>
  )
}

function ListSkeleton() {
  return (
    <div className="space-y-2" role="status" aria-label="Loading staff">
      {Array.from({ length: 5 }, (_, i) => (
        <div key={i} className="flex animate-pulse items-center gap-3 rounded-xl border border-slate-200 bg-white p-3">
          <div className="h-10 w-10 rounded-full bg-slate-200" />
          <div className="flex-1 space-y-2">
            <div className="h-3 w-1/3 rounded bg-slate-200" />
            <div className="h-3 w-1/5 rounded bg-slate-100" />
          </div>
        </div>
      ))}
    </div>
  )
}
