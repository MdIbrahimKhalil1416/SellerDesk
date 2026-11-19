/**
 * The access token lives ONLY in this variable (JavaScript memory), never in localStorage or
 * sessionStorage (docs/SECURITY.md §2). An XSS bug therefore cannot read a saved token, and the
 * token disappears when the tab is closed or reloaded. After a reload the session is restored
 * from the HttpOnly refresh cookie (see AuthProvider).
 */
let accessToken: string | null = null

export function getAccessToken(): string | null {
  return accessToken
}

export function setAccessToken(token: string): void {
  accessToken = token
}

export function clearAccessToken(): void {
  accessToken = null
}
