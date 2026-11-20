import { Navigate, Route, Routes } from 'react-router'
import { ForgotPasswordPage } from '../features/auth/ForgotPasswordPage'
import { LoginPage } from '../features/auth/LoginPage'
import { RegisterPage } from '../features/auth/RegisterPage'
import { ResetPasswordPage } from '../features/auth/ResetPasswordPage'
import { VerifyEmailPage } from '../features/auth/VerifyEmailPage'
import { DashboardPage } from '../features/dashboard/DashboardPage'
import { ProductCreatePage } from '../features/products/ProductCreatePage'
import { ProductEditPage } from '../features/products/ProductEditPage'
import { ProductListPage } from '../features/products/ProductListPage'
import { AppLayout } from '../layouts/AppLayout'
import { AuthLayout } from '../layouts/AuthLayout'
import { ProtectedRoute } from './ProtectedRoute'
import { PublicOnlyRoute } from './PublicOnlyRoute'
import { RequirePermission } from './RequirePermission'

export function AppRoutes() {
  return (
    <Routes>
      {/* Only for logged-out users; logged-in users are sent to the dashboard. */}
      <Route element={<PublicOnlyRoute />}>
        <Route element={<AuthLayout />}>
          <Route path="/login" element={<LoginPage />} />
          <Route path="/register" element={<RegisterPage />} />
          <Route path="/verify-email" element={<VerifyEmailPage />} />
          <Route path="/forgot-password" element={<ForgotPasswordPage />} />
          <Route path="/reset-password" element={<ResetPasswordPage />} />
        </Route>
      </Route>

      {/* Only for logged-in users; others are sent to /login. */}
      <Route element={<ProtectedRoute />}>
        <Route element={<AppLayout />}>
          <Route path="/dashboard" element={<DashboardPage />} />
          {/* Without PRODUCT_EDIT the product page is read-only; creating needs PRODUCT_EDIT. */}
          <Route element={<RequirePermission permission="PRODUCT_VIEW" />}>
            <Route path="/products" element={<ProductListPage />} />
            <Route path="/products/:id" element={<ProductEditPage />} />
          </Route>
          <Route element={<RequirePermission permission="PRODUCT_EDIT" />}>
            <Route path="/products/new" element={<ProductCreatePage />} />
          </Route>
        </Route>
      </Route>

      <Route path="/" element={<Navigate to="/dashboard" replace />} />
      <Route path="*" element={<Navigate to="/dashboard" replace />} />
    </Routes>
  )
}
