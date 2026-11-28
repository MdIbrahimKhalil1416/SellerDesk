import { useEffect, useRef, useState, type ChangeEvent } from 'react'
import { shopApi } from '../../../api/shop'
import { ImageIcon } from '../../../components/icons'
import { Alert } from '../../../components/ui/Alert'
import { Button } from '../../../components/ui/Button'
import { ConfirmDialog } from '../../../components/ui/ConfirmDialog'
import { checkImageFile, MAX_IMAGE_MB } from '../../../lib/imageFiles'
import { thumbnailUrl } from '../../../lib/imageUrl'
import { cn } from '../../../lib/cn'
import type { Shop } from '../../../types/shop'
import { useDeleteLogo, useStoreShop } from '../queries'
import { shopErrorMessage } from '../shopErrors'

/**
 * Shop logo with preview. The logo has its own endpoint (a file upload), so it is saved right
 * away when chosen; it is not part of the form's Save button.
 *
 * The file is checked here first (type by its bytes, size) for an instant answer, with the same
 * helper as product photos; the backend checks again.
 */
export function LogoUploader({ shop }: { shop: Shop }) {
  const store = useStoreShop()
  const remove = useDeleteLogo()
  const inputRef = useRef<HTMLInputElement>(null)
  const [preview, setPreview] = useState<string | null>(null) // local file while uploading
  const [progress, setProgress] = useState(0)
  const [uploading, setUploading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [success, setSuccess] = useState<string | null>(null)
  const [confirmRemove, setConfirmRemove] = useState(false)

  // Free the in-memory preview when it is replaced or the page closes.
  useEffect(() => {
    if (!preview) return
    return () => URL.revokeObjectURL(preview)
  }, [preview])

  async function onFileChosen(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0]
    event.target.value = '' // choosing the same file again fires change again
    if (!file) return
    setError(null)
    setSuccess(null)
    const problem = await checkImageFile(file)
    if (problem) {
      setError(`${file.name}: ${problem}`)
      return
    }
    setPreview(URL.createObjectURL(file))
    setProgress(0)
    setUploading(true)
    try {
      store(await shopApi.uploadLogo(file, setProgress))
      setSuccess('Logo saved.')
    } catch (e) {
      setError(shopErrorMessage(e, 'The logo could not be uploaded.'))
    } finally {
      setUploading(false)
      setPreview(null)
    }
  }

  function onConfirmRemove() {
    setError(null)
    setSuccess(null)
    remove.mutate(undefined, {
      onSuccess: () => setSuccess('Logo removed.'),
      onError: (e) => setError(shopErrorMessage(e)),
      onSettled: () => setConfirmRemove(false),
    })
  }

  const shown = preview ?? thumbnailUrl(shop.logoUrl, 96)
  const busy = uploading || remove.isPending

  return (
    <div>
      <span className="mb-1.5 block text-sm font-medium text-slate-700">Logo</span>
      <div className="flex flex-col gap-4 sm:flex-row sm:items-center">
        <div className={cn('relative flex h-24 w-24 shrink-0 items-center justify-center overflow-hidden rounded-xl border bg-slate-50', shown ? 'border-slate-200' : 'border-dashed border-slate-300')}>
          {shown ? (
            <img src={shown} alt="Shop logo" className={cn('h-full w-full object-cover', uploading && 'opacity-60')} />
          ) : (
            <span className="text-slate-400">
              <ImageIcon />
            </span>
          )}
          {uploading && (
            <div className="absolute inset-x-1.5 bottom-1.5 h-1.5 overflow-hidden rounded-full bg-slate-200" role="progressbar" aria-valuenow={progress} aria-valuemin={0} aria-valuemax={100} aria-label="Logo upload">
              <div className="h-full bg-indigo-600 transition-all" style={{ width: `${progress}%` }} />
            </div>
          )}
        </div>
        <div className="space-y-2">
          <div className="flex flex-wrap gap-2">
            <Button variant="secondary" onClick={() => inputRef.current?.click()} loading={uploading} disabled={busy}>
              {uploading ? (progress < 100 ? `Uploading ${progress}%` : 'Processing…') : shop.logoUrl ? 'Change logo' : 'Upload logo'}
            </Button>
            {shop.logoUrl && !uploading && (
              <Button variant="ghost" className="text-red-600 hover:bg-red-50" onClick={() => setConfirmRemove(true)} disabled={busy}>
                Remove
              </Button>
            )}
          </div>
          <p className="text-xs text-slate-500">JPEG, PNG or WebP · max {MAX_IMAGE_MB} MB · square works best. Saved as soon as you upload it.</p>
          <input ref={inputRef} type="file" accept="image/jpeg,image/png,image/webp" hidden onChange={(e) => void onFileChosen(e)} />
        </div>
      </div>
      {error && <Alert tone="error" className="mt-3">{error}</Alert>}
      {success && <Alert tone="success" className="mt-3">{success}</Alert>}

      <ConfirmDialog
        open={confirmRemove}
        title="Remove the logo?"
        confirmLabel="Remove"
        danger
        loading={remove.isPending}
        onConfirm={onConfirmRemove}
        onCancel={() => setConfirmRemove(false)}
      >
        The logo is deleted. You can upload a new one at any time.
      </ConfirmDialog>
    </div>
  )
}
