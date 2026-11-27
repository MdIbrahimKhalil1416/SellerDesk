import { useEffect, useId, type ReactNode } from 'react'
import { CloseIcon } from '../icons'

interface DrawerProps {
  open: boolean
  title: string
  children: ReactNode
  /** Sticky area at the bottom, e.g. a Save button. */
  footer?: ReactNode
  onClose: () => void
  /** While true, Escape and the backdrop do nothing (e.g. a confirm dialog is open on top). */
  locked?: boolean
}

/**
 * A panel that slides in from the right (full width on phones). z-40, so a ConfirmDialog (z-50)
 * opened from inside it appears on top.
 */
export function Drawer({ open, title, children, footer, onClose, locked }: DrawerProps) {
  const titleId = useId()

  useEffect(() => {
    if (!open) return
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && !locked && onClose()
    document.addEventListener('keydown', onKey)
    document.body.style.overflow = 'hidden' // no background scrolling behind the drawer
    return () => {
      document.removeEventListener('keydown', onKey)
      document.body.style.overflow = ''
    }
  }, [open, locked, onClose])

  if (!open) return null
  return (
    <div className="fixed inset-0 z-40">
      <div className="absolute inset-0 bg-slate-900/40" onClick={() => !locked && onClose()} />
      <aside
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        className="absolute inset-y-0 right-0 flex w-full flex-col bg-white shadow-xl sm:max-w-lg"
      >
        <header className="flex items-center justify-between gap-3 border-b border-slate-200 px-5 py-4">
          <h2 id={titleId} className="truncate text-base font-semibold text-slate-900">
            {title}
          </h2>
          <button type="button" onClick={onClose} className="-m-1 rounded-md p-1 text-slate-500 hover:bg-slate-100" aria-label="Close">
            <CloseIcon />
          </button>
        </header>
        <div className="flex-1 overflow-y-auto px-5 py-4">{children}</div>
        {footer && <footer className="border-t border-slate-200 px-5 py-3">{footer}</footer>}
      </aside>
    </div>
  )
}
