import type { Page } from '../types/api'
import type { AuthResponse } from '../types/auth'
import type {
  AcceptInvitationRequest,
  Invitation,
  InvitationPreview,
  InviteStaffRequest,
  PermissionCatalog,
  StaffListParams,
  StaffMember,
  StaffRow,
  UpdateStaffRequest,
} from '../types/staff'
import { api } from './client'

const BASE = '/api/v1/staff'
const memberPath = (id: string) => `${BASE}/${encodeURIComponent(id)}`
const invitationPath = (id: string) => `${BASE}/invitations/${encodeURIComponent(id)}`

/**
 * Thin wrappers around the staff endpoints (docs/modules/STAFF.md §3).
 * No shopId anywhere: the backend takes it from the logged-in user's token.
 */
export const staffApi = {
  async list(params: StaffListParams): Promise<Page<StaffRow>> {
    const { data } = await api.get<Page<StaffRow>>(BASE, {
      // Empty values are left out, so the backend uses its defaults ("all roles", "all statuses").
      params: Object.fromEntries(Object.entries(params).filter(([, v]) => v !== undefined && v !== '')),
    })
    return data
  },

  async permissionCatalog(): Promise<PermissionCatalog> {
    const { data } = await api.get<PermissionCatalog>(`${BASE}/permissions`)
    return data
  },

  async get(userId: string): Promise<StaffMember> {
    const { data } = await api.get<StaffMember>(memberPath(userId))
    return data
  },

  async update(userId: string, body: UpdateStaffRequest): Promise<StaffMember> {
    const { data } = await api.patch<StaffMember>(memberPath(userId), body)
    return data
  },

  async changeStatus(userId: string, status: 'ACTIVE' | 'DISABLED'): Promise<StaffMember> {
    const { data } = await api.patch<StaffMember>(`${memberPath(userId)}/status`, { status })
    return data
  },

  async invite(body: InviteStaffRequest): Promise<Invitation> {
    const { data } = await api.post<Invitation>(`${BASE}/invitations`, body)
    return data
  },

  async resendInvitation(id: string): Promise<Invitation> {
    const { data } = await api.post<Invitation>(`${invitationPath(id)}/resend`)
    return data
  },

  async cancelInvitation(id: string): Promise<void> {
    await api.delete(invitationPath(id))
  },

  // ---- Public: the invited person, not logged in -------------------------------------------
  // The token goes in the body, never in the URL, so it does not end up in server access logs.

  async previewInvitation(token: string): Promise<InvitationPreview> {
    const { data } = await api.post<InvitationPreview>('/api/v1/auth/invitations/preview', { token })
    return data
  },

  /** Creates the account and logs in: access token in the body, refresh cookie set by the server. */
  async acceptInvitation(body: AcceptInvitationRequest): Promise<AuthResponse> {
    const { data } = await api.post<AuthResponse>('/api/v1/auth/invitations/accept', body)
    return data
  },
}
