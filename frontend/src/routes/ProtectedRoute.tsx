import { Navigate, Outlet, useLocation } from 'react-router'
import { FullPageSpinner } from '../components/FullPageSpinner'
import { useAuth } from '../features/auth/authContext'

/** Children render only for logged-in users. Others go to /login and come back afterwards. */
export function ProtectedRoute() {
  const { user, isLoading } = useAuth()
  const location = useLocation()

  if (isLoading) {
    return <FullPageSpinner />
  }
  if (!user) {
    return <Navigate to="/login" replace state={{ from: location.pathname + location.search }} />
  }
  return <Outlet />
}
