import { useQueryClient } from '@tanstack/react-query'
import { useMemo, useState, type FormEvent } from 'react'
import { Link, useLocation, useParams } from 'react-router'
import { productsApi } from '../../api/products'
import { ArrowLeftIcon } from '../../components/icons'
import { Alert } from '../../components/ui/Alert'
import { Button } from '../../components/ui/Button'
import { Spinner } from '../../components/ui/Spinner'
import { getApiError } from '../../lib/apiError'
import type { Product } from '../../types/product'
import { usePermissions } from '../auth/usePermissions'
import { ArchiveButton } from './components/ArchiveButton'
import { ImageManager } from './components/ImageManager'
import { ProductFields } from './components/ProductFields'
import { ProductStatusBadge } from './components/ProductStatusBadge'
import { VariantsSection } from './components/VariantsSection'
import { rowErrorsOf, validateProductFields, type ProductFieldErrors, type ProductFieldsValue } from './productForm'
import { productErrorMessage } from './productErrors'
import { productKeys, useLatestVersion, useProduct, useStoreProduct, useUpdateProduct } from './queries'
import { changedFields, isDirty, rowFromVariant, toVariantRequest, validateRows, type RowErrors, type VariantRow } from './variantRows'

/** /products/:id. Read-only for users without PRODUCT_EDIT. */
export function ProductEditPage() {
  const { id = '' } = useParams()
  const product = useProduct(id)

  if (product.isPending) {
    return (
      <div className="flex items-center gap-3 py-12 text-slate-600" role="status">
        <Spinner /> Loading product…
      </div>
    )
  }
  if (product.isError) {
    const notFound = getApiError(product.error)?.code === 'NOT_FOUND'
    return (
      <div className="mx-auto max-w-md rounded-xl border border-slate-200 bg-white p-6 text-center">
        <h1 className="text-lg font-semibold text-slate-900">{notFound ? 'Product not found' : 'Could not load the product'}</h1>
        <p className="mt-2 text-sm text-slate-600">
          {notFound ? 'It may have been deleted, or the link is wrong.' : productErrorMessage(product.error)}
        </p>
        <div className="mt-4 flex justify-center gap-2">
          <Link to="/products" className="text-sm font-semibold text-indigo-600 hover:text-indigo-500">
            Back to products
          </Link>
          {!notFound && (
            <Button variant="secondary" onClick={() => void product.refetch()}>
              Try again
            </Button>
          )}
        </div>
      </div>
    )
  }
  // key: a different product gets fresh form state.
  return <ProductEditor key={product.data.id} product={product.data} />
}

function ProductEditor({ product }: { product: Product }) {
  const { can } = usePermissions()
  const canEdit = can('PRODUCT_EDIT')
  const location = useLocation()
  const [notice, setNotice] = useState<string | null>((location.state as { notice?: string } | null)?.notice ?? null)
  const [statusError, setStatusError] = useState<string | null>(null)

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div className="min-w-0">
          <Link to="/products" className="inline-flex items-center gap-1 text-sm font-medium text-slate-500 hover:text-slate-800">
            <ArrowLeftIcon /> Products
          </Link>
          <div className="mt-1 flex flex-wrap items-center gap-2">
            <h1 className="truncate text-2xl font-semibold text-slate-900">{product.name}</h1>
            <ProductStatusBadge status={product.status} />
          </div>
        </div>
        {canEdit && <ArchiveButton product={product} onError={setStatusError} />}
      </div>

      {notice && (
        <Alert tone="success">
          <div className="flex items-start justify-between gap-2">
            <span>{notice}</span>
            <button type="button" className="font-semibold" onClick={() => setNotice(null)} aria-label="Dismiss">
              ×
            </button>
          </div>
        </Alert>
      )}
      {statusError && <Alert tone="error">{statusError}</Alert>}
      {product.status === 'ARCHIVED' && (
        <Alert tone="info">This product is archived: it cannot be added to new orders. You can still edit it.</Alert>
      )}
      {!canEdit && <Alert tone="info">You can view this product but not change it.</Alert>}

      <BasicInfoSection product={product} canEdit={canEdit} />

      <section className="rounded-xl border border-slate-200 bg-white p-4 sm:p-5">
        <h2 className="mb-4 font-semibold text-slate-900">Photos</h2>
        <ImageManager product={product} canEdit={canEdit} />
      </section>

      <VariantsEditSection product={product} canEdit={canEdit} />

      <p className="text-xs text-slate-400">
        Created {new Date(product.createdAt).toLocaleString()} · Updated {new Date(product.updatedAt).toLocaleString()}
      </p>
    </div>
  )
}

function fieldsOf(product: Product): ProductFieldsValue {
  return { name: product.name, description: product.description ?? '', category: product.category ?? '' }
}

