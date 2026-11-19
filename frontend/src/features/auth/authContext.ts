import { createContext, useContext } from 'react'
import type { ShopSummary, User } from '../../types/auth'

export interface AuthState {
  /** Logged-in user, or null. */
  user: User | null
  /** The user's shop; null when logged out, and for PLATFORM_ADMIN. */
  shop: ShopSummary | null
  /** True only while the app restores the session on start. Render a spinner, not the login page. */
  isLoading: boolean
  /** Throws the Axios error on failure; read it with getApiError / fieldErrorsOf. */
  login: (email: string, password: string) => Promise<void>
  /**
   * Starts a session from an access token the backend just issued (e.g. after email
   * verification): keeps it in memory and loads user + shop from /auth/me.
   */
  startSession: (accessToken: string) => Promise<void>
  /** Logs out on the server (all devices), clears local state and goes to /login. */
  logout: () => Promise<void>
}

export const AuthContext = createContext<AuthState | null>(null)

export function useAuth(): AuthState {
  const value = useContext(AuthContext)
  if (!value) {
    throw new Error('useAuth must be used inside <AuthProvider>')
  }
  return value
}
