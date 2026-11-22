import { Badge, Button, Card, FileButton, Group, SimpleGrid, Stack, Table, Text, Title } from '@mantine/core'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import {
  IconAlertOctagon, IconArrowUpRight, IconBuildingBank, IconBuildingStore, IconChartLine, IconFileInvoice, IconHourglass, IconInbox,
  IconSend, IconShieldCheck,
} from '@tabler/icons-react'
import { useState, type ComponentType } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { api, qs } from '../api/client'
import type { AuditEvent, EmailMessage, InvoiceSummary, PaymentRow, QueueItem, VendorRow } from '../api/types'
import { useAuth } from '../auth/AuthContext'
import { Empty, MoneyCell, StatusBadge } from '../components/bits'
import { MonthlySpendChart } from '../components/MonthlySpendChart'
import { notifyError, notifyOk } from '../components/notify'
import { dateTime, money, MONTHS, noCode, num, statusLabel } from '../lib/format'
import { useNames } from '../lib/names'

type StatColor = 'violet' | 'orange' | 'red' | 'blue' | 'teal'
interface StatDef { label: string; value: number | string; icon: ComponentType<{ size?: number }>; color: StatColor; to: string }

/** Lovable stat card: label, big number, tinted icon tile, "View queue". */
function Stat({ label, value, icon: Icon, color, to }: StatDef) {
  return (
    <Card component={Link} to={to} withBorder p="lg" className="ap-stat">
      <Group justify="space-between" wrap="nowrap">
        <Stack gap={8}>
          <Text size="sm" c="dimmed" fw={600}>{label}</Text>
          <Title order={2}>{value}</Title>
        </Stack>
        <div className={`ap-stat-icon ap-stat-${color}`}><Icon size={25} /></div>
      </Group>
      <Group gap={5} mt="lg">
        <IconArrowUpRight size={15} />
        <Text size="xs" c="dimmed">View Queue</Text>
      </Group>
    </Card>
  )
}

const BILLED = new Set(['APPROVED', 'SCHEDULED', 'RELEASED', 'PAID'])

/** Same basis as the analytics endpoint (approved / scheduled / paid, CAD), for roles that cannot call it. */
function monthlyFrom(list: InvoiceSummary[], from: string, to: string): Record<string, string> {
  const out: Record<string, number> = {}
  for (const i of list) {
    const d = String(i.invoiceDate ?? '')
    if (!BILLED.has(String(i.status)) || d < from || d > to) continue
    out[d.slice(0, 7)] = (out[d.slice(0, 7)] ?? 0) + num(i.totalCad)
  }
  return Object.fromEntries(Object.entries(out).map(([k, v]) => [k, v.toFixed(2)]))
}

