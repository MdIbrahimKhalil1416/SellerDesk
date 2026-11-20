import { Link, Outlet } from 'react-router'
import { usePermissions } from '../features/auth/usePermissions'
import type { Permission } from '../types/auth'

/**
 * Route guard: child routes render only with `permission`, otherwise a friendly "no access" page.
 * Must be inside ProtectedRoute (it assumes a logged-in user).
 */
export function RequirePermission({ permission }: { permission: Permission }) {
  const { can } = usePermissions()
  if (can(permission)) return <Outlet />

  return (
    <div className="mx-auto max-w-md rounded-xl border border-slate-200 bg-white p-6 text-center">
      <h1 className="text-lg font-semibold text-slate-900">No access</h1>
      <p className="mt-2 text-sm text-slate-600">You do not have permission to open this page. Ask the shop owner if you need it.</p>
      <Link to="/dashboard" className="mt-4 inline-block text-sm font-semibold text-indigo-600 hover:text-indigo-500">
        Back to dashboard
      </Link>
    </div>
  )
}
