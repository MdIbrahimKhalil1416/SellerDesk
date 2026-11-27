import type { Permission, Role, UserStatus } from './auth'

/** Roles an owner or manager can give to staff. SHOP_OWNER and PLATFORM_ADMIN can never be assigned. */
export type StaffRole = 'MODERATOR' | 'PACKER' | 'ACCOUNTANT'

/** Status shown in the combined list: ACTIVE / DISABLED for members, INVITED / EXPIRED for invitations. */
export type StaffRowStatus = 'ACTIVE' | 'DISABLED' | 'INVITED' | 'EXPIRED'

/** GET /staff: one row of the combined table (docs/modules/STAFF.md §3.1). */
export interface StaffRow {
  kind: 'MEMBER' | 'INVITATION'
  /** userId for MEMBER, invitationId for INVITATION. */
  id: string
  /** null for INVITATION. */
  name: string | null
  email: string
  role: Role
  permissions: Permission[]
  status: StaffRowStatus
  /** MEMBER only, null if the member never logged in. */
  lastLoginAt: string | null
  /** INVITATION only. */
  invitedAt: string | null
  expiresAt: string | null
  lastSentAt: string | null
}

export interface StaffListParams {
  q?: string
  role?: Role
  status?: StaffRowStatus
  page: number
  size: number
}

/** GET/PATCH /staff/{userId} */
export interface StaffMember {
  id: string
  name: string
  email: string
  phone: string | null
  role: Role
  permissions: Permission[]
  status: UserStatus
  lastLoginAt: string | null
  createdAt: string
  /** null for the owner. `name` is null if the inviter no longer exists. */
  invitedBy: { id: string; name: string | null } | null
}

/** GET /staff/permissions */
export interface PermissionCatalog {
  permissions: Array<{ code: Permission; description: string }>
  roleDefaults: Record<StaffRole, Permission[]>
}

/** PATCH /staff/{userId}. `permissions` is the complete new list, not a diff. */
export interface UpdateStaffRequest {
  role?: StaffRole
  permissions?: Permission[]
}

export interface InviteStaffRequest {
  email: string
  role: StaffRole
  /** Omitted: the role's default permissions. */
  permissions?: Permission[]
}

export interface Invitation {
  id: string
  email: string
  role: StaffRole
  permissions: Permission[]
  status: 'PENDING' | 'ACCEPTED' | 'CANCELLED'
  expiresAt: string
  lastSentAt: string
  createdAt: string
}

/** POST /auth/invitations/preview */
export interface InvitationPreview {
  email: string
  shopName: string
  role: StaffRole
  inviterName: string
  expiresAt: string
}

export interface AcceptInvitationRequest {
  token: string
  name: string
  password: string
}
