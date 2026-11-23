/** Mirrors com.sellerdesk.common.security.Role. */
export type Role = 'PLATFORM_ADMIN' | 'SHOP_OWNER' | 'MODERATOR' | 'PACKER' | 'ACCOUNTANT'

/** Mirrors com.sellerdesk.common.security.Permission (docs/SECURITY.md §3). */
export type Permission =
  | 'SHOP_SETTINGS'
  | 'STAFF_MANAGE'
  | 'PRODUCT_VIEW'
  | 'PRODUCT_EDIT'
  | 'STOCK_VIEW'
  | 'STOCK_ADJUST'
  | 'CUSTOMER_VIEW'
  | 'CUSTOMER_EDIT'
  | 'ORDER_VIEW'
  | 'ORDER_CREATE'
  | 'ORDER_EDIT'
  | 'ORDER_CONFIRM'
  | 'ORDER_FULFILL'
  | 'ORDER_CLOSE'
  | 'ORDER_CANCEL'
  | 'REPORT_VIEW'

export type UserStatus = 'PENDING_VERIFICATION' | 'ACTIVE' | 'DISABLED'

export type ShopStatus = 'ACTIVE' | 'SUSPENDED'

export interface User {
  id: string
  name: string
  email: string
  phone: string | null
  role: Role
  permissions: Permission[]
  status: UserStatus
}

export interface ShopSummary {
  id: string
  name: string
  status: ShopStatus
}

/** POST /auth/login, /auth/verify-email. The refresh token is in an HttpOnly cookie, not here. */
export interface AuthResponse {
  accessToken: string
  expiresIn: number
  user: User
}

/** POST /auth/refresh */
export interface TokenResponse {
  accessToken: string
  expiresIn: number
}

/** POST /auth/register-shop */
export interface RegisterShopRequest {
  ownerName: string
  email: string
  password: string
  shopName: string
  /** Bangladeshi mobile, e.g. 01712345678 (optional +88 prefix). */
  shopPhone: string
}

export interface RegisterShopResponse {
  message: string
  email: string
  resendAfterSeconds: number
}

/** POST /auth/verify-email/resend and /auth/password/forgot: same answer for every email. */
export interface OtpSentResponse {
  message: string
  resendAfterSeconds: number
}

export interface VerifyEmailRequest {
  email: string
  code: string
}

export interface ResetPasswordRequest {
  email: string
  code: string
  newPassword: string
}

/** GET /auth/me. `shop` is null for PLATFORM_ADMIN. */
export interface MeResponse {
  user: User
  shop: ShopSummary | null
}
