import { useCallback, useEffect, useState } from 'react'
import { useNavigate } from 'react-router'

/**
 * Warns before the user leaves a page with unsaved changes.
 *
 * - Reload, closing the tab, typing another URL: the browser's own "Leave site?" dialog
 *   (`beforeunload`; browsers do not allow a custom text there).
 * - Clicking an in-app link (sidebar, "Back to ..."): the click is stopped and `pendingPath` is
 *   set, so the page can show its own ConfirmDialog. `leave()` then goes there, `stay()` cancels.
 *
 * Why not React Router's `useBlocker`: it only works with a "data router" (createBrowserRouter),
 * and this app uses <BrowserRouter>. The browser's back button is therefore not intercepted.
 *
 * How the link check works: the listener runs on `document` in the CAPTURE phase, which comes
 * before React's own click handling. Stopping the event there means React Router never sees the
 * click, so it does not navigate.
 */
export function useUnsavedChangesGuard(when: boolean) {
  const navigate = useNavigate()
  const [pendingPath, setPendingPath] = useState<string | null>(null)

  useEffect(() => {
    if (!when) return
    const onBeforeUnload = (event: BeforeUnloadEvent) => {
      event.preventDefault()
      event.returnValue = '' // older browsers need this to show the dialog
    }
    const onClick = (event: MouseEvent) => {
      // Ctrl/Cmd/Shift-click or middle click opens a new tab: nothing is lost here.
      if (event.defaultPrevented || event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return
      const anchor = (event.target as Element | null)?.closest?.('a[href]')
      if (!(anchor instanceof HTMLAnchorElement) || anchor.target === '_blank' || anchor.hasAttribute('download')) return
      const url = new URL(anchor.href, window.location.href)
      if (url.origin !== window.location.origin) return // external links: beforeunload covers them
      const path = url.pathname + url.search + url.hash
      if (path === window.location.pathname + window.location.search + window.location.hash) return
      event.preventDefault()
      event.stopPropagation()
      setPendingPath(path)
    }
    window.addEventListener('beforeunload', onBeforeUnload)
    document.addEventListener('click', onClick, true)
    return () => {
      window.removeEventListener('beforeunload', onBeforeUnload)
      document.removeEventListener('click', onClick, true)
    }
  }, [when])

  const stay = useCallback(() => setPendingPath(null), [])
  const leave = useCallback(() => {
    if (pendingPath) navigate(pendingPath)
    setPendingPath(null)
  }, [navigate, pendingPath])

  return { pendingPath, stay, leave }
}