/** "What do I need to do now?" - Lovable home layout, role-specific real data. */
export default function HomePage() {
  const { me, is } = useAuth()
  const r = me!.role
  const canAnalytics = is('APR', 'FM', 'ADM', 'AUD')
  const year = me!.businessDate.slice(0, 4)
  const from = `${year}-01-01`
  const to = me!.businessDate
  const n = useNames()
  const invoices = useQuery({ queryKey: ['invoices', 'home'], queryFn: () => api.get<InvoiceSummary[]>('/invoices') })
  const history = useQuery({ queryKey: ['invoices', 'home', 'history'], queryFn: () => api.get<InvoiceSummary[]>('/invoices' + qs({ includeHistory: true })), enabled: r === 'CLK' })
  const spend = useQuery({ queryKey: ['home-spend', from, to], queryFn: () => api.get<{ monthly: Record<string, string> }>('/analytics/spend' + qs({ from, to })), enabled: canAnalytics })
  const emails = useQuery({ queryKey: ['inbox'], queryFn: () => api.get<EmailMessage[]>('/inbox'), enabled: is('CLK', 'ADM', 'AUD') })
  const approvals = useQuery({ queryKey: ['approvals'], queryFn: () => api.get<QueueItem[]>('/approvals/mine'), enabled: is('APR', 'FM', 'ADM') })
  const payments = useQuery({ queryKey: ['payments', ''], queryFn: () => api.get<PaymentRow[]>('/payments'), enabled: is('CLK', 'FM', 'ADM', 'AUD') })
  const vendors = useQuery({ queryKey: ['vendors'], queryFn: () => api.get<VendorRow[]>('/vendors'), enabled: is('FM', 'ADM') })
  const audit = useQuery({ queryKey: ['audit', 'recent'], queryFn: () => api.get<AuditEvent[]>('/audit?limit=8'), enabled: is('ADM', 'AUD') })

  const inv = invoices.data ?? []
  const count = (pred: (i: InvoiceSummary) => boolean) => inv.filter(pred).length
  const attentionEmails = (emails.data ?? []).filter((e) => e.processedAt && e.classification !== 'INVOICE').length
  const unprocessed = (emails.data ?? []).filter((e) => !e.processedAt).length
  const pay = payments.data ?? []
  const releasable = pay.filter((p) => p.payment?.status === 'SCHEDULED' && (p.releaseBlockers?.length ?? 0) === 0).length
  const month = me!.businessDate.slice(0, 7)
  const deptSpend = inv.filter((i) => i.departmentId === me!.departmentId && String(i.invoiceDate ?? '').startsWith(month))
    .reduce((s, i) => s + num(i.totalCad), 0)
  const queue = approvals.data ?? []

  const stats: StatDef[] =
    r === 'EMP' ? [
      { label: 'My Submissions', value: inv.length, icon: IconHourglass, color: 'violet', to: '/invoices' },
      { label: 'In Review', value: count((i) => i.status === 'IN_REVIEW'), icon: IconBuildingStore, color: 'orange', to: '/invoices?status=IN_REVIEW' },
      { label: 'Approved', value: count((i) => i.status === 'APPROVED'), icon: IconBuildingBank, color: 'blue', to: '/invoices?status=APPROVED' },
      { label: 'Paid', value: count((i) => i.status === 'PAID'), icon: IconSend, color: 'teal', to: '/invoices?status=PAID' },
    ] : r === 'CLK' ? [
      { label: 'To Review', value: count((i) => i.status === 'IN_REVIEW'), icon: IconHourglass, color: 'violet', to: '/invoices?status=IN_REVIEW' },
      { label: 'Blocked', value: count((i) => (i.blockFlags ?? 0) > 0), icon: IconAlertOctagon, color: 'red', to: '/invoices?flagged=true' },
      { label: 'Attention Needed', value: attentionEmails, icon: IconInbox, color: 'orange', to: '/inbox' },
      { label: 'Approved', value: count((i) => i.status === 'APPROVED'), icon: IconSend, color: 'teal', to: '/payments' },
    ] : r === 'APR' ? [
      { label: 'Waiting For Me', value: approvals.data?.length ?? '…', icon: IconHourglass, color: 'violet', to: '/approvals' },
      { label: `${n.dept(me!.departmentId)} Spend, ${MONTHS[Number(month.slice(5, 7)) - 1]} ${month.slice(0, 4)}`, value: money(deptSpend), icon: IconChartLine, color: 'blue', to: '/invoices' },
    ] : r === 'AUD' ? [
      { label: 'Invoices This Batch', value: inv.length, icon: IconFileInvoice, color: 'blue', to: '/invoices' },
      { label: 'Flagged', value: count((i) => (i.blockFlags ?? 0) + (i.warnFlags ?? 0) > 0), icon: IconAlertOctagon, color: 'orange', to: '/invoices?flagged=true' },
      { label: 'Paid', value: count((i) => i.status === 'PAID'), icon: IconSend, color: 'teal', to: '/invoices?status=PAID' },
      { label: 'Audit Log', value: 'Verify', icon: IconShieldCheck, color: 'violet', to: '/audit' },
    ] : [ // FM, ADM
      { label: 'Waiting For Me', value: approvals.data?.length ?? '…', icon: IconHourglass, color: 'violet', to: '/approvals' },
      { label: 'Vendors To Approve', value: (vendors.data ?? []).filter((v) => v.vendor?.status === 'pending_review').length, icon: IconBuildingStore, color: 'orange', to: '/vendors' },
      { label: 'Bank Changes To Verify', value: (vendors.data ?? []).filter((v) => v.pendingBankChange).length, icon: IconBuildingBank, color: 'red', to: '/vendors' },
      { label: 'Payments To Release', value: releasable, icon: IconSend, color: 'teal', to: '/payments' },
    ]

  // Attention band: the most pressing real item for this role (no sample invoice ids).
  const firstQueued = queue.find((q) => q.topFlag) ?? queue[0]
  const blocked = inv.filter((i) => (i.blockFlags ?? 0) > 0 && !['REJECTED', 'DUPLICATE', 'PAID'].includes(String(i.status)))
  const firstBlocked = blocked.find((i) => i.status === 'IN_REVIEW') ?? blocked[0]
  const attention = is('APR', 'FM', 'ADM') && firstQueued
    ? { id: firstQueued.invoiceId!, line: `${firstQueued.vendorName} · ${firstQueued.topFlag ? noCode(n.text(firstQueued.topFlag)) : `step ${firstQueued.stepSeq} of ${firstQueued.stepCount}`}`, title: 'Invoice Waiting For Your Decision' }
    : r !== 'EMP' && firstBlocked
      ? { id: firstBlocked.id!, line: `${firstBlocked.vendorName} · ${firstBlocked.blockFlags} blocking ${firstBlocked.blockFlags === 1 ? 'flag' : 'flags'}${blocked.length > 1 ? ` · ${blocked.length - 1} more blocked` : ''}`, title: 'Invoice Needs Your Attention' }
      : null

  const monthly = canAnalytics ? spend.data?.monthly : monthlyFrom(r === 'CLK' ? (history.data ?? []) : inv, from, to)
  const lastMonth = Number(to.slice(5, 7)) - 1

  return (
    <Stack gap="xl">
      <Group justify="space-between" align="flex-end">
        <div>
          <Title order={1}>Good to see you, {me!.fullName.split(' ')[0]}</Title>
          <Text c="dimmed" mt={4}>Here’s what needs your attention today.</Text>
          {me!.actingFor.length > 0 && (
            <Text c="grape" size="sm" mt={4}>You are acting as delegate for {me!.actingFor.map((a) => a.fullName).join(', ')}; their approvals appear in your queue.</Text>
          )}
        </div>
        <Badge variant="light">{r === 'EMP' ? 'My Workspace' : 'Finance Workspace'}</Badge>
      </Group>
      {r === 'ADM' && <DemoReset />}

      <SimpleGrid cols={{ base: 1, xs: 2, lg: 4 }} spacing="lg">
        {stats.map((s) => <Stat key={s.label} {...s} />)}
      </SimpleGrid>

      {attention && (
        <Group justify="space-between" className="ap-attention-band">
          <div>
            <Text fw={700}>{attention.title}</Text>
            <Text size="sm" c="dimmed">{attention.id} · {attention.line}</Text>
          </div>
          <Button component={Link} to={`/invoices/${attention.id}`} variant="light" color="red">Review Invoice</Button>
        </Group>
      )}
      {r === 'CLK' && unprocessed > 0 && (
        <Card withBorder className="ap-stat"><Group justify="space-between"><Text>{unprocessed} {unprocessed === 1 ? 'email is' : 'emails are'} waiting in your inbox.</Text>
          <Button component={Link} to="/inbox">View Inbox</Button></Group></Card>
      )}
      {r === 'EMP' && <EmployeeUpload />}

      <SimpleGrid cols={{ base: 1, lg: 2 }} spacing="xl">
        <section>
          <Group justify="space-between" mb="md">
            <Title order={3}>{r === 'EMP' ? 'My Monthly Spend' : 'Monthly Spend'}</Title>
            <Text size="xs" c="dimmed">Jan – {MONTHS[lastMonth]} {year} · CAD</Text>
          </Group>
          {monthly ? <MonthlySpendChart monthly={monthly} /> : <Text c="dimmed" size="sm">Loading…</Text>}
          {canAnalytics && <Button component={Link} to="/analytics" variant="subtle" mt="sm">Spend Analytics →</Button>}
        </section>

        <section>
          {is('APR', 'FM', 'ADM') && (<>
            <Title order={3} mb="md">Waiting For Your Approval</Title>
            {queue.length ? (
              <Table>
                <Table.Thead><Table.Tr><Table.Th>Invoice</Table.Th><Table.Th>Vendor</Table.Th><Table.Th ta="right">Amount</Table.Th><Table.Th /></Table.Tr></Table.Thead>
                <Table.Tbody>
                  {queue.slice(0, 6).map((q) => (
                    <Table.Tr key={q.invoiceId}>
                      <Table.Td><Link to={`/invoices/${q.invoiceId}`}>{q.invoiceId}</Link></Table.Td>
                      <Table.Td>{q.vendorName}{q.topFlag && <Text size="xs" c="dimmed">{noCode(n.text(q.topFlag))}</Text>}</Table.Td>
                      <Table.Td ta="right"><MoneyCell cad={q.totalCad} amount={q.total} currency={q.currency} /></Table.Td>
                      <Table.Td>{q.onBehalfOfUserId || q.asDelegate ? <Badge color="grape" variant="light">For {n.person(q.onBehalfOfUserId ?? q.assignedUserId)}</Badge> : null}</Table.Td>
                    </Table.Tr>
                  ))}
                </Table.Tbody>
              </Table>
            ) : <Text c="dimmed" py="xl">You’re all caught up.</Text>}
          </>)}

          {r === 'CLK' && (<>
            <Title order={3} mb="md">Ready For Review</Title>
            <InvoiceTable rows={inv.filter((i) => i.status === 'IN_REVIEW').slice(0, 6)} empty="Nothing waiting for review." />
          </>)}

          {r === 'EMP' && (<>
            <Title order={3} mb="md">My Submissions</Title>
            {inv.length === 0 ? <Empty>No invoices yet. Upload a supplier invoice above and AP will take it from there.</Empty>
              : <InvoiceTable rows={inv.slice(0, 8)} empty="" />}
          </>)}

          {r === 'AUD' && (<>
            <Title order={3} mb="md">Recent Activity</Title>
            <AuditTable events={audit.data ?? []} />
          </>)}
        </section>
      </SimpleGrid>

      {r === 'ADM' && (
        <section>
          <Title order={3} mb="md">Recent Activity</Title>
          <AuditTable events={audit.data ?? []} />
        </section>
      )}
    </Stack>
  )
}

