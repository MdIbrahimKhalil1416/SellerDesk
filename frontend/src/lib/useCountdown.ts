import { useCallback, useEffect, useState } from 'react'

/**
 * A seconds countdown, e.g. "Resend code in 42 s". Based on an end time (not on counting ticks),
 * so it stays correct when the tab was in the background.
 */
export function useCountdown(initialSeconds = 0) {
  const [endsAt, setEndsAt] = useState(() => (initialSeconds > 0 ? Date.now() + initialSeconds * 1000 : 0))
  const [now, setNow] = useState(() => Date.now())

  useEffect(() => {
    if (endsAt <= Date.now()) return
    const id = window.setInterval(() => {
      const t = Date.now()
      setNow(t)
      if (t >= endsAt) window.clearInterval(id)
    }, 250)
    return () => window.clearInterval(id)
  }, [endsAt])

  const start = useCallback((seconds: number) => {
    const t = Date.now()
    setNow(t)
    setEndsAt(t + seconds * 1000)
  }, [])

  return { secondsLeft: Math.max(0, Math.ceil((endsAt - now) / 1000)), start }
}
