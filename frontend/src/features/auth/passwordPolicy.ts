/** Same rules as the backend's @ValidPassword (docs/SECURITY.md §1). The backend still decides. */
export function passwordRuleChecks(password: string) {
  return [
    { label: '8–72 characters', ok: password.length >= 8 && password.length <= 72 },
    { label: 'At least one letter', ok: /\p{L}/u.test(password) },
    { label: 'At least one digit', ok: /\p{Nd}/u.test(password) },
  ]
}

export function isPasswordValid(password: string): boolean {
  return passwordRuleChecks(password).every((rule) => rule.ok)
}

/** Bangladeshi mobile as the backend accepts it: optional +88 / 88, then 01[3-9] + 8 digits. */
export const BD_MOBILE = /^(\+?88)?01[3-9]\d{8}$/

export const SIX_DIGITS = /^\d{6}$/
