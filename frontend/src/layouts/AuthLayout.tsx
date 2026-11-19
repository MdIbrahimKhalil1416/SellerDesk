import { Outlet } from 'react-router'

/** Login, register, verify, password reset: brand panel on large screens, form card everywhere. */
export function AuthLayout() {
  return (
    <div className="min-h-screen bg-slate-50 lg:grid lg:grid-cols-2">
      <aside className="hidden flex-col justify-between bg-gradient-to-br from-indigo-600 to-violet-700 p-12 text-white lg:flex">
        <div className="text-xl font-semibold">SellerDesk</div>
        <div>
          <p className="text-3xl font-semibold leading-tight">Orders, stock and COD payments for your Facebook shop.</p>
          <p className="mt-4 max-w-md text-indigo-100">
            One place for you and your team to manage every order from Messenger to delivery.
          </p>
        </div>
        <p className="text-sm text-indigo-200">Made for F-commerce sellers in Bangladesh</p>
      </aside>

      <main className="flex min-h-screen items-center justify-center px-4 py-10 sm:px-6">
        <div className="w-full max-w-md">
          <div className="mb-8 text-center text-xl font-semibold text-indigo-600 lg:hidden">SellerDesk</div>
          <div className="rounded-2xl border border-slate-200 bg-white p-6 shadow-sm sm:p-8">
            <Outlet />
          </div>
        </div>
      </main>
    </div>
  )
}
