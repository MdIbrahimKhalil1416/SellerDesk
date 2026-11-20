import { useCallback } from 'react'
import { hasPermission } from '../../lib/permissions'
import type { Permission } from '../../types/auth'
import { useAuth } from './authContext'

/**
 * `const { can } = usePermissions(); if (can('PRODUCT_EDIT')) ...`
 *
 * For showing or hiding UI only. The backend still checks every request.
 */
export function usePermissions() {
  const { user } = useAuth()
  const can = useCallback((permission: Permission) => hasPermission(user, permission), [user])
  return { can }
}
