import { Navigate, Outlet, useLocation } from 'react-router'
import { FullPageSpinner } from '../components/FullPageSpinner'
import { useAuth } from '../features/auth/authContext'

/** Login, register...: a logged-in user is sent back to where they came from (or home). */
export function PublicOnlyRoute() {
  const { user, isLoading } = useAuth()
  const location = useLocation()

  if (isLoading) {
    return <FullPageSpinner />
  }
  if (user) {
    const from = (location.state as { from?: string } | null)?.from
    return <Navigate to={from && from !== '/login' ? from : '/dashboard'} replace />
  }
  return <Outlet />
}
