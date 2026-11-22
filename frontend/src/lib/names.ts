// Human-readable names for the ids the API uses (U003, D07, V026, 5010, P05, dept_manager...).
// The API keeps ids (they are stable keys and appear in the audit log); only the UI turns them into names.
// components/NamesProvider loads the reference lists once per session and also fills a module-level registry, so
// plain functions such as notifyError() and humanize() work outside React components.
import { createContext, useContext } from 'react'

export const APPROVAL_ROLE_LABEL: Record<string, string> = {
  dept_manager: 'Department Manager',
  finance_manager: 'Finance Manager',
  controller_admin: 'Controller',
  cfo: 'CFO',
  employee: 'Employee',
  ap_clerk: 'AP Clerk',
  approver: 'Approver',
  auditor: 'Auditor',
}

export type Registry = {
  people: Record<string, string>
  departments: Record<string, string>
  vendors: Record<string, string>
  gl: Record<string, string>
  policies: Record<string, string>
}

let registry: Registry = { people: {}, departments: {}, vendors: {}, gl: {}, policies: {} }

export function setRegistry(r: Registry) {
  registry = r
}

export type Names = {
  /** "Elise Tremblay" for U003; the id itself if unknown; "—" for empty. */
  person: (id?: string | null) => string
  /** "Facilities" for D07. */
  dept: (id?: string | null) => string
  /** "Queen Street Properties Inc." for V036. */
  vendor: (id?: string | null) => string
  /** "5010 · Freight" for 5010. */
  gl: (code?: string | null) => string
  /** "Department manager" for dept_manager. */
  role: (key?: string | null) => string
  /** "Invoice total over $10,000 CAD" for P02 (when the viewer may read policies), else the id. */
  policy: (id?: string | null) => string
  /** Replace ids inside free text (server messages, routing notes, audit details) with names. */
  text: (s?: string | null) => string
}

const dash = (v?: string | null) => (v === null || v === undefined || v === '' ? '—' : null)

export function buildNames(r: Registry): Names {
  return {
    person: (id) => dash(id) ?? r.people[id!] ?? id!,
    dept: (id) => dash(id) ?? r.departments[id!] ?? id!,
    vendor: (id) => dash(id) ?? r.vendors[id!] ?? id!,
    gl: (code) => dash(code) ?? (r.gl[code!] ? `${code} · ${r.gl[code!]}` : code!),
    role: (key) => dash(key) ?? APPROVAL_ROLE_LABEL[key!] ?? key!.replace(/_/g, ' '),
    // The policy's condition in words ("Invoice total over $10,000 CAD"), without the "-> roles" part.
    policy: (id) => dash(id) ?? (r.policies[id!] ? humanizeWith(r, r.policies[id!].split(' -> ')[0]) : id!),
    text: (s) => humanizeWith(r, s),
  }
}

function humanizeWith(r: Registry, s?: string | null): string {
  if (!s) return s ?? ''
  const known = (id: string) => !!(r.people[id] ?? r.departments[id] ?? r.vendors[id])
  return s
    // "Liam O'Connor (U012)" -> "Liam O'Connor": the name is already there, so drop the id in brackets.
    .replace(/ \((U\d{3}|D0\d|V\d{3})\)/g, (m, id) => (known(id) ? '' : m))
    // "(V026, 4 paid invoices" -> "(4 paid invoices" for the same reason.
    .replace(/\((U\d{3}|D0\d|V\d{3}), /g, (m, id) => (known(id) ? '(' : m))
    .replace(/\bU\d{3}\b/g, (m) => r.people[m] ?? m)
    .replace(/\bD0\d\b/g, (m) => r.departments[m] ?? m)
    .replace(/\bV\d{3}\b/g, (m) => r.vendors[m] ?? m)
    .replace(/\b(dept_manager|finance_manager|controller_admin|cfo)\b/g, (m) => APPROVAL_ROLE_LABEL[m])
    // Email ids (EM-042) are internal keys: "From email EM-042: x.pdf" -> "From email: x.pdf".
    .replace(/\s*\(?\bEM-\d{3,}\b\)?/g, '')
}

/** Outside React (notifications, helpers): uses whatever the provider has loaded so far. */
export function humanize(s?: string | null): string {
  return humanizeWith(registry, s)
}

export const NamesContext = createContext<Names>(buildNames(registry))

export function useNames(): Names {
  return useContext(NamesContext)
}
