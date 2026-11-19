import { useQueryClient } from '@tanstack/react-query'
import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react'
import { useNavigate } from 'react-router'
import { authApi } from '../../api/auth'
import { refreshAccessToken, setSessionExpiredHandler } from '../../api/client'
import { clearAccessToken, setAccessToken } from '../../api/tokenStore'
import type { ShopSummary, User } from '../../types/auth'
import { AuthContext, type AuthState } from './authContext'

/**
 * Owns "who is logged in". Must be inside the router (it navigates) and the QueryClientProvider
 * (it clears cached data on logout, so the next user never sees the previous user's data).
 */
export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null)
  const [shop, setShop] = useState<ShopSummary | null>(null)
  const [isLoading, setIsLoading] = useState(true)
  const navigate = useNavigate()
  const queryClient = useQueryClient()

  const clearSession = useCallback(() => {
    clearAccessToken()
    setUser(null)
    setShop(null)
    queryClient.clear()
  }, [queryClient])

  // Refresh failed in the middle of using the app (cookie expired, logged out elsewhere...).
  useEffect(() => {
    setSessionExpiredHandler(() => {
      clearSession()
      navigate('/login', { replace: true })
    })
    return () => setSessionExpiredHandler(null)
  }, [clearSession, navigate])

  // App start / page reload: the token in memory is gone, but the HttpOnly cookie may still be
  // there. Try a refresh; if it works, load the user and shop.
  useEffect(() => {
    let cancelled = false
    async function restoreSession() {
      try {
        const token = await refreshAccessToken() // shared single-flight call
        if (token && !cancelled) {
          const me = await authApi.me()
          if (!cancelled) {
            setUser(me.user)
            setShop(me.shop)
          }
        }
      } catch {
        if (!cancelled) clearSession()
      } finally {
        if (!cancelled) setIsLoading(false)
      }
    }
    void restoreSession()
    return () => {
      cancelled = true
    }
  }, [clearSession])

  const startSession = useCallback(async (accessToken: string) => {
    setAccessToken(accessToken)
    const me = await authApi.me() // login/verify return the user, but not the shop
    setUser(me.user)
    setShop(me.shop)
  }, [])

  const login = useCallback(
    async (email: string, password: string) => {
      const result = await authApi.login(email, password) // also sets the refresh cookie
      await startSession(result.accessToken)
    },
    [startSession],
  )

  const logout = useCallback(async () => {
    try {
      await authApi.logout()
    } catch {
      // Even if the server call fails (offline...), the local session ends.
    } finally {
      clearSession()
      navigate('/login', { replace: true })
    }
  }, [clearSession, navigate])

  const value = useMemo<AuthState>(
    () => ({ user, shop, isLoading, login, startSession, logout }),
    [user, shop, isLoading, login, startSession, logout],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}
