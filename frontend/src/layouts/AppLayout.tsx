import { useEffect, useState, type ReactNode } from 'react'
import { NavLink, Outlet } from 'react-router'
import { BoxIcon, CloseIcon, HomeIcon, LogoutIcon, MenuIcon, OrdersIcon, SettingsIcon, UsersIcon } from '../components/icons'
import { Spinner } from '../components/ui/Spinner'
import { useAuth } from '../features/auth/authContext'
import { usePermissions } from '../features/auth/usePermissions'
import type { Permission } from '../types/auth'
import { cn } from '../lib/cn'

interface NavItem {
  label: string
  to?: string // no `to` = not built yet
  icon: ReactNode
  /** Shown only to users with this permission. */
  permission?: Permission
}

const NAV: NavItem[] = [
  { label: 'Dashboard', to: '/dashboard', icon: <HomeIcon /> },
  { label: 'Orders', icon: <OrdersIcon /> },
  { label: 'Products', to: '/products', icon: <BoxIcon />, permission: 'PRODUCT_VIEW' },
  { label: 'Customers', icon: <UsersIcon /> },
  { label: 'Settings', icon: <SettingsIcon /> },
]

/**
 * Logged-in frame. Large screens: fixed sidebar on the left. Small screens: the sidebar becomes
 * a drawer opened with the menu button and closed by the backdrop, Escape, or clicking a link.
 */
export function AppLayout() {
  const [drawerOpen, setDrawerOpen] = useState(false)

  useEffect(() => {
    if (!drawerOpen) return
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && setDrawerOpen(false)
    document.addEventListener('keydown', onKey)
    document.body.style.overflow = 'hidden' // no background scrolling behind the drawer
    return () => {
      document.removeEventListener('keydown', onKey)
      document.body.style.overflow = ''
    }
  }, [drawerOpen])

  return (
    <div className="min-h-screen bg-slate-50 text-slate-900">
      {/* Desktop sidebar */}
      <aside className="fixed inset-y-0 left-0 hidden w-64 border-r border-slate-200 bg-white lg:block">
        <Sidebar />
      </aside>

      {/* Mobile drawer */}
      <div className={cn('fixed inset-0 z-40 lg:hidden', drawerOpen ? 'pointer-events-auto' : 'pointer-events-none')} aria-hidden={!drawerOpen}>
        <div
          className={cn('absolute inset-0 bg-slate-900/40 transition-opacity', drawerOpen ? 'opacity-100' : 'opacity-0')}
          onClick={() => setDrawerOpen(false)}
        />
        <aside
          className={cn(
            'absolute inset-y-0 left-0 w-72 max-w-[85%] bg-white shadow-xl transition-transform duration-200',
            drawerOpen ? 'translate-x-0' : '-translate-x-full',
          )}
          role="dialog"
          aria-modal="true"
          aria-label="Navigation"
        >
          <button
            type="button"
            onClick={() => setDrawerOpen(false)}
            className="absolute right-3 top-3 rounded-md p-2 text-slate-500 hover:bg-slate-100"
            aria-label="Close menu"
          >
            <CloseIcon />
          </button>
          <Sidebar onNavigate={() => setDrawerOpen(false)} />
        </aside>
      </div>

      <div className="lg:pl-64">
        <TopBar onOpenMenu={() => setDrawerOpen(true)} />
        <main className="mx-auto max-w-6xl px-4 py-6 sm:px-6 lg:px-8">
          <Outlet />
        </main>
      </div>
    </div>
  )
}

/** @param onNavigate called when a link is clicked (closes the mobile drawer) */
function Sidebar({ onNavigate }: { onNavigate?: () => void }) {
  const { shop } = useAuth()
  const { can } = usePermissions()
  const items = NAV.filter((item) => !item.permission || can(item.permission))

  return (
    <div className="flex h-full flex-col">
      <div className="border-b border-slate-200 px-5 py-4">
        <div className="text-lg font-semibold text-indigo-600">SellerDesk</div>
        {shop && <div className="truncate text-sm text-slate-500">{shop.name}</div>}
      </div>
      <nav className="flex-1 space-y-1 p-3" aria-label="Main">
        {items.map((item) =>
          item.to ? (
            <NavLink
              key={item.label}
              to={item.to}
              onClick={onNavigate}
              className={({ isActive }) =>
                cn(
                  'flex items-center gap-3 rounded-lg px-3 py-2.5 text-sm font-medium transition',
                  isActive ? 'bg-indigo-50 text-indigo-700' : 'text-slate-600 hover:bg-slate-100 hover:text-slate-900',
                )
              }
            >
              {item.icon}
              {item.label}
            </NavLink>
          ) : (
            <span key={item.label} className="flex cursor-not-allowed items-center gap-3 rounded-lg px-3 py-2.5 text-sm font-medium text-slate-400">
              {item.icon}
              {item.label}
              <span className="ml-auto rounded-full bg-slate-100 px-2 py-0.5 text-xs">Soon</span>
            </span>
          ),
        )}
      </nav>
    </div>
  )
}

function TopBar({ onOpenMenu }: { onOpenMenu: () => void }) {
  const { user, shop, logout } = useAuth()
  const [loggingOut, setLoggingOut] = useState(false)

  async function onLogout() {
    setLoggingOut(true)
    await logout() // navigates to /login; this component unmounts
  }

  const initials = (user?.name ?? '?')
    .split(/\s+/)
    .map((part) => part[0] ?? '')
    .join('')
    .slice(0, 2)
    .toUpperCase()

  return (
    <header className="sticky top-0 z-30 border-b border-slate-200 bg-white/90 backdrop-blur">
      <div className="flex h-16 items-center gap-3 px-4 sm:px-6 lg:px-8">
        <button type="button" onClick={onOpenMenu} className="-ml-2 rounded-md p-2 text-slate-600 hover:bg-slate-100 lg:hidden" aria-label="Open menu">
          <MenuIcon />
        </button>
        <div className="min-w-0 flex-1">
          <p className="truncate text-sm font-semibold text-slate-900">{shop?.name ?? 'SellerDesk'}</p>
        </div>
        <div className="flex items-center gap-3">
          <div className="hidden text-right sm:block">
            <p className="text-sm font-medium text-slate-900">{user?.name}</p>
            <p className="text-xs text-slate-500">{user?.role.replace('_', ' ').toLowerCase()}</p>
          </div>
          <span className="flex h-9 w-9 items-center justify-center rounded-full bg-indigo-100 text-sm font-semibold text-indigo-700" aria-hidden="true">
            {initials}
          </span>
          <button
            type="button"
            onClick={() => void onLogout()}
            disabled={loggingOut}
            className="inline-flex items-center gap-2 rounded-lg border border-slate-300 px-3 py-2 text-sm font-medium text-slate-700 hover:bg-slate-50 disabled:opacity-60"
          >
            {loggingOut ? <Spinner /> : <LogoutIcon />}
            <span className="hidden sm:inline">{loggingOut ? 'Logging out…' : 'Log out'}</span>
          </button>
        </div>
      </div>
    </header>
  )
}
