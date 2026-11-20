import { useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router'
import { ArrowLeftIcon, ImageIcon } from '../../components/icons'
import { Alert } from '../../components/ui/Alert'
import { Button } from '../../components/ui/Button'
import { usePermissions } from '../auth/usePermissions'
import { ProductFields } from './components/ProductFields'
import { VariantsSection } from './components/VariantsSection'
import { splitCreateErrors, validateProductFields, type ProductFieldErrors, type ProductFieldsValue } from './productForm'
import { productErrorMessage } from './productErrors'
import { useCreateProduct } from './queries'
import { emptyRow, toVariantRequest, validateRows, type RowErrors, type VariantRow } from './variantRows'

/**
 * /products/new. Everything is sent in ONE request when saving; prices are converted from taka
 * to poisha only then. Photos are added on the product page right after (they need its id).
 */
export function ProductCreatePage() {
  const navigate = useNavigate()
  const { can } = usePermissions()
  const create = useCreateProduct()
  const [fields, setFields] = useState<ProductFieldsValue>({ name: '', description: '', category: '' })
  const [fieldErrors, setFieldErrors] = useState<ProductFieldErrors>({})
  const [rows, setRows] = useState<VariantRow[]>(() => [emptyRow()])
  const [rowErrors, setRowErrors] = useState<Record<string, RowErrors>>({})
  const [error, setError] = useState<string | null>(null)

  function onSubmit(event: FormEvent) {
    event.preventDefault()
    setError(null)
    const fErrors = validateProductFields(fields)
    const rErrors = validateRows(rows)
    setFieldErrors(fErrors)
    setRowErrors(rErrors)
    if (rows.length === 0) return setError('Add at least one variant.')
    if (Object.keys(fErrors).length > 0 || Object.keys(rErrors).length > 0) {
      return setError('Please fix the highlighted fields.')
    }

    create.mutate(
      {
        name: fields.name.trim(),
        description: fields.description.trim() || undefined,
        category: fields.category.trim() || undefined,
        variants: rows.map(toVariantRequest),
      },
      {
        onSuccess: (product) =>
          navigate(`/products/${product.id}`, { replace: true, state: { notice: 'Product created. You can add photos now.' } }),
        onError: (e) => {
          const mapped = splitCreateErrors(e, rows.map((r) => r.key))
          setFieldErrors(mapped.fields)
          setRowErrors(mapped.rows)
          // e.g. SKU_ALREADY_EXISTS: the clashing rows are marked, the alert says what to do.
          setError(productErrorMessage(e, 'Could not save the product.'))
        },
      },
    )
  }

  return (
    <form onSubmit={onSubmit} noValidate className="space-y-6 pb-24">
      <div>
        <Link to="/products" className="inline-flex items-center gap-1 text-sm font-medium text-slate-500 hover:text-slate-800">
          <ArrowLeftIcon /> Products
        </Link>
        <h1 className="mt-1 text-2xl font-semibold text-slate-900">New product</h1>
      </div>

      {error && <Alert tone="error">{error}</Alert>}

      <section className="rounded-xl border border-slate-200 bg-white p-4 sm:p-5">
        <h2 className="mb-4 font-semibold text-slate-900">Basic info</h2>
        <ProductFields value={fields} onChange={setFields} errors={fieldErrors} />
      </section>

      <section className="rounded-xl border border-slate-200 bg-white p-4 sm:p-5">
        <h2 className="font-semibold text-slate-900">Variants</h2>
        <p className="mb-4 text-sm text-slate-500">Prices in taka (৳). Stock starts at 0; add opening stock in Inventory later.</p>
        <VariantsSection
          rows={rows}
          onRowsChange={setRows}
          errors={rowErrors}
          readOnly={false}
          showCost={can('PRODUCT_EDIT')}
          showStock={false}
          productName={fields.name}
        />
      </section>

      <section className="flex items-center gap-3 rounded-xl border border-dashed border-slate-300 bg-white p-4 text-sm text-slate-500">
        <ImageIcon />
        Photos can be added right after saving.
      </section>

      {/* Sticky on phones so the button is always reachable below a long variant list. */}
      <div className="fixed inset-x-0 bottom-0 z-20 border-t border-slate-200 bg-white/95 p-3 backdrop-blur lg:left-64">
        <div className="mx-auto flex max-w-6xl justify-end gap-2 px-1 sm:px-4">
          <Button variant="secondary" onClick={() => navigate('/products')} disabled={create.isPending}>
            Cancel
          </Button>
          <Button type="submit" loading={create.isPending}>
            {create.isPending ? 'Saving…' : 'Save product'}
          </Button>
        </div>
      </div>
    </form>
  )
}
