import { TextField, type TextFieldProps } from './TextField'

/**
 * A text input for an amount in TAKA (৳). The value stays a string while typing ("450.", "0.5"),
 * so nothing jumps around; convert with `parseTakaToPoisha` when sending to the API.
 *
 * `inputMode="decimal"` shows the number keypad with a dot on phones.
 */
export function MoneyInput(props: Omit<TextFieldProps, 'type' | 'inputMode'>) {
  return <TextField type="text" inputMode="decimal" autoComplete="off" placeholder="0.00" {...props} />
}
