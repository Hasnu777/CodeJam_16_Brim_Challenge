// Formatting only happens in the UI; the API carries plain decimal strings and ISO dates.

const cad = new Intl.NumberFormat('en-CA', { style: 'currency', currency: 'CAD' })
const plain = new Intl.NumberFormat('en-CA', { minimumFractionDigits: 2, maximumFractionDigits: 2 })

export function money(v: unknown, currency = 'CAD'): string {
  if (v === null || v === undefined || v === '') return '—'
  const n = Number(v)
  if (Number.isNaN(n)) return String(v)
  if (currency === 'CAD') return cad.format(n)
  return `${currency} ${plain.format(n)}`
}

export function num(v: unknown): number {
  const n = Number(v)
  return Number.isNaN(n) ? 0 : n
}

/** "Nov 2, 2026" from ISO date; timestamps shown in Toronto time. */
export function date(v: unknown): string {
  if (!v) return '—'
  const s = String(v)
  if (/^\d{4}-\d{2}-\d{2}$/.test(s)) {
    const [y, m, d] = s.split('-').map(Number)
    return new Date(y, m - 1, d).toLocaleDateString('en-CA', { month: 'short', day: 'numeric', year: 'numeric' })
  }
  return new Date(s).toLocaleDateString('en-CA', { month: 'short', day: 'numeric', year: 'numeric', timeZone: 'America/Toronto' })
}

export function dateTime(v: unknown): string {
  if (!v) return '—'
  return new Date(String(v)).toLocaleString('en-CA', {
    month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit', timeZone: 'America/Toronto',
  })
}

export const ROLE_LABEL: Record<string, string> = {
  EMP: 'Employee',
  CLK: 'AP Clerk',
  APR: 'Approver',
  FM: 'Finance Manager',
  ADM: 'Controller / Admin',
  AUD: 'Auditor',
}

export const ROLE_COLOR: Record<string, string> = {
  EMP: 'gray',
  CLK: 'blue',
  APR: 'teal',
  FM: 'violet',
  ADM: 'indigo',
  AUD: 'orange',
}

/** Same three colours everywhere: red = blocks payment, amber = needs review, grey = info. */
export const SEVERITY_COLOR: Record<string, string> = { BLOCK: 'red', WARN: 'yellow', INFO: 'gray' }

export const STATUS_COLOR: Record<string, string> = {
  RECEIVED: 'gray',
  EXTRACTED: 'gray',
  IN_REVIEW: 'blue',
  PENDING_APPROVAL: 'grape',
  APPROVED: 'teal',
  SCHEDULED: 'cyan',
  RELEASED: 'indigo',
  PAID: 'green',
  ON_HOLD: 'red',
  REJECTED: 'red',
  DUPLICATE: 'orange',
  SENT: 'indigo',
  SETTLED: 'green',
  FAILED: 'red',
}

export function statusLabel(s: string): string {
  return s.replace(/_/g, ' ').toLowerCase().replace(/^\w/, (c) => c.toUpperCase())
}
