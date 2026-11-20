import { useState, type KeyboardEvent } from 'react'
import { CloseIcon, PlusIcon } from '../../../components/icons'
import { Button } from '../../../components/ui/Button'
import { MoneyInput } from '../../../components/ui/MoneyInput'
import { TextField } from '../../../components/ui/TextField'
import { MAX_OPTIONS, combinations, type VariantOption } from '../variantRows'

interface Props {
  options: VariantOption[]
  onOptionsChange: (options: VariantOption[]) => void
  skuPrefix: string
  onSkuPrefixChange: (value: string) => void
  defaultPrice: string
  onDefaultPriceChange: (value: string) => void
  /** Adds the missing combinations as rows. */
  onGenerate: () => void
  /** Result of the last generate, e.g. "Added 6 variants". */
  message?: string | null
}

/**
 * Step 1 of the variant builder: options with their values (Size: S, M, L · Color: Red, Blue).
 * "Generate" turns every combination into a row of the variant table, where SKU and prices are
 * then edited one by one.
 */
export function VariantBuilder({ options, onOptionsChange, skuPrefix, onSkuPrefixChange, defaultPrice, onDefaultPriceChange, onGenerate, message }: Props) {
  const count = combinations(options).length

  function update(index: number, option: VariantOption) {
    onOptionsChange(options.map((o, i) => (i === index ? option : o)))
  }

  return (
    <div className="rounded-xl border border-dashed border-slate-300 bg-slate-50/60 p-4">
      <h3 className="text-sm font-semibold text-slate-900">Options</h3>
      <p className="mt-0.5 text-xs text-slate-500">Add options like Size or Color, then generate one variant per combination.</p>

      <div className="mt-3 space-y-3">
        {options.map((option, index) => (
          <OptionEditor
            key={index}
            option={option}
            onChange={(o) => update(index, o)}
            onRemove={() => onOptionsChange(options.filter((_, i) => i !== index))}
          />
        ))}
      </div>

      {options.length < MAX_OPTIONS && (
        <Button variant="ghost" className="mt-2 px-2" onClick={() => onOptionsChange([...options, { name: options.length === 0 ? 'Size' : '', values: [] }])}>
          <PlusIcon /> Add option
        </Button>
      )}

      {options.length > 0 && (
        <div className="mt-4 grid gap-3 border-t border-slate-200 pt-4 sm:grid-cols-[1fr_1fr_auto] sm:items-end">
          <TextField label="SKU prefix" value={skuPrefix} onChange={(e) => onSkuPrefixChange(e.target.value)} placeholder="e.g. TEE" hint="SKUs become PREFIX-RED-M" />
          <MoneyInput label="Price for new variants (৳)" value={defaultPrice} onChange={(e) => onDefaultPriceChange(e.target.value)} hint="You can change each one after." />
          <Button onClick={onGenerate} disabled={count === 0} className="sm:mb-6">
            Generate {count > 0 ? `${count} ` : ''}combination{count === 1 ? '' : 's'}
          </Button>
        </div>
      )}
      {message && (
        <p className="mt-2 text-sm text-slate-600" role="status">
          {message}
        </p>
      )}
    </div>
  )
}

function OptionEditor({ option, onChange, onRemove }: { option: VariantOption; onChange: (o: VariantOption) => void; onRemove: () => void }) {
  const [draft, setDraft] = useState('')

  /** "S, M, L" typed at once is split into three values; duplicates (any case) are ignored. */
  function addDraft() {
    const fresh = draft
      .split(',')
      .map((v) => v.trim().replace(/\s+/g, ' '))
      .filter((v) => v && v.length <= 50)
    const values = [...option.values]
    for (const v of fresh) {
      if (!values.some((existing) => existing.toLowerCase() === v.toLowerCase())) values.push(v)
    }
    onChange({ ...option, values })
    setDraft('')
  }

  function onKeyDown(e: KeyboardEvent<HTMLInputElement>) {
    if (e.key === 'Enter' || e.key === ',') {
      e.preventDefault()
      addDraft()
    } else if (e.key === 'Backspace' && draft === '' && option.values.length > 0) {
      onChange({ ...option, values: option.values.slice(0, -1) })
    }
  }

  return (
    <div className="rounded-lg border border-slate-200 bg-white p-3">
      <div className="flex items-start gap-2">
        <TextField
          label="Option name"
          className="w-36 shrink-0"
          value={option.name}
          maxLength={30}
          onChange={(e) => onChange({ ...option, name: e.target.value })}
          placeholder="Size"
        />
        <div className="min-w-0 flex-1">
          <label className="mb-1.5 block text-sm font-medium text-slate-700">Values</label>
          <div className="flex min-h-[42px] flex-wrap items-center gap-1.5 rounded-lg border border-slate-300 bg-white px-2 py-1.5 focus-within:border-indigo-500 focus-within:ring-2 focus-within:ring-indigo-100">
            {option.values.map((value) => (
              <span key={value} className="inline-flex items-center gap-1 rounded-md bg-indigo-50 px-2 py-0.5 text-sm text-indigo-700">
                {value}
                <button
                  type="button"
                  onClick={() => onChange({ ...option, values: option.values.filter((v) => v !== value) })}
                  className="rounded text-indigo-400 hover:text-indigo-700"
                  aria-label={`Remove ${value}`}
                >
                  <span aria-hidden="true" className="px-0.5 text-base leading-none">×</span>
                </button>
              </span>
            ))}
            <input
              value={draft}
              onChange={(e) => setDraft(e.target.value)}
              onKeyDown={onKeyDown}
              onBlur={() => draft.trim() && addDraft()}
              aria-label={`Add a value to ${option.name || 'this option'}`}
              placeholder={option.values.length === 0 ? 'Type a value, press Enter (e.g. S, M, L)' : 'Add more…'}
              className="min-w-[8rem] flex-1 border-0 bg-transparent p-1 text-sm outline-none"
            />
          </div>
        </div>
        <button type="button" onClick={onRemove} className="mt-7 rounded-md p-2 text-slate-400 hover:bg-slate-100 hover:text-slate-700" aria-label="Remove option">
          <CloseIcon />
        </button>
      </div>
    </div>
  )
}
