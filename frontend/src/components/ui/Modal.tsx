import { useEffect, useId, type ReactNode } from 'react'
import { CloseIcon } from '../icons'

interface ModalProps {
  open: boolean
  title: string
  children: ReactNode
  /** Escape, the backdrop and the X close the modal; ignored while `busy` (e.g. saving). */
  onClose: () => void
  busy?: boolean
}

/** A centered dialog (bottom sheet on phones). For simple yes/no questions use ConfirmDialog. */
export function Modal({ open, title, children, onClose, busy }: ModalProps) {
  const titleId = useId()

  useEffect(() => {
    if (!open) return
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && !busy && onClose()
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [open, busy, onClose])

  if (!open) return null
  return (
    <div className="fixed inset-0 z-50 flex items-end justify-center sm:items-center sm:p-4">
      <div className="absolute inset-0 bg-slate-900/40" onClick={() => !busy && onClose()} />
      <div
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        className="relative max-h-[90vh] w-full overflow-y-auto rounded-t-2xl bg-white p-5 shadow-xl sm:max-w-md sm:rounded-xl"
      >
        <div className="mb-4 flex items-start justify-between gap-3">
          <h2 id={titleId} className="text-base font-semibold text-slate-900">
            {title}
          </h2>
          <button type="button" onClick={onClose} disabled={busy} className="-m-1 rounded-md p-1 text-slate-500 hover:bg-slate-100 disabled:opacity-50" aria-label="Close">
            <CloseIcon />
          </button>
        </div>
        {children}
      </div>
    </div>
  )
}
