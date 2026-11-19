import axios, { type AxiosError, type InternalAxiosRequestConfig } from 'axios'
import type { TokenResponse } from '../types/auth'
import { clearAccessToken, getAccessToken, setAccessToken } from './tokenStore'

declare module 'axios' {
  interface AxiosRequestConfig {
    /** Never try a token refresh for this request (login, refresh itself...). */
    skipAuthRefresh?: boolean
    /** Set after one refresh + retry, so a request is retried at most once. */
    _retried?: boolean
  }
}

export const REFRESH_PATH = '/api/v1/auth/refresh'

/**
 * The one Axios instance for all backend calls.
 *
 * - No baseURL: every call uses a relative path (/api/...). The backend serves the app and the API
 *   from one origin in prod; in dev the Vite proxy forwards /api to the backend. So the browser
 *   always talks to a single origin: no CORS, and the refresh cookie is first-party.
 * - `withCredentials: true`: makes sure the HttpOnly refresh cookie is sent and stored. On the same
 *   origin browsers do this anyway; it is kept so behavior does not depend on a default.
 * - Request interceptor: adds `Authorization: Bearer <access token from memory>`.
 * - Response interceptor: on 401, refreshes once and retries the original request.
 */
export const api = axios.create({
  withCredentials: true,
  headers: { 'Content-Type': 'application/json' },
  timeout: 20_000,
})

api.interceptors.request.use((config) => {
  const token = getAccessToken()
  if (token) {
    // Always the CURRENT token, so a retried request automatically uses the refreshed one.
    config.headers.set('Authorization', `Bearer ${token}`)
  }
  return config
})

// ---- Refresh: one call at a time, shared by everyone who needs it ----------------------

let refreshInFlight: Promise<string | null> | null = null

/**
 * Gets a new access token from the refresh cookie. Returns null if there is no valid session.
 *
 * Single flight: if five requests get 401 at the same moment, all five await the SAME promise, so
 * the backend sees one /auth/refresh call, not five. (Five parallel refreshes would each rotate the
 * cookie and race each other.)
 */
export function refreshAccessToken(): Promise<string | null> {
  if (!refreshInFlight) {
    refreshInFlight = api
      .post<TokenResponse>(REFRESH_PATH, undefined, { skipAuthRefresh: true })
      .then((response) => {
        setAccessToken(response.data.accessToken)
        return response.data.accessToken
      })
      .catch(() => {
        clearAccessToken()
        return null
      })
      .finally(() => {
        refreshInFlight = null
      })
  }
  return refreshInFlight
}

// ---- What to do when the session is really over -----------------------------------------

let onSessionExpired: (() => void) | null = null

/** AuthProvider registers a handler that clears the user and redirects to /login. */
export function setSessionExpiredHandler(handler: (() => void) | null): void {
  onSessionExpired = handler
}

/**
 * Public auth endpoints answer 401 for wrong passwords etc.; a refresh makes no sense there.
 * `me` and `logout` need a Bearer token like any other endpoint, so they DO refresh: otherwise a
 * logout with an expired access token would fail on the server and leave the refresh cookie valid.
 */
const REFRESHABLE_AUTH_PATHS = ['/api/v1/auth/me', '/api/v1/auth/logout']

function isPublicAuthEndpoint(url: string | undefined): boolean {
  return !!url && url.includes('/api/v1/auth/') && !REFRESHABLE_AUTH_PATHS.some((p) => url.includes(p))
}

api.interceptors.response.use(
  (response) => response,
  async (error: AxiosError) => {
    const config = error.config as InternalAxiosRequestConfig | undefined
    const shouldRefresh =
      error.response?.status === 401 &&
      config !== undefined &&
      !config.skipAuthRefresh &&
      !config._retried &&
      !isPublicAuthEndpoint(config.url)

    if (!shouldRefresh) {
      return Promise.reject(error)
    }

    config._retried = true
    const newToken = await refreshAccessToken()
    if (!newToken) {
      onSessionExpired?.()
      return Promise.reject(error)
    }
    return api(config) // request interceptor puts the new token in
  },
)
