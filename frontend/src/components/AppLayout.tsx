import { AppShell, Badge, Burger, Group, NavLink, Select, Stack, Text, Title, Tooltip } from '@mantine/core'
import { useDisclosure } from '@mantine/hooks'
import { useQuery } from '@tanstack/react-query'
import {
  IconAlertOctagon, IconChartBar, IconCash, IconFileInvoice, IconScale, IconHome, IconInbox, IconListCheck, IconBuildingStore, IconGavel, IconShieldCheck,
} from '@tabler/icons-react'
import { Link, Outlet, useLocation, useNavigate } from 'react-router-dom'
import { api } from '../api/client'
import type { Role, UserSummary } from '../api/types'
import { useAuth } from '../auth/AuthContext'
import { ROLE_COLOR, ROLE_LABEL } from '../lib/format'
import { notifyError } from './notify'

interface NavItem { to: string; label: string; icon: React.ReactNode; roles: Role[] }

export const NAV: NavItem[] = [
  { to: '/', label: 'Home', icon: <IconHome size={18} />, roles: ['EMP', 'CLK', 'APR', 'FM', 'ADM', 'AUD'] },
  { to: '/inbox', label: 'Inbox', icon: <IconInbox size={18} />, roles: ['CLK', 'ADM', 'AUD'] },
  { to: '/invoices', label: 'Invoices', icon: <IconFileInvoice size={18} />, roles: ['EMP', 'CLK', 'APR', 'FM', 'ADM', 'AUD'] },
  { to: '/approvals', label: 'My approvals', icon: <IconListCheck size={18} />, roles: ['APR', 'FM', 'ADM'] },
  { to: '/payments', label: 'Payments', icon: <IconCash size={18} />, roles: ['CLK', 'FM', 'ADM', 'AUD'] },
  { to: '/vendors', label: 'Vendors', icon: <IconBuildingStore size={18} />, roles: ['CLK', 'APR', 'FM', 'ADM', 'AUD'] },
  { to: '/policies', label: 'Policies', icon: <IconGavel size={18} />, roles: ['FM', 'ADM', 'AUD'] },
  { to: '/audit', label: 'Audit log', icon: <IconShieldCheck size={18} />, roles: ['ADM', 'AUD'] },
  { to: '/fraud', label: 'Fraud alerts', icon: <IconAlertOctagon size={18} />, roles: ['CLK', 'FM', 'ADM', 'AUD'] },
  { to: '/reconciliation', label: 'Reconciliation', icon: <IconScale size={18} />, roles: ['FM', 'ADM', 'AUD'] },
  { to: '/analytics', label: 'Spend analytics', icon: <IconChartBar size={18} />, roles: ['APR', 'FM', 'ADM', 'AUD'] },
]

export function UserSwitcher({ width = 300 }: { width?: number }) {
  const { me, login } = useAuth()
  const nav = useNavigate()
  const users = useQuery({ queryKey: ['switcher-users'], queryFn: () => api.get<UserSummary[]>('/auth/users') })
  return (
    <Select
      w={width}
      searchable
      placeholder="Switch user"
      aria-label="Switch user"
      value={me?.id ?? null}
      data={(users.data ?? []).map((u) => ({
        value: u.id!,
        label: `${u.fullName} · ${ROLE_LABEL[u.role!] ?? u.role}${u.status !== 'active' ? ` (${u.status!.replace('_', ' ')})` : ''}`,
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

export default function AppLayout() {
  const { me } = useAuth()
  const [opened, { toggle }] = useDisclosure()
  const loc = useLocation()
  if (!me) return null
  const items = NAV.filter((n) => n.roles.includes(me.role))
  return (
    <AppShell header={{ height: 60 }} navbar={{ width: 220, breakpoint: 'sm', collapsed: { mobile: !opened } }} padding="md">
      <AppShell.Header>
        <Group h="100%" px="md" justify="space-between" wrap="nowrap">
          <Group gap="sm" wrap="nowrap">
            <Burger opened={opened} onClick={toggle} hiddenFrom="sm" size="sm" />
            <Title order={4}>Harborline AP</Title>
            <Text size="sm" c="dimmed" visibleFrom="md">business date {me.businessDate}</Text>
          </Group>
          <Group gap="sm" wrap="nowrap">
            <Stack gap={0} align="flex-end" visibleFrom="sm">
              <Text size="sm" fw={600}>{me.fullName}</Text>
              <Text size="xs" c="dimmed">{me.title}</Text>
            </Stack>
            <Badge color={ROLE_COLOR[me.role]} variant="light">{ROLE_LABEL[me.role]}</Badge>
            {me.actingFor.length > 0 && (
              <Tooltip label={`You are delegate for ${me.actingFor.map((a) => a.fullName).join(', ')}`}>
                <Badge color="grape" variant="outline">delegate for {me.actingFor.map((a) => a.userId).join(', ')}</Badge>
              </Tooltip>
            )}
            <UserSwitcher width={260} />
          </Group>
        </Group>
      </AppShell.Header>
      <AppShell.Navbar p="xs">
        {items.map((n) => (
          <NavLink key={n.to} component={Link} to={n.to} label={n.label} leftSection={n.icon}
            active={n.to === '/' ? loc.pathname === '/' : loc.pathname.startsWith(n.to)} onClick={() => opened && toggle()} />
        ))}
      </AppShell.Navbar>
      <AppShell.Main>
        <Outlet />
      </AppShell.Main>
    </AppShell>
  )
}
