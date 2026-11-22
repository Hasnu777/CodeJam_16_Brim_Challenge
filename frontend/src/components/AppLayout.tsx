import { AppShell, Badge, Burger, Group, NavLink, ScrollArea, Select, Text, Title, Tooltip } from '@mantine/core'
import { useDisclosure } from '@mantine/hooks'
import { useQuery } from '@tanstack/react-query'
import {
  IconAlertOctagon, IconChartLine, IconCash, IconFileInvoice, IconScale, IconHome, IconInbox, IconListCheck, IconBuildingStore, IconGavel, IconShieldCheck,
} from '@tabler/icons-react'
import { Link, Outlet, useLocation, useNavigate } from 'react-router-dom'
import { api } from '../api/client'
import type { Role, UserSummary } from '../api/types'
import { useAuth } from '../auth/AuthContext'
import { ROLE_LABEL } from '../lib/format'
import { notifyError } from './notify'

interface NavItem { to: string; label: string; icon: React.ReactNode; roles: Role[] }

export const NAV: NavItem[] = [
  { to: '/', label: 'Home', icon: <IconHome size={19} />, roles: ['EMP', 'CLK', 'APR', 'FM', 'ADM', 'AUD'] },
  { to: '/inbox', label: 'Inbox', icon: <IconInbox size={19} />, roles: ['CLK', 'ADM', 'AUD'] },
  { to: '/invoices', label: 'Invoices', icon: <IconFileInvoice size={19} />, roles: ['EMP', 'CLK', 'APR', 'FM', 'ADM', 'AUD'] },
  { to: '/approvals', label: 'My Approvals', icon: <IconListCheck size={19} />, roles: ['APR', 'FM', 'ADM'] },
  { to: '/payments', label: 'Payments', icon: <IconCash size={19} />, roles: ['CLK', 'FM', 'ADM', 'AUD'] },
  { to: '/vendors', label: 'Vendors', icon: <IconBuildingStore size={19} />, roles: ['CLK', 'APR', 'FM', 'ADM', 'AUD'] },
  { to: '/policies', label: 'Policies', icon: <IconGavel size={19} />, roles: ['FM', 'ADM', 'AUD'] },
  { to: '/audit', label: 'Audit Log', icon: <IconShieldCheck size={19} />, roles: ['ADM', 'AUD'] },
  { to: '/fraud', label: 'Fraud Alerts', icon: <IconAlertOctagon size={19} />, roles: ['CLK', 'FM', 'ADM', 'AUD'] },
  { to: '/reconciliation', label: 'Reconciliation', icon: <IconScale size={19} />, roles: ['FM', 'ADM', 'AUD'] },
  { to: '/analytics', label: 'Spend Analytics', icon: <IconChartLine size={19} />, roles: ['APR', 'FM', 'ADM', 'AUD'] },
]

/** "Maintenance Technician (Employee)": the person's job title, plus the app role when the title does not already say it. */
function jobTitle(u: UserSummary): string {
  const role = ROLE_LABEL[u.role!] ?? u.role ?? ''
  const title = u.title ?? ''
  if (!title) return role
  return title.toLowerCase().includes(role.toLowerCase()) || role.toLowerCase().includes(title.toLowerCase()) ? title : `${title} (${role})`
}

/** Real session login (POST /api/auth/login via AuthContext), styled as the Lovable header selector. */
export function UserSwitcher({ width, className }: { width?: number; className?: string }) {
  const { me, login } = useAuth()
  const nav = useNavigate()
  const users = useQuery({ queryKey: ['switcher-users'], queryFn: () => api.get<UserSummary[]>('/auth/users') })
  return (
    <Select
      w={width}
      className={className}
      searchable
      placeholder="Switch user"
      aria-label="Switch user"
      value={me?.id ?? null}
      data={(users.data ?? []).map((u) => ({
        value: u.id!,
        label: `${u.fullName} · ${jobTitle(u)}${u.status !== 'active' ? ` (${u.status!.replace('_', ' ')})` : ''}`,
        disabled: u.status === 'terminated',
      }))}
      onChange={async (v) => {
        if (!v || v === me?.id) return
        try {
          await login(v)
          nav('/')
        } catch (e) {
          notifyError(e)
        }
      }}
    />
  )
}

/** Workspace shell from the Lovable design (WorkspaceShell.jsx), on the app's real auth, router and providers. */
export default function AppLayout() {
  const { me } = useAuth()
  const [opened, { toggle, close }] = useDisclosure(false)
  const loc = useLocation()
  if (!me) return null
  const items = NAV.filter((n) => n.roles.includes(me.role))
  return (
    <AppShell className="ap-workspace" header={{ height: 76 }} navbar={{ width: 240, breakpoint: 'sm', collapsed: { mobile: !opened } }} padding="lg">
      <AppShell.Header>
        <Group h="100%" px="lg" justify="space-between" wrap="nowrap">
          <Group gap="sm" wrap="nowrap" style={{ flexShrink: 0 }}>
            <Burger opened={opened} onClick={toggle} hiddenFrom="sm" size="sm" aria-label="Toggle navigation" />
            <Title order={3} className="ap-brand"><span>Harborline</span> AP</Title>
          </Group>
          <Group gap="md" wrap="nowrap" className="ap-header-right">
            <Text size="xs" c="dimmed" visibleFrom="lg">Business Date <b>{me.businessDate}</b></Text>
            {me.actingFor.length > 0 && (
              <Tooltip label={`You are delegate for ${me.actingFor.map((a) => a.fullName).join(', ')}`}>
                <Badge color="grape" variant="light" visibleFrom="md">Delegate For {me.actingFor.map((a) => a.fullName).join(', ')}</Badge>
              </Tooltip>
            )}
            <UserSwitcher className="ap-user-switch" />
          </Group>
        </Group>
      </AppShell.Header>
      <AppShell.Navbar p="sm">
        <ScrollArea pt="xs">
          {items.map((n) => (
            <NavLink key={n.to} component={Link} to={n.to} label={n.label} leftSection={n.icon} className="ap-nav-link"
              active={n.to === '/' ? loc.pathname === '/' : loc.pathname.startsWith(n.to)} onClick={close} />
          ))}
          {/* Below the header breakpoints, the business date and delegate status move here so they stay reachable. */}
          <Text size="xs" c="dimmed" hiddenFrom="lg" mt="md" px="sm">Business Date <b>{me.businessDate}</b></Text>
          {me.actingFor.length > 0 && (
            <Text size="xs" c="grape" hiddenFrom="md" mt={4} px="sm">Delegate For {me.actingFor.map((a) => a.fullName).join(', ')}</Text>
          )}
        </ScrollArea>
      </AppShell.Navbar>
      <AppShell.Main>
        <div className="ap-page-content">
          <Outlet />
        </div>
      </AppShell.Main>
    </AppShell>
  )
}
