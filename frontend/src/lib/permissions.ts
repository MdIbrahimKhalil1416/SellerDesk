import type { Permission, User } from '../types/auth'

/**
 * True if the user has the permission. The list comes from /auth/me and matches the token.
 *
 * Hiding a button is only a convenience: the backend checks every permission again (403).
 */
export function hasPermission(user: User | null, permission: Permission): boolean {
  return !!user && user.permissions.includes(permission)
}
