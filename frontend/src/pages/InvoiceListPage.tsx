import { Alert, Anchor, Badge, Button, Checkbox, Group, MultiSelect, Select, Stack, Switch, Table, Text, TextInput, Title } from '@mantine/core'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useMemo, useState } from 'react'
import { Link, useLocation, useNavigate, useSearchParams } from 'react-router-dom'
import { api, qs } from '../api/client'
import type { Department, InvoiceSummary } from '../api/types'
import { useAuth } from '../auth/AuthContext'
import { Empty, ErrorBox, FlagCounts, Loading, MoneyCell, StatusBadge } from '../components/bits'
import { notifyError, notifyOk } from '../components/notify'
import { date, noCode, statusLabel } from '../lib/format'
import { useNames } from '../lib/names'

const STATUSES = ['RECEIVED', 'EXTRACTED', 'IN_REVIEW', 'PENDING_APPROVAL', 'APPROVED', 'SCHEDULED', 'RELEASED', 'PAID', 'ON_HOLD', 'REJECTED', 'DUPLICATE']

type SortKey = 'id' | 'vendor' | 'due'
const SORTS: { value: SortKey; label: string }[] = [
  { value: 'id', label: 'AP ID (newest first)' },
  { value: 'vendor', label: 'Vendor (A to Z)' },
  { value: 'due', label: 'Due Date (soonest first)' },
]

/** Drill-down from Spend Analytics: exactly the bills behind one breakdown row. */
export interface DrillDown { label: string; ids: string[] }

