/** Joins class names, skipping falsy ones: cn('a', isOn && 'b') → 'a b' or 'a'. */
export function cn(...classes: Array<string | false | null | undefined>): string {
  return classes.filter(Boolean).join(' ')
}
