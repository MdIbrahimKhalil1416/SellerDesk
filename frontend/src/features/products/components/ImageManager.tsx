import { useEffect, useRef, useState, type ChangeEvent, type ReactNode } from 'react'
import { productsApi } from '../../../api/products'
import { ArrowLeftIcon, ArrowRightIcon, ImageIcon, TrashIcon } from '../../../components/icons'
import { Alert } from '../../../components/ui/Alert'
import { ConfirmDialog } from '../../../components/ui/ConfirmDialog'
import { cn } from '../../../lib/cn'
import type { Product, ProductImage } from '../../../types/product'
import { checkImageFile, MAX_IMAGES, MAX_IMAGE_MB } from '../../../lib/imageFiles'
import { productErrorMessage } from '../productErrors'
import { useLatestVersion, useStoreProduct } from '../queries'
import { thumbnailUrl } from '../../../lib/imageUrl'

interface Upload {
  id: number
  name: string
  previewUrl: string
  progress: number
  error?: string
}

let uploadCounter = 0

/**
 * Product photos: upload with preview and progress, reorder (first = cover), remove.
 *
 * Files are checked here first (type, size, count) so the user gets an answer at once; the
 * backend checks again by the file's bytes. Uploads run one after another: the 8-image limit
 * and the product version then always refer to the latest state.
 */
