import { ArrowLeftIcon, ArrowRightIcon } from '../icons'
import { Button } from './Button'
import { Spinner } from './Spinner'

/** Previous / "Page x of y" / Next. `page` is 0-based like the backend. Hidden when there is only one page. */
export function Pagination({ page, totalPages, loading, onPage }: { page: number; totalPages: number; loading: boolean; onPage: (page: number) => void }) {
  if (totalPages <= 1) return null
  return (
    <nav className="mt-4 flex items-center justify-between gap-3" aria-label="Pagination">
      <Button variant="secondary" disabled={page === 0 || loading} onClick={() => onPage(page - 1)}>
        <ArrowLeftIcon /> Previous
      </Button>
      <span className="flex items-center gap-2 text-sm text-slate-600">
        {loading && <Spinner />}
        Page {page + 1} of {totalPages}
      </span>
      <Button variant="secondary" disabled={page + 1 >= totalPages || loading} onClick={() => onPage(page + 1)}>
        Next <ArrowRightIcon />
      </Button>
    </nav>
  )
}
