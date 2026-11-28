import { Select } from '../../../components/ui/Select'
import { TextField } from '../../../components/ui/TextField'
import type { DeliveryZone } from '../../../types/shop'
import { suggestZone, ZONE_OPTIONS, type AddressErrors, type AddressForm } from '../customerForm'

/**
 * One address. The delivery zone follows the district (Dhaka → inside) until the user picks one
 * by hand; it decides the default delivery charge of an order.
 */
export function AddressFields({ value, onChange, errors, showDefault }: {
  value: AddressForm
  onChange: (next: AddressForm) => void
  errors: AddressErrors
  /** Show the "default address" checkbox. */
  showDefault?: boolean
}) {
  const set = (field: 'label' | 'line' | 'area' | 'note') => (e: { target: { value: string } }) => onChange({ ...value, [field]: e.target.value })

  return (
    <div className="grid gap-4 sm:grid-cols-2">
      <TextField label="Full address" placeholder="House 12, Road 5" className="sm:col-span-2" value={value.line} onChange={set('line')} error={errors.line} />
      <TextField label="Area / thana (optional)" placeholder="Mirpur" value={value.area} onChange={set('area')} error={errors.area} />
      <TextField
        label="District"
        placeholder="Dhaka"
        value={value.district}
        onChange={(e) => {
          const district = e.target.value
          onChange({ ...value, district, zone: value.zoneTouched ? value.zone : suggestZone(district) })
        }}
        error={errors.district}
      />
      <div>
        <Select
          label="Delivery zone"
          options={ZONE_OPTIONS}
          value={value.zone}
          onChange={(e) => onChange({ ...value, zone: e.target.value as DeliveryZone, zoneTouched: true })}
        />
        <p className="mt-1.5 text-xs text-slate-500">
          {errors.zone ?? (value.zoneTouched ? 'Decides the default delivery charge.' : 'Suggested from the district. Change it if couriers treat this area differently.')}
        </p>
      </div>
      <TextField label="Label (optional)" placeholder="Home, Office" value={value.label} onChange={set('label')} error={errors.label} />
      <TextField label="Landmark (optional)" placeholder="Opposite the mosque" className="sm:col-span-2" value={value.note} onChange={set('note')} error={errors.note} />
      {showDefault && (
        <label className="flex items-center gap-2 text-sm text-slate-700 sm:col-span-2">
          <input
            type="checkbox"
            checked={value.isDefault}
            onChange={(e) => onChange({ ...value, isDefault: e.target.checked })}
            className="h-4 w-4 rounded border-slate-300 text-indigo-600 focus:ring-indigo-500"
          />
          Use as default address (pre-filled on new orders)
        </label>
      )}
    </div>
  )
}