function InvoiceTable({ rows, empty }: { rows: InvoiceSummary[]; empty: string }) {
  if (!rows.length) return <Text c="dimmed" py="xl">{empty}</Text>
  return (
    <Table>
      <Table.Thead><Table.Tr><Table.Th>Invoice</Table.Th><Table.Th>Vendor</Table.Th><Table.Th ta="right">Amount</Table.Th><Table.Th>Status</Table.Th></Table.Tr></Table.Thead>
      <Table.Tbody>
        {rows.map((i) => (
          <Table.Tr key={i.id}>
            <Table.Td><Link to={`/invoices/${i.id}`}>{i.id}</Link></Table.Td>
            <Table.Td>{i.vendorName}</Table.Td>
            <Table.Td ta="right"><MoneyCell cad={i.totalCad} amount={i.total} currency={i.currency} /></Table.Td>
            <Table.Td><StatusBadge status={i.status} /></Table.Td>
          </Table.Tr>
        ))}
      </Table.Tbody>
    </Table>
  )
}

function AuditTable({ events }: { events: AuditEvent[] }) {
  const n = useNames()
  if (!events.length) return <Text c="dimmed" py="xl">No activity yet.</Text>
  return (
    <Table>
      <Table.Tbody>
        {events.map((e) => (
          <Table.Tr key={e.id}>
            <Table.Td><Text size="xs" c="dimmed">{dateTime(e.ts)}</Text></Table.Td>
            <Table.Td><Text size="sm">{n.person(e.actor)}{e.onBehalfOf ? ` for ${n.person(e.onBehalfOf)}` : ''}</Text></Table.Td>
            <Table.Td><Badge variant="light" size="sm">{statusLabel(e.action ?? '')}</Badge></Table.Td>
            <Table.Td><Text size="xs">{e.entity === 'vendor' ? n.vendor(e.entityId) : e.entity === 'user' ? n.person(e.entityId) : `${statusLabel(e.entity ?? '')} ${e.entityId ?? ''}`}</Text></Table.Td>
          </Table.Tr>
        ))}
      </Table.Tbody>
    </Table>
  )
}

