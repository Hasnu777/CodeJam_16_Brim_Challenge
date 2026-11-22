import { Badge, Button, Card, FileButton, Group, SimpleGrid, Stack, Table, Text, Title } from '@mantine/core'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { api } from '../api/client'
import type { AuditEvent, EmailMessage, InvoiceSummary, PaymentRow, QueueItem, VendorRow } from '../api/types'
import { useAuth } from '../auth/AuthContext'
import { Empty, MoneyCell, StatusBadge } from '../components/bits'
import { notifyError, notifyOk } from '../components/notify'
import { dateTime, money, num } from '../lib/format'

function Stat({ label, value, to, color }: { label: string; value: number | string; to: string; color?: string }) {
  return (
    <Card withBorder component={Link} to={to} padding="md">
      <Text size="xs" c="dimmed" tt="uppercase" fw={600}>{label}</Text>
      <Text size="xl" fw={700} c={color}>{value}</Text>
    </Card>
  )
}

/** "What do I need to do now?" - different content per role. */
export default function HomePage() {
  const { me, is } = useAuth()
  const r = me!.role
  const invoices = useQuery({ queryKey: ['invoices', 'home'], queryFn: () => api.get<InvoiceSummary[]>('/invoices') })
  const emails = useQuery({ queryKey: ['inbox'], queryFn: () => api.get<EmailMessage[]>('/inbox'), enabled: is('CLK', 'ADM', 'AUD') })
  const approvals = useQuery({ queryKey: ['approvals'], queryFn: () => api.get<QueueItem[]>('/approvals/mine'), enabled: is('APR', 'FM', 'ADM') })
  const payments = useQuery({ queryKey: ['payments', ''], queryFn: () => api.get<PaymentRow[]>('/payments'), enabled: is('CLK', 'FM', 'ADM', 'AUD') })
  const vendors = useQuery({ queryKey: ['vendors'], queryFn: () => api.get<VendorRow[]>('/vendors'), enabled: is('FM', 'ADM') })
  const audit = useQuery({ queryKey: ['audit', 'recent'], queryFn: () => api.get<AuditEvent[]>('/audit?limit=12'), enabled: is('ADM', 'AUD') })

  const inv = invoices.data ?? []
  const count = (pred: (i: InvoiceSummary) => boolean) => inv.filter(pred).length
  const attention = (emails.data ?? []).filter((e) => e.processedAt && e.classification !== 'INVOICE').length
  const unprocessed = (emails.data ?? []).filter((e) => !e.processedAt).length
  const pay = payments.data ?? []
  const releasable = pay.filter((p) => p.payment?.status === 'SCHEDULED' && (p.releaseBlockers?.length ?? 0) === 0).length
  const month = me!.businessDate.slice(0, 7)
  const deptSpend = inv.filter((i) => i.departmentId === me!.departmentId && String(i.invoiceDate ?? '').startsWith(month))
    .reduce((s, i) => s + num(i.totalCad), 0)

  return (
    <Stack>
      <div>
        <Title order={3}>Good to see you, {me!.fullName.split(' ')[0]}</Title>
        {me!.actingFor.length > 0 && (
          <Text c="grape">You are acting as delegate for {me!.actingFor.map((a) => a.fullName).join(', ')}; their approvals appear in your queue.</Text>
        )}
      </div>
      {r === 'ADM' && <DemoReset />}

      <SimpleGrid cols={{ base: 2, md: 4 }}>
        {r === 'CLK' && <>
          <Stat label="To review" value={count((i) => i.status === 'IN_REVIEW')} to="/invoices?status=IN_REVIEW" />
          <Stat label="With BLOCK flags" value={count((i) => (i.blockFlags ?? 0) > 0)} to="/invoices?flagged=true" color="red" />
          <Stat label="Needs attention" value={attention} to="/inbox" color="orange" />
          <Stat label="Approved, to schedule" value={count((i) => i.status === 'APPROVED')} to="/payments" />
        </>}
        {(r === 'APR' || r === 'FM' || r === 'ADM') && (
          <Stat label="Waiting for me" value={approvals.data?.length ?? '…'} to="/approvals" color="grape" />
        )}
        {r === 'APR' && <Stat label={`${me!.departmentId} spend ${month}`} value={money(deptSpend)} to="/invoices" />}
        {(r === 'FM' || r === 'ADM') && <>
          <Stat label="Vendors to approve" value={(vendors.data ?? []).filter((v) => v.vendor?.status === 'pending_review').length} to="/vendors" color="orange" />
          <Stat label="Bank changes to verify" value={(vendors.data ?? []).filter((v) => v.pendingBankChange).length} to="/vendors" color="red" />
          <Stat label="Payments I can release" value={releasable} to="/payments" color="teal" />
        </>}
        {r === 'ADM' && <Stat label="In review" value={count((i) => i.status === 'IN_REVIEW')} to="/invoices?status=IN_REVIEW" />}
        {r === 'AUD' && <>
          <Stat label="Invoices this batch" value={inv.length} to="/invoices" />
          <Stat label="Flagged" value={count((i) => (i.blockFlags ?? 0) + (i.warnFlags ?? 0) > 0)} to="/invoices?flagged=true" color="orange" />
          <Stat label="Paid" value={count((i) => i.status === 'PAID')} to="/invoices?status=PAID" color="green" />
          <Stat label="Audit log" value="verify" to="/audit" />
        </>}
        {r === 'EMP' && ['IN_REVIEW', 'PENDING_APPROVAL', 'APPROVED', 'PAID'].map((s) => (
          <Stat key={s} label={s.replace('_', ' ').toLowerCase()} value={count((i) => i.status === s)} to={`/invoices?status=${s}`} />
        ))}
      </SimpleGrid>

      {r === 'CLK' && unprocessed > 0 && (
        <Card withBorder><Group justify="space-between"><Text>{unprocessed} emails waiting in the AP inbox.</Text>
          <Button component={Link} to="/inbox">Go to inbox</Button></Group></Card>
      )}
      {r === 'EMP' && <EmployeeUpload />}

      {(r === 'APR' || r === 'FM' || r === 'ADM') && (
        <Card withBorder>
          <Title order={5} mb="xs">Waiting for your approval</Title>
          {(approvals.data ?? []).length === 0 ? <Text c="dimmed">Nothing waiting for you.</Text> : (
            <Table>
              <Table.Tbody>
                {(approvals.data ?? []).slice(0, 8).map((q) => (
                  <Table.Tr key={q.invoiceId}>
                    <Table.Td><Link to={`/invoices/${q.invoiceId}`}>{q.invoiceId}</Link></Table.Td>
                    <Table.Td>{q.vendorName}</Table.Td>
                    <Table.Td><MoneyCell cad={q.totalCad} amount={q.total} currency={q.currency} /></Table.Td>
                    <Table.Td>{q.onBehalfOfUserId || q.asDelegate ? <Badge color="grape" variant="light">as delegate</Badge> : null}</Table.Td>
                    <Table.Td><Text size="xs" c="dimmed">{q.topFlag}</Text></Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          )}
        </Card>
      )}

      {(r === 'ADM' || r === 'AUD') && (
        <Card withBorder>
          <Title order={5} mb="xs">Recent activity</Title>
          <Table>
            <Table.Tbody>
              {(audit.data ?? []).map((e) => (
                <Table.Tr key={e.id}>
                  <Table.Td><Text size="xs" c="dimmed">{dateTime(e.ts)}</Text></Table.Td>
                  <Table.Td><Text size="sm">{e.actor}{e.onBehalfOf ? ` for ${e.onBehalfOf}` : ''}</Text></Table.Td>
                  <Table.Td><Badge variant="light" size="sm">{e.action}</Badge></Table.Td>
                  <Table.Td><Text size="xs">{e.entity} {e.entityId}</Text></Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </Card>
      )}

      {r === 'EMP' && (
        <Card withBorder>
          <Title order={5} mb="xs">My submissions</Title>
          {inv.length === 0 ? <Empty>No invoices yet. Upload a supplier invoice above and AP will take it from there.</Empty> : (
            <Table>
              <Table.Tbody>
                {inv.map((i) => (
                  <Table.Tr key={i.id}>
                    <Table.Td><Link to={`/invoices/${i.id}`}>{i.id}</Link></Table.Td>
                    <Table.Td>{i.vendorName}</Table.Td>
                    <Table.Td><MoneyCell cad={i.totalCad} amount={i.total} currency={i.currency} /></Table.Td>
                    <Table.Td><StatusBadge status={i.status} /></Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          )}
        </Card>
      )}
    </Stack>
  )
}

function EmployeeUpload() {
  const [busy, setBusy] = useState(false)
  const nav = useNavigate()
  const qc = useQueryClient()
  return (
    <Card withBorder>
      <Group justify="space-between">
        <div>
          <Text fw={600}>Submit a supplier invoice</Text>
          <Text size="sm" c="dimmed">PDF or photo. It is read automatically and sent to AP for your department.</Text>
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
          {(props) => <Button {...props} loading={busy}>Upload invoice</Button>}
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
      <Card withBorder>
        <Group justify="space-between">
          <div>
            <Text fw={600}>Demo tools</Text>
            <Text size="sm" c="dimmed">Wipes all data (audit log included), reloads the dataset and processes the inbox. Only works in demo mode.</Text>
          </div>
          <Button color="red" variant="light" loading={busy} onClick={reset}>Reset demo data</Button>
        </Group>
      </Card>
  )
}