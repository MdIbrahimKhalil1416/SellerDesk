import type { ReactNode } from 'react'
import { usePermissions } from '../features/auth/usePermissions'
import type { Permission } from '../types/auth'

/** Renders `children` only for users with `permission`: `<Can permission="PRODUCT_EDIT"><Button/></Can>`. */
export function Can({ permission, children, fallback = null }: { permission: Permission; children: ReactNode; fallback?: ReactNode }) {
  const { can } = usePermissions()
  return <>{can(permission) ? children : fallback}</>
}
