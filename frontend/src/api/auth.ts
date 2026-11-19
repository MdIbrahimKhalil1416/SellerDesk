import type {
  AuthResponse,
  MeResponse,
  OtpSentResponse,
  RegisterShopRequest,
  RegisterShopResponse,
  ResetPasswordRequest,
  VerifyEmailRequest,
} from '../types/auth'
import { api } from './client'

/** Thin wrappers around the auth endpoints (docs/API.md §1). */
export const authApi = {
  async registerShop(body: RegisterShopRequest): Promise<RegisterShopResponse> {
    const { data } = await api.post<RegisterShopResponse>('/api/v1/auth/register-shop', body)
    return data
  },

  /** Correct code → account ACTIVE, access token returned, refresh cookie set. */
  async verifyEmail(body: VerifyEmailRequest): Promise<AuthResponse> {
    const { data } = await api.post<AuthResponse>('/api/v1/auth/verify-email', body)
    return data
  },

  async resendVerificationCode(email: string): Promise<OtpSentResponse> {
    const { data } = await api.post<OtpSentResponse>('/api/v1/auth/verify-email/resend', { email })
    return data
  },

  async login(email: string, password: string): Promise<AuthResponse> {
    const { data } = await api.post<AuthResponse>('/api/v1/auth/login', { email, password })
    return data
  },

  async forgotPassword(email: string): Promise<OtpSentResponse> {
    const { data } = await api.post<OtpSentResponse>('/api/v1/auth/password/forgot', { email })
    return data
  },

  /** 204 on success. All sessions of the user end; they log in again with the new password. */
  async resetPassword(body: ResetPasswordRequest): Promise<void> {
    await api.post('/api/v1/auth/password/reset', body)
  },

  async me(): Promise<MeResponse> {
    const { data } = await api.get<MeResponse>('/api/v1/auth/me')
    return data
  },

  /**
   * Revokes all tokens of the user on the server and deletes the refresh cookie.
   * With an expired access token the interceptor refreshes first, so the server-side logout
   * still happens.
   */
  async logout(): Promise<void> {
    await api.post('/api/v1/auth/logout')
  },
}
