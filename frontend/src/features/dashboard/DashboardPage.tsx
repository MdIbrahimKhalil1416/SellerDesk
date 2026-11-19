import { useAuth } from '../auth/authContext'

const UPCOMING = [
  { title: 'Orders', text: 'Track every order from pending to delivered.' },
  { title: 'Products & stock', text: 'Variants, stock levels and low-stock alerts.' },
  { title: 'Customers', text: 'Phone-based customer list with order history.' },
]

export function DashboardPage() {
  const { user, shop } = useAuth()

  return (
    <div className="space-y-6">
      <section className="rounded-2xl bg-gradient-to-br from-indigo-600 to-violet-600 p-6 text-white shadow-sm sm:p-8">
        <p className="text-sm text-indigo-100">Welcome back</p>
        <h1 className="mt-1 text-2xl font-semibold sm:text-3xl">{user?.name}</h1>
        <p className="mt-2 text-indigo-100">
          {shop ? (
            <>
              You are managing <span className="font-semibold text-white">{shop.name}</span>.
            </>
          ) : (
            'Your account has no shop.'
          )}
        </p>
      </section>

      <section className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
        {UPCOMING.map((card) => (
          <div key={card.title} className="rounded-xl border border-slate-200 bg-white p-5">
            <div className="flex items-center justify-between">
              <h2 className="font-semibold text-slate-900">{card.title}</h2>
              <span className="rounded-full bg-slate-100 px-2 py-0.5 text-xs text-slate-500">Coming soon</span>
            </div>
            <p className="mt-2 text-sm text-slate-600">{card.text}</p>
          </div>
        ))}
      </section>

      <section className="rounded-xl border border-slate-200 bg-white p-5 text-sm text-slate-600">
        <h2 className="mb-2 font-semibold text-slate-900">Your account</h2>
        <dl className="grid gap-x-6 gap-y-1 sm:grid-cols-[auto_1fr]">
          <dt className="text-slate-500">Email</dt>
          <dd className="break-all">{user?.email}</dd>
          <dt className="text-slate-500">Role</dt>
          <dd>{user?.role}</dd>
          <dt className="text-slate-500">Shop status</dt>
          <dd>{shop?.status ?? '-'}</dd>
        </dl>
      </section>
    </div>
  )
}