function EmployeeUpload() {
  const [busy, setBusy] = useState(false)
  const nav = useNavigate()
  const qc = useQueryClient()
  return (
    <Card withBorder p="lg" className="ap-stat">
      <Group justify="space-between">
        <div>
          <Text fw={700}>Submit a Supplier Invoice</Text>
          <Text size="sm" c="dimmed">Upload a PDF or a photo (.jpg, .jpeg, .png).</Text>
        </div>
        <FileButton accept="application/pdf,image/png,image/jpeg" onChange={async (f) => {
          if (!f) return
          setBusy(true)
          try {
            const fd = new FormData()
            fd.append('file', f)
            const inv = await api.upload<{ id: string }>('/invoices/upload', fd)
            notifyOk(`Uploaded as ${inv.id}`)
            qc.invalidateQueries()
            nav(`/invoices/${inv.id}`)
          } catch (e) {
            notifyError(e)
          } finally {
            setBusy(false)
          }
        }}>
          {(props) => <Button {...props} loading={busy}>Upload Invoice</Button>}
        </FileButton>
      </Group>
    </Card>
  )
}


/** Admin-only demo tool: wipe everything (audit log included), reload the dataset and re-process the inbox. Demo mode only. */
function DemoReset() {
  const [busy, setBusy] = useState(false)
  const qc = useQueryClient()
  const reset = async () => {
    if (!window.confirm('This wipes ALL data, including the audit log, and reloads the demo dataset. Continue?')) return
    setBusy(true)
    try {
      await api.post('/admin/reset')
      await api.post('/inbox/process')
      notifyOk('Demo data reset; the inbox is being processed (about a minute)')
      qc.invalidateQueries()
    } catch (e) {
      notifyError(e)
    } finally {
      setBusy(false)
    }
  }
  return (
      <Card withBorder p="lg" className="ap-stat">
        <Group justify="space-between" wrap="nowrap">
          <div>
            <Text fw={700}>Demo Tools</Text>
            <Text size="sm" c="dimmed">Wipes all data (audit log included), reloads the dataset and processes the inbox. Only works in demo mode.</Text>
          </div>
          <Button color="red" variant="light" loading={busy} onClick={reset} style={{ flexShrink: 0 }}>Reset Demo Data</Button>
        </Group>
      </Card>
  )
}