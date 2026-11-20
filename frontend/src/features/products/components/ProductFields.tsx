import { useId } from 'react'
import { TextArea } from '../../../components/ui/TextArea'
import { TextField } from '../../../components/ui/TextField'
import type { ProductFieldErrors, ProductFieldsValue } from '../productForm'
import { useCategories } from '../queries'

interface Props {
  value: ProductFieldsValue
  onChange: (value: ProductFieldsValue) => void
  errors: ProductFieldErrors
  readOnly?: boolean
}

/**
 * Name, description and category. The category input suggests existing categories (datalist);
 * typing a name that does not exist yet simply creates that category with the product.
 */
export function ProductFields({ value, onChange, errors, readOnly }: Props) {
  const listId = useId()
  const categories = useCategories()
  const typed = value.category.trim().replace(/\s+/g, ' ')
  const isNewCategory =
    typed !== '' && categories.data !== undefined && !categories.data.some((c) => c.name.toLowerCase() === typed.toLowerCase())

  return (
    <div className="grid gap-4 sm:grid-cols-2">
      <TextField
        label="Product name"
        className="sm:col-span-2"
        value={value.name}
        onChange={(e) => onChange({ ...value, name: e.target.value })}
        error={errors.name}
        maxLength={200}
        disabled={readOnly}
        placeholder="e.g. Cotton T-Shirt"
      />
      <div>
        <TextField
          label="Category"
          list={listId}
          value={value.category}
          onChange={(e) => onChange({ ...value, category: e.target.value })}
          error={errors.category}
          maxLength={50}
          disabled={readOnly}
          placeholder="e.g. T-Shirts"
          hint={isNewCategory && !readOnly ? `New category "${typed}" will be created.` : 'Pick one or type a new one.'}
        />
        <datalist id={listId}>
          {categories.data?.map((c) => (
            <option key={c.name} value={c.name} />
          ))}
        </datalist>
      </div>
      <TextArea
        label="Description (optional)"
        className="sm:col-span-2"
        rows={3}
        value={value.description}
        onChange={(e) => onChange({ ...value, description: e.target.value })}
        error={errors.description}
        maxLength={5000}
        disabled={readOnly}
      />
    </div>
  )
}