export default function InvoiceListPage() {
  const { me, is } = useAuth()
  const n = useNames()
  const nav = useNavigate()
  const loc = useLocation()
  const drill = (loc.state as { drill?: DrillDown } | null)?.drill
  const [sp, setSp] = useSearchParams()
  const [status, setStatus] = useState<string[]>(sp.get('status') ? sp.get('status')!.split(',') : [])
  const [dept, setDept] = useState<string | null>(sp.get('dept'))
  const [flagged, setFlagged] = useState(sp.get('flagged') === 'true')
  const [history, setHistory] = useState(false)
  const [q, setQ] = useState('')
  const [sort, setSort] = useState<SortKey>('id')
  const [picked, setPicked] = useState<string[]>([])
  const qc = useQueryClient()
  const depts = useQuery({ queryKey: ['departments'], queryFn: () => api.get<Department[]>('/ref/departments') })
  // A drill-down spans the 10-month history too (analytics counts it), so it always includes history.
  const params = { status: status.join(','), dept: dept ?? undefined, flagged: flagged || undefined, includeHistory: history || !!drill || undefined }
  const list = useQuery({ queryKey: ['invoices', params], queryFn: () => api.get<InvoiceSummary[]>('/invoices' + qs(params)) })
  const rows = useMemo(() => {
    const needle = q.trim().toLowerCase()
    const only = drill ? new Set(drill.ids) : null
    const out = (list.data ?? []).filter((i) => (!only || only.has(i.id!)) && (!needle || `${i.id} ${i.vendorName} ${i.vendorInvoiceNumber}`.toLowerCase().includes(needle)))
    const by: Record<SortKey, (a: InvoiceSummary, b: InvoiceSummary) => number> = {
      id: (a, b) => String(b.id).localeCompare(String(a.id)),
      vendor: (a, b) => String(a.vendorName ?? '').localeCompare(String(b.vendorName ?? '')) || String(b.id).localeCompare(String(a.id)),
      due: (a, b) => String(a.dueDate ?? '9999').localeCompare(String(b.dueDate ?? '9999')) || String(b.id).localeCompare(String(a.id)),
    }
    return [...out].sort(by[sort])
  }, [list.data, q, sort, drill])

  const prepare = async () => {
    try {
      const out = await api.post<{ id: string }[]>('/payments/prepare', { invoiceIds: picked })
      notifyOk(`${out.length} payment(s) scheduled`)
      setPicked([])
      qc.invalidateQueries()
    } catch (e) {
      notifyError(e)
    }
  }

  const title = me!.role === 'EMP' ? 'My Submissions' : me!.role === 'APR' ? `Invoices · ${n.dept(me!.departmentId)}` : 'Invoices'
  return (
    <Stack>
      <Group justify="space-between">
        <Title order={3}>{title}</Title>
        {is('CLK') && picked.length > 0 && <Button onClick={prepare}>Prepare Payments ({picked.length})</Button>}
      </Group>
      {drill && (
        <Alert color="blue" variant="light" withCloseButton closeButtonLabel="Show all invoices" onClose={() => nav('/invoices', { replace: true, state: null })}>
          Showing the {drill.ids.length} {drill.ids.length === 1 ? 'bill' : 'bills'} behind <b>{drill.label}</b> in Spend Analytics.
        </Alert>
      )}
      <Group align="flex-end">
        <TextInput label="Search" placeholder="Vendor, Invoice #, AP ID" value={q} onChange={(e) => setQ(e.currentTarget.value)} w={220} />
        <MultiSelect label="Status" data={STATUSES.map((s) => ({ value: s, label: statusLabel(s) }))} value={status} clearable w={320}
          onChange={(v) => { setStatus(v); setSp((p) => { if (v.length) p.set('status', v.join(',')); else p.delete('status'); return p }, { state: loc.state }) }} />
        {!is('EMP', 'APR') && (
          <Select label="Department" clearable w={180} value={dept} onChange={setDept}
            data={(depts.data ?? []).map((d) => ({ value: d.id!, label: d.name! }))} />
        )}
        <Select label="Sort By" w={220} value={sort} onChange={(v) => setSort((v as SortKey) ?? 'id')} data={SORTS} allowDeselect={false} />
        <Switch label="Flagged Only" checked={flagged} onChange={(e) => setFlagged(e.currentTarget.checked)} />
        {!drill && <Switch label="Include 10-Month History" checked={history} onChange={(e) => setHistory(e.currentTarget.checked)} />}
      </Group>
      <Group gap="xs">
        <Text size="xs" c="dimmed">{rows.length} {rows.length === 1 ? 'invoice' : 'invoices'} · Issues:</Text>
        <Badge color="red" size="xs">Blocks payment</Badge>
        <Badge color="yellow" size="xs" variant="light">Needs review</Badge>
        <Badge color="gray" size="xs" variant="light">Info</Badge>
      </Group>
      {list.isLoading ? <Loading /> : list.error ? <ErrorBox error={list.error} /> : rows.length === 0 ? (
        <Empty>{me!.role === 'EMP' ? 'You have not submitted any invoices.' : 'No invoices match. Process the inbox to begin, or include history.'}</Empty>
      ) : (
        <Table striped highlightOnHover stickyHeader>
          <Table.Thead>
            <Table.Tr>
              {is('CLK') && <Table.Th>Select</Table.Th>}
              <Table.Th>AP ID</Table.Th><Table.Th>Vendor</Table.Th><Table.Th>Invoice #</Table.Th><Table.Th>Amount (CAD)</Table.Th>
              <Table.Th>Due</Table.Th><Table.Th>Status</Table.Th><Table.Th>Issues</Table.Th><Table.Th>Current Approver</Table.Th>
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {rows.map((i) => (
              <Table.Tr key={i.id}>
                {is('CLK') && <Table.Td>{i.status === 'APPROVED' && !i.creditNote &&
                  <Checkbox aria-label={`Select ${i.id}`} checked={picked.includes(i.id!)} onChange={(e) => setPicked((p) => e.currentTarget.checked ? [...p, i.id!] : p.filter((x) => x !== i.id))} />}</Table.Td>}
                <Table.Td><Anchor component={Link} to={`/invoices/${i.id}`}>{i.id}</Anchor></Table.Td>
                <Table.Td><Text size="sm">{i.vendorName ?? <i>Unknown</i>}</Text>{!i.vendorId && <Text size="xs" c="red">Not in vendor master</Text>}</Table.Td>
                <Table.Td><Text size="sm">{i.vendorInvoiceNumber}</Text>{i.creditNote && <Text size="xs" c="dimmed">Credit note</Text>}</Table.Td>
                <Table.Td><MoneyCell cad={i.totalCad} amount={i.total} currency={i.currency} /></Table.Td>
                <Table.Td><Text size="sm">{date(i.dueDate)}</Text></Table.Td>
                <Table.Td><StatusBadge status={i.status} /></Table.Td>
                <Table.Td><FlagCounts block={i.blockFlags} warn={i.warnFlags} info={i.infoFlags} />
                  {i.topFlag && <Text size="xs" c="dimmed" lineClamp={1} maw={220}>{noCode(i.topFlag)}</Text>}</Table.Td>
                <Table.Td><Text size="sm">{i.currentApproverName ?? ''}</Text></Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      )}
    </Stack>
  )
}
