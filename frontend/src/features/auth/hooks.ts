import { useMutation } from '@tanstack/react-query'
import { authApi } from '../../api/auth'
import type { RegisterShopRequest, ResetPasswordRequest, VerifyEmailRequest } from '../../types/auth'
import { useAuth } from './authContext'

/**
 * One TanStack Query mutation per auth form. A mutation gives each form `isPending` (for the
 * button spinner), `error` and `mutate/mutateAsync`, without hand-written loading state.
 */

export function useRegisterShop() {
  return useMutation({ mutationFn: (body: RegisterShopRequest) => authApi.registerShop(body) })
}

/** Verifies the code, then starts the session right away (the backend already logged the user in). */
export function useVerifyEmail() {
  const { startSession } = useAuth()
  return useMutation({
    mutationFn: async (body: VerifyEmailRequest) => {
      const result = await authApi.verifyEmail(body)
      await startSession(result.accessToken)
    },
  })
}

export function useResendVerificationCode() {
  return useMutation({ mutationFn: (email: string) => authApi.resendVerificationCode(email) })
}

export function useLogin() {
  const { login } = useAuth()
  return useMutation({ mutationFn: ({ email, password }: { email: string; password: string }) => login(email, password) })
}

export function useForgotPassword() {
  return useMutation({ mutationFn: (email: string) => authApi.forgotPassword(email) })
}

export function useResetPassword() {
  return useMutation({ mutationFn: (body: ResetPasswordRequest) => authApi.resetPassword(body) })
}