export function ImageManager({ product, canEdit }: { product: Product; canEdit: boolean }) {
  const store = useStoreProduct()
  const latestVersion = useLatestVersion()
  const [uploads, setUploads] = useState<Upload[]>([])
  const [rejected, setRejected] = useState<string[]>([])
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [toRemove, setToRemove] = useState<ProductImage | null>(null)
  const [removing, setRemoving] = useState(false)
  const inputRef = useRef<HTMLInputElement>(null)
  const previews = useRef(new Set<string>())

  // Free the in-memory preview images when leaving the page.
  useEffect(() => {
    const urls = previews.current
    return () => urls.forEach((url) => URL.revokeObjectURL(url))
  }, [])

  const pending = uploads.filter((u) => !u.error).length
  const slotsLeft = MAX_IMAGES - product.images.length - pending

  function patchUpload(id: number, patch: Partial<Upload>) {
    setUploads((list) => list.map((u) => (u.id === id ? { ...u, ...patch } : u)))
  }

  function dropUpload(upload: Upload) {
    URL.revokeObjectURL(upload.previewUrl)
    previews.current.delete(upload.previewUrl)
    setUploads((list) => list.filter((u) => u.id !== upload.id))
  }

  async function onFilesChosen(event: ChangeEvent<HTMLInputElement>) {
    const files = Array.from(event.target.files ?? [])
    event.target.value = '' // choosing the same file again fires change again
    setError(null)
    const problems: string[] = []
    const accepted: Array<{ upload: Upload; file: File }> = []
    let slots = slotsLeft
    for (const file of files) {
      const problem = await checkImageFile(file)
      if (problem) {
        problems.push(`${file.name}: ${problem}`)
      } else if (slots <= 0) {
        problems.push(`${file.name}: a product can have at most ${MAX_IMAGES} images.`)
      } else {
        slots--
        const previewUrl = URL.createObjectURL(file)
        previews.current.add(previewUrl)
        accepted.push({ upload: { id: ++uploadCounter, name: file.name, previewUrl, progress: 0 }, file })
      }
    }
    setRejected(problems)
    if (accepted.length === 0) return
    setUploads((list) => [...list, ...accepted.map((a) => a.upload)])

    for (const { upload, file } of accepted) {
      try {
        const updated = await productsApi.uploadImage(product.id, file, (progress) => patchUpload(upload.id, { progress }))
        store(updated)
        dropUpload(upload)
      } catch (e) {
        patchUpload(upload.id, { error: productErrorMessage(e, 'Upload failed.') })
      }
    }
  }

  async function move(index: number, direction: -1 | 1) {
    const ids = product.images.map((i) => i.id)
    const target = index + direction
    if (target < 0 || target >= ids.length) return
    ;[ids[index], ids[target]] = [ids[target] ?? '', ids[index] ?? '']
    setBusy(true)
    setError(null)
    try {
      store(await productsApi.reorderImages(product.id, ids, latestVersion(product.id)))
    } catch (e) {
      setError(productErrorMessage(e))
    } finally {
      setBusy(false)
    }
  }

  async function confirmRemove() {
    if (!toRemove) return
    setRemoving(true)
    setError(null)
    try {
      store(await productsApi.deleteImage(product.id, toRemove.id))
      setToRemove(null)
    } catch (e) {
      setError(productErrorMessage(e))
      setToRemove(null)
    } finally {
      setRemoving(false)
    }
  }

  return (
    <div>
      {error && <Alert tone="error" className="mb-3">{error}</Alert>}
      {rejected.length > 0 && (
        <Alert tone="error" className="mb-3">
          <p className="font-medium">Some files were not added:</p>
          <ul className="mt-1 list-disc pl-5">
            {rejected.map((r) => (
              <li key={r}>{r}</li>
            ))}
          </ul>
        </Alert>
      )}

      <ul className="grid grid-cols-2 gap-3 sm:grid-cols-4">
        {product.images.map((image, index) => (
          <li key={image.id} className="group relative overflow-hidden rounded-xl border border-slate-200 bg-slate-100">
            <img src={thumbnailUrl(image.url, 160) ?? image.url} alt={`Product image ${index + 1}`} className="aspect-square w-full object-cover" loading="lazy" />
            {index === 0 && <span className="absolute left-2 top-2 rounded-full bg-indigo-600 px-2 py-0.5 text-xs font-semibold text-white">Cover</span>}
            {canEdit && (
              <div className="absolute inset-x-0 bottom-0 flex justify-between bg-gradient-to-t from-slate-900/70 to-transparent p-1.5">
                <div className="flex gap-1">
                  <IconButton label="Move left" disabled={busy || index === 0} onClick={() => void move(index, -1)}>
                    <ArrowLeftIcon />
                  </IconButton>
                  <IconButton label="Move right" disabled={busy || index === product.images.length - 1} onClick={() => void move(index, 1)}>
                    <ArrowRightIcon />
                  </IconButton>
                </div>
                <IconButton label="Remove image" disabled={busy} onClick={() => setToRemove(image)}>
                  <TrashIcon />
                </IconButton>
              </div>
            )}
          </li>
        ))}

        {uploads.map((upload) => (
          <li key={upload.id} className={cn('relative overflow-hidden rounded-xl border bg-slate-100', upload.error ? 'border-red-300' : 'border-slate-200')}>
            <img src={upload.previewUrl} alt={`Uploading ${upload.name}`} className={cn('aspect-square w-full object-cover', !upload.error && 'opacity-60')} />
            <div className="absolute inset-x-0 bottom-0 bg-white/90 p-2 text-xs">
              {upload.error ? (
                <div className="flex items-start justify-between gap-2">
                  <span className="text-red-700">{upload.error}</span>
                  <button type="button" className="font-semibold text-slate-600 hover:text-slate-900" onClick={() => dropUpload(upload)}>
                    Dismiss
                  </button>
                </div>
              ) : (
                <>
                  <div className="mb-1 flex justify-between text-slate-600">
                    <span className="truncate">{upload.progress < 100 ? 'Uploading…' : 'Processing…'}</span>
                    <span>{upload.progress}%</span>
                  </div>
                  <div className="h-1.5 overflow-hidden rounded-full bg-slate-200" role="progressbar" aria-valuenow={upload.progress} aria-valuemin={0} aria-valuemax={100} aria-label={`Upload of ${upload.name}`}>
                    <div className="h-full bg-indigo-600 transition-all" style={{ width: `${upload.progress}%` }} />
                  </div>
                </>
              )}
            </div>
          </li>
        ))}

        {canEdit && slotsLeft > 0 && (
          <li>
            <button
              type="button"
              onClick={() => inputRef.current?.click()}
              className="flex aspect-square w-full flex-col items-center justify-center gap-1 rounded-xl border-2 border-dashed border-slate-300 bg-white p-3 text-center text-sm text-slate-600 hover:border-indigo-400 hover:text-indigo-600"
            >
              <ImageIcon />
              <span className="font-medium">Add photos</span>
              <span className="text-xs text-slate-500">{slotsLeft} left</span>
            </button>
            <input ref={inputRef} type="file" accept="image/jpeg,image/png,image/webp" multiple hidden onChange={(e) => void onFilesChosen(e)} />
          </li>
        )}
      </ul>

      {product.images.length === 0 && uploads.length === 0 && !canEdit && <p className="text-sm text-slate-500">No photos.</p>}
      {canEdit && (
        <p className="mt-2 text-xs text-slate-500">
          JPEG, PNG or WebP · max {MAX_IMAGE_MB} MB each · up to {MAX_IMAGES} photos. The first photo is the cover.
        </p>
      )}

      <ConfirmDialog
        open={toRemove !== null}
        title="Remove this photo?"
        confirmLabel="Remove"
        danger
        loading={removing}
        onConfirm={() => void confirmRemove()}
        onCancel={() => setToRemove(null)}
      >
        The photo is deleted from the product. This cannot be undone.
      </ConfirmDialog>
    </div>
  )
}

function IconButton({ label, disabled, onClick, children }: { label: string; disabled?: boolean; onClick: () => void; children: ReactNode }) {
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={disabled}
      aria-label={label}
      title={label}
      className="rounded-md bg-white/90 p-1.5 text-slate-700 shadow-sm hover:bg-white disabled:opacity-40"
    >
      {children}
    </button>
  )
}
