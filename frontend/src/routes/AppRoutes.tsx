import { Navigate, Route, Routes } from 'react-router'
import { ForgotPasswordPage } from '../features/auth/ForgotPasswordPage'
import { LoginPage } from '../features/auth/LoginPage'
import { RegisterPage } from '../features/auth/RegisterPage'
import { ResetPasswordPage } from '../features/auth/ResetPasswordPage'
import { VerifyEmailPage } from '../features/auth/VerifyEmailPage'
import { DashboardPage } from '../features/dashboard/DashboardPage'
import { CustomerCreatePage } from '../features/customers/CustomerCreatePage'
import { CustomerDetailPage } from '../features/customers/CustomerDetailPage'
import { CustomerListPage } from '../features/customers/CustomerListPage'
import { InventoryPage } from '../features/inventory/InventoryPage'
import { OrderCreatePage } from '../features/orders/OrderCreatePage'
import { OrderDetailPage } from '../features/orders/OrderDetailPage'
import { OrderListPage } from '../features/orders/OrderListPage'
import { VariantStockPage } from '../features/inventory/VariantStockPage'
import { ProductCreatePage } from '../features/products/ProductCreatePage'
import { ProductEditPage } from '../features/products/ProductEditPage'
import { ProductListPage } from '../features/products/ProductListPage'
import { SettingsPage } from '../features/settings/SettingsPage'
import { AcceptInvitePage } from '../features/staff/AcceptInvitePage'
import { StaffPage } from '../features/staff/StaffPage'
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

      {/*
        Public for everyone, also when logged in: an invite link opened on a device where someone
        is logged in must still work (PublicOnlyRoute would redirect and lose the #token).
      */}
      <Route element={<AuthLayout />}>
        <Route path="/accept-invite" element={<AcceptInvitePage />} />
      </Route>

      {/* Only for logged-in users; others are sent to /login. */}
      <Route element={<ProtectedRoute />}>
        <Route element={<AppLayout />}>
          <Route path="/dashboard" element={<DashboardPage />} />
          {/* Status buttons follow each order's allowedActions (status + the user's permissions). */}
          <Route element={<RequirePermission permission="ORDER_VIEW" />}>
            <Route path="/orders" element={<OrderListPage />} />
            <Route path="/orders/:id" element={<OrderDetailPage />} />
          </Route>
          <Route element={<RequirePermission permission="ORDER_CREATE" />}>
            <Route path="/orders/new" element={<OrderCreatePage />} />
          </Route>
          {/* Without PRODUCT_EDIT the product page is read-only; creating needs PRODUCT_EDIT. */}
          <Route element={<RequirePermission permission="PRODUCT_VIEW" />}>
            <Route path="/products" element={<ProductListPage />} />
            <Route path="/products/:id" element={<ProductEditPage />} />
          </Route>
          <Route element={<RequirePermission permission="PRODUCT_EDIT" />}>
            <Route path="/products/new" element={<ProductCreatePage />} />
          </Route>
          {/* Without STOCK_ADJUST the pages are read-only (no Restock / Adjust buttons). */}
          <Route element={<RequirePermission permission="STOCK_VIEW" />}>
            <Route path="/inventory" element={<InventoryPage />} />
            <Route path="/inventory/variants/:variantId" element={<VariantStockPage />} />
          </Route>
          {/* Without CUSTOMER_EDIT the pages are read-only; creating needs CUSTOMER_EDIT. */}
          <Route element={<RequirePermission permission="CUSTOMER_VIEW" />}>
            <Route path="/customers" element={<CustomerListPage />} />
            <Route path="/customers/:id" element={<CustomerDetailPage />} />
          </Route>
          <Route element={<RequirePermission permission="CUSTOMER_EDIT" />}>
            <Route path="/customers/new" element={<CustomerCreatePage />} />
          </Route>
          <Route element={<RequirePermission permission="STAFF_MANAGE" />}>
            <Route path="/staff" element={<StaffPage />} />
          </Route>
          <Route element={<RequirePermission permission="SHOP_SETTINGS" />}>
            <Route path="/settings" element={<SettingsPage />} />
          </Route>
        </Route>
      </Route>

      <Route path="/" element={<Navigate to="/dashboard" replace />} />
      <Route path="*" element={<Navigate to="/dashboard" replace />} />
    </Routes>
  )
}