function BasicInfoSection({ product, canEdit }: { product: Product; canEdit: boolean }) {
  const update = useUpdateProduct(product.id)
  const latestVersion = useLatestVersion()
  const [fields, setFields] = useState<ProductFieldsValue>(() => fieldsOf(product))
  const [errors, setErrors] = useState<ProductFieldErrors>({})
  const [message, setMessage] = useState<{ tone: 'success' | 'error'; text: string } | null>(null)

  const saved = fieldsOf(product)
  const dirty = fields.name.trim() !== saved.name || fields.description.trim() !== saved.description || fields.category.trim().replace(/\s+/g, ' ') !== saved.category

  function onSubmit(event: FormEvent) {
    event.preventDefault()
    setMessage(null)
    const e = validateProductFields(fields)
    setErrors(e)
    if (Object.keys(e).length > 0) return
    update.mutate(
      {
        name: fields.name.trim(),
        description: fields.description.trim() || null,
        category: fields.category.trim() || null,
        version: latestVersion(product.id),
      },
      {
        onSuccess: (p) => {
          setFields(fieldsOf(p))
          setMessage({ tone: 'success', text: 'Saved.' })
        },
        onError: (err) => {
          const fe = getApiError(err)?.fieldErrors ?? []
          setErrors(Object.fromEntries(fe.map((f) => [f.field, f.message])))
          setMessage({ tone: 'error', text: productErrorMessage(err) })
        },
      },
    )
  }

  return (
    <form onSubmit={onSubmit} noValidate className="rounded-xl border border-slate-200 bg-white p-4 sm:p-5">
      <h2 className="mb-4 font-semibold text-slate-900">Basic info</h2>
      {message && <Alert tone={message.tone} className="mb-4">{message.text}</Alert>}
      <ProductFields value={fields} onChange={setFields} errors={errors} readOnly={!canEdit} />
      {canEdit && (
        <div className="mt-4 flex justify-end gap-2">
          {dirty && (
            <Button variant="secondary" onClick={() => setFields(saved)} disabled={update.isPending}>
              Discard
            </Button>
          )}
          <Button type="submit" loading={update.isPending} disabled={!dirty}>
            Save info
          </Button>
        </div>
      )}
    </form>
  )
}

/**
 * Variants of a saved product. "Save variants" sends one request per changed or new row, one
 * after another (each uses the version returned by the previous one). It stops at the first
 * error and marks that row; rows saved before it stay saved.
 */
function VariantsEditSection({ product, canEdit }: { product: Product; canEdit: boolean }) {
  const { can } = usePermissions()
  const queryClient = useQueryClient()
  const store = useStoreProduct()
  const latestVersion = useLatestVersion()
  const [rows, setRows] = useState<VariantRow[]>(() => product.variants.map(rowFromVariant))
  const [errors, setErrors] = useState<Record<string, RowErrors>>({})
  const [message, setMessage] = useState<{ tone: 'success' | 'error'; text: string } | null>(null)
  const [saving, setSaving] = useState(false)

  const saved = useMemo(() => new Map(product.variants.map((v) => [v.variantId, v])), [product.variants])
  const dirtyCount = rows.filter((r) => isDirty(r, r.variantId ? saved.get(r.variantId) : undefined)).length
  const showCost = canEdit || can('REPORT_VIEW')
  const showStock = product.variants.some((v) => v.stock !== undefined)

  async function saveAll() {
    setMessage(null)
    const validation = validateRows(rows)
    setErrors(validation)
    if (Object.keys(validation).length > 0) {
      setMessage({ tone: 'error', text: 'Please fix the highlighted fields.' })
      return
    }

    setSaving(true)
    let current = rows
    let savedCount = 0
    const latest = () => queryClient.getQueryData<Product>(productKeys.detail(product.id)) ?? product
    try {
      for (const row of rows) {
        let updated: Product | null = null
        try {
          if (!row.variantId) {
            updated = await productsApi.addVariant(product.id, { ...toVariantRequest(row), version: latestVersion(product.id) })
          } else {
            const savedVariant = latest().variants.find((v) => v.variantId === row.variantId)
            const changes = savedVariant ? changedFields(row, savedVariant) : null
            if (!changes) continue
            updated = await productsApi.updateVariant(product.id, row.variantId, { ...changes, version: latestVersion(product.id) })
          }
        } catch (e) {
          setErrors({ [row.key]: rowErrorsOf(e) })
          setMessage({
            tone: 'error',
            text: `${savedCount > 0 ? `${savedCount} saved. ` : ''}${row.sku || 'A variant'}: ${productErrorMessage(e)}`,
          })
          return
        }
        store(updated)
        savedCount++
        // Replace the row with the server's version (a new row gets its variantId here).
        const sku = row.sku.trim().toUpperCase()
        const fresh = updated.variants.find((v) => (row.variantId ? v.variantId === row.variantId : v.sku === sku))
        if (fresh) current = current.map((r) => (r.key === row.key ? rowFromVariant(fresh) : r))
      }
      setErrors({})
      setMessage({ tone: 'success', text: savedCount > 0 ? `Saved ${savedCount} variant${savedCount === 1 ? '' : 's'}.` : 'Nothing to save.' })
    } finally {
      setRows(current)
      setSaving(false)
    }
  }

  return (
    <section className="rounded-xl border border-slate-200 bg-white p-4 sm:p-5">
      <div className="mb-4 flex flex-wrap items-center justify-between gap-2">
        <div>
          <h2 className="font-semibold text-slate-900">Variants</h2>
          <p className="text-sm text-slate-500">
            Prices in taka (৳). SKUs cannot change after saving; to fix one, deactivate the variant and add a new one.
          </p>
        </div>
        {canEdit && (
          <div className="flex gap-2">
            {dirtyCount > 0 && (
              <Button
                variant="secondary"
                disabled={saving}
                onClick={() => {
                  setRows(product.variants.map(rowFromVariant))
                  setErrors({})
                  setMessage(null)
                }}
              >
                Discard
              </Button>
            )}
            <Button onClick={() => void saveAll()} loading={saving} disabled={dirtyCount === 0}>
              Save variants{dirtyCount > 0 ? ` (${dirtyCount})` : ''}
            </Button>
          </div>
        )}
      </div>
      {message && <Alert tone={message.tone} className="mb-4">{message.text}</Alert>}
      <VariantsSection
        rows={rows}
        onRowsChange={setRows}
        errors={errors}
        readOnly={!canEdit}
        showCost={showCost}
        showStock={showStock}
        productName={product.name}
        saved={saved}
      />
    </section>
  )
}
