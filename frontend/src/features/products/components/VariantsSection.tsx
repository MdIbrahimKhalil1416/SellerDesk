import { useState } from 'react'
import { PlusIcon } from '../../../components/icons'
import { Button } from '../../../components/ui/Button'
import type { Variant } from '../../../types/product'
import { emptyRow, generateRows, MAX_VARIANTS, suggestSku, type RowErrors, type VariantOption, type VariantRow } from '../variantRows'
import { VariantBuilder } from './VariantBuilder'
import { VariantTable } from './VariantTable'

interface Props {
  rows: VariantRow[]
  onRowsChange: (rows: VariantRow[]) => void
  errors: Record<string, RowErrors>
  readOnly: boolean
  showCost: boolean
  showStock: boolean
  /** Used for the default SKU prefix. */
  productName: string
  saved?: Map<string, Variant>
}

/** Options builder (step 1) + variant table (step 2), shared by the create and the edit page. */
export function VariantsSection({ rows, onRowsChange, errors, readOnly, showCost, showStock, productName, saved }: Props) {
  const [options, setOptions] = useState<VariantOption[]>([])
  /** null = follow the product name. */
  const [skuPrefix, setSkuPrefix] = useState<string | null>(null)
  const [defaultPrice, setDefaultPrice] = useState('')
  const [message, setMessage] = useState<string | null>(null)

  const prefix = skuPrefix ?? suggestSku(productName, []).slice(0, 20)

  function generate() {
    const result = generateRows(rows, options, prefix, defaultPrice)
    onRowsChange(result.rows)
    const parts = [result.added === 0 ? 'No new combinations: every one already has a variant.' : `Added ${result.added} variant${result.added === 1 ? '' : 's'}.`]
    if (result.overLimit > 0) parts.push(`${result.overLimit} skipped: a product can have at most ${MAX_VARIANTS} variants.`)
    setMessage(parts.join(' '))
  }

  return (
    <div className="space-y-4">
      {!readOnly && (
        <VariantBuilder
          options={options}
          onOptionsChange={setOptions}
          skuPrefix={prefix}
          onSkuPrefixChange={setSkuPrefix}
          defaultPrice={defaultPrice}
          onDefaultPriceChange={setDefaultPrice}
          onGenerate={generate}
          message={message}
        />
      )}
      <VariantTable rows={rows} onRowsChange={onRowsChange} errors={errors} readOnly={readOnly} showCost={showCost} showStock={showStock} saved={saved} />
      {!readOnly && rows.length < MAX_VARIANTS && (
        <Button variant="ghost" className="px-2" onClick={() => onRowsChange([...rows, emptyRow()])}>
          <PlusIcon /> Add a variant without options
        </Button>
      )}
    </div>
  )
}
