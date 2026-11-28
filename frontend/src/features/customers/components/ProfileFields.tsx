import { TextArea } from '../../../components/ui/TextArea'
import { TextField } from '../../../components/ui/TextField'
import { BD_PHONE_EXAMPLE, normalizeBdPhone } from '../../../lib/bdPhone'
import type { ProfileErrors, ProfileForm } from '../customerForm'

/** Name, phones, Facebook link, tags, note. Shared by the create page and the edit form. */
export function ProfileFields({ value, onChange, errors }: { value: ProfileForm; onChange: (field: keyof ProfileForm, v: string) => void; errors: ProfileErrors }) {
  const bind = (field: keyof ProfileForm) => ({
    name: field,
    value: value[field],
    error: errors[field],
    onChange: (e: { target: { value: string } }) => onChange(field, e.target.value),
  })
  const phoneHint = (text: string, fallback: string) => {
    const normalized = normalizeBdPhone(text)
    return normalized && normalized !== text.trim() ? `Will be saved as ${normalized}` : fallback
  }

  return (
    <div className="grid gap-4 sm:grid-cols-2">
      <TextField label="Name" autoComplete="off" maxLength={100} className="sm:col-span-2" {...bind('name')} />
      <TextField
        label="Phone"
        type="tel"
        inputMode="tel"
        placeholder={BD_PHONE_EXAMPLE}
        {...bind('phone')}
        hint={phoneHint(value.phone, `Bangladeshi mobile, e.g. ${BD_PHONE_EXAMPLE} or +880 1712-345678`)}
      />
      <TextField label="Other phone (optional)" type="tel" inputMode="tel" {...bind('altPhone')} hint={phoneHint(value.altPhone, 'A second number, e.g. a family member')} />
      <TextField label="Facebook profile (optional)" type="url" placeholder="https://facebook.com/..." className="sm:col-span-2" {...bind('facebookProfileUrl')} />
      <TextField label="Tags (optional)" placeholder="VIP, Wholesale" className="sm:col-span-2" {...bind('tags')} hint="Separate tags with commas. Up to 10." />
      <TextArea label="Note (optional)" rows={3} maxLength={1000} className="sm:col-span-2" {...bind('note')} placeholder="e.g. Prefers delivery after 6 pm" />
    </div>
  )
}
