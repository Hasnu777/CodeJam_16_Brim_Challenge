import { useQuery } from '@tanstack/react-query'
import { useEffect, useMemo, type ReactNode } from 'react'
import { api } from '../api/client'
import type { ApprovalPolicy, Department, GlAccount, UserSummary, VendorRow } from '../api/types'
import { useAuth } from '../auth/AuthContext'
import { buildNames, NamesContext, setRegistry, type Registry } from '../lib/names'

/** Loads people, departments, GL accounts, vendors and policies once, so every screen can show names, not ids. */
export function NamesProvider({ children }: { children: ReactNode }) {
  const { me } = useAuth()
  const role = me?.role
  const forever = { staleTime: Infinity, gcTime: Infinity }
  const people = useQuery({ queryKey: ['names', 'people'], queryFn: () => api.get<UserSummary[]>('/auth/users'), ...forever })
  const depts = useQuery({ queryKey: ['names', 'departments'], queryFn: () => api.get<Department[]>('/ref/departments'), enabled: !!me, ...forever })
  const gl = useQuery({ queryKey: ['names', 'gl'], queryFn: () => api.get<GlAccount[]>('/ref/gl-accounts'), enabled: !!me, ...forever })
  // Role-gated lists: only fetched for roles the server lets read them; others fall back to the id.
  const vendors = useQuery({ queryKey: ['names', 'vendors', role], queryFn: () => api.get<VendorRow[]>('/vendors'),
    enabled: !!role && ['CLK', 'FM', 'ADM', 'AUD', 'APR'].includes(role), staleTime: 60_000 })
  const policies = useQuery({ queryKey: ['names', 'policies', role], queryFn: () => api.get<ApprovalPolicy[]>('/policies'),
    enabled: !!role && ['FM', 'ADM', 'AUD'].includes(role), staleTime: 60_000 })

  const r = useMemo<Registry>(() => ({
    people: Object.fromEntries((people.data ?? []).map((u) => [u.id!, u.fullName!])),
    departments: Object.fromEntries((depts.data ?? []).map((d) => [d.id!, d.name!])),
    vendors: Object.fromEntries((vendors.data ?? []).map((v) => [v.vendor!.id!, v.vendor!.legalName!])),
    gl: Object.fromEntries((gl.data ?? []).map((g) => [g.accountNumber!, g.name!])),
    policies: Object.fromEntries((policies.data ?? []).map((p) => [p.id!, p.sourceText ?? p.id!])),
  }), [people.data, depts.data, vendors.data, gl.data, policies.data])
  const names = useMemo(() => buildNames(r), [r])
  // Notifications and other non-React helpers read the latest lists through humanize().
  useEffect(() => { setRegistry(r) }, [r])

  return <NamesContext.Provider value={names}>{children}</NamesContext.Provider>
}
