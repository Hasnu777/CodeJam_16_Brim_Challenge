import { Anchor, Button, Checkbox, Group, MultiSelect, Select, Stack, Switch, Table, Text, TextInput, Title } from '@mantine/core'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useMemo, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { api, qs } from '../api/client'
import type { Department, InvoiceSummary } from '../api/types'
import { useAuth } from '../auth/AuthContext'
import { Empty, ErrorBox, FlagCounts, Loading, MoneyCell, StatusBadge } from '../components/bits'
import { notifyError, notifyOk } from '../components/notify'
import { date } from '../lib/format'

const STATUSES = ['RECEIVED', 'EXTRACTED', 'IN_REVIEW', 'PENDING_APPROVAL', 'APPROVED', 'SCHEDULED', 'RELEASED', 'PAID', 'ON_HOLD', 'REJECTED', 'DUPLICATE']

export default function InvoiceListPage() {
  const { me, is } = useAuth()
  const [sp, setSp] = useSearchParams()
  const [status, setStatus] = useState<string[]>(sp.get('status') ? sp.get('status')!.split(',') : [])
  const [dept, setDept] = useState<string | null>(sp.get('dept'))
  const [flagged, setFlagged] = useState(sp.get('flagged') === 'true')
  const [history, setHistory] = useState(false)
  const [q, setQ] = useState('')
  const [picked, setPicked] = useState<string[]>([])
  const qc = useQueryClient()
  const depts = useQuery({ queryKey: ['departments'], queryFn: () => api.get<Department[]>('/ref/departments') })
  const params = { status: status.join(','), dept: dept ?? undefined, flagged: flagged || undefined, includeHistory: history || undefined }
  const list = useQuery({ queryKey: ['invoices', params], queryFn: () => api.get<InvoiceSummary[]>('/invoices' + qs(params)) })
  const rows = useMemo(() => {
    const n = q.trim().toLowerCase()
    return (list.data ?? []).filter((i) => !n || `${i.id} ${i.vendorName} ${i.vendorInvoiceNumber}`.toLowerCase().includes(n))
  }, [list.data, q])

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

  const title = me!.role === 'EMP' ? 'My submissions' : me!.role === 'APR' ? `Invoices · ${me!.departmentId}` : 'Invoices'
  return (
    <Stack>
      <Group justify="space-between">
        <Title order={3}>{title}</Title>
        {is('CLK') && picked.length > 0 && <Button onClick={prepare}>Prepare payment for {picked.length}</Button>}
      </Group>
      <Group align="flex-end">
        <TextInput label="Search" placeholder="vendor, invoice #, AP id" value={q} onChange={(e) => setQ(e.currentTarget.value)} w={220} />
        <MultiSelect label="Status" data={STATUSES} value={status} clearable w={320}
          onChange={(v) => { setStatus(v); setSp((p) => { if (v.length) p.set('status', v.join(',')); else p.delete('status'); return p }) }} />
        {!is('EMP', 'APR') && (
          <Select label="Department" clearable w={180} value={dept} onChange={setDept}
            data={(depts.data ?? []).map((d) => ({ value: d.id!, label: `${d.id} ${d.name}` }))} />
        )}
        <Switch label="Flagged only" checked={flagged} onChange={(e) => setFlagged(e.currentTarget.checked)} />
        <Switch label="Include 10-month history" checked={history} onChange={(e) => setHistory(e.currentTarget.checked)} />
      </Group>
      {list.isLoading ? <Loading /> : list.error ? <ErrorBox error={list.error} /> : rows.length === 0 ? (
        <Empty>{me!.role === 'EMP' ? 'You have not submitted any invoices.' : 'No invoices match. Process the inbox to begin, or include history.'}</Empty>
      ) : (
        <Table striped highlightOnHover stickyHeader>
          <Table.Thead>
            <Table.Tr>
              {is('CLK') && <Table.Th />}
              <Table.Th>AP id</Table.Th><Table.Th>Vendor</Table.Th><Table.Th>Invoice #</Table.Th><Table.Th>Amount (CAD)</Table.Th>
              <Table.Th>Due</Table.Th><Table.Th>Status</Table.Th><Table.Th>Flags</Table.Th><Table.Th>Current approver</Table.Th>
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {rows.map((i) => (
              <Table.Tr key={i.id}>
                {is('CLK') && <Table.Td>{i.status === 'APPROVED' && !i.creditNote &&
                  <Checkbox checked={picked.includes(i.id!)} onChange={(e) => setPicked((p) => e.currentTarget.checked ? [...p, i.id!] : p.filter((x) => x !== i.id))} />}</Table.Td>}
                <Table.Td><Anchor component={Link} to={`/invoices/${i.id}`}>{i.id}</Anchor></Table.Td>
                <Table.Td><Text size="sm">{i.vendorName ?? <i>unknown</i>}</Text>{!i.vendorId && <Text size="xs" c="red">not in vendor master</Text>}</Table.Td>
                <Table.Td><Text size="sm">{i.vendorInvoiceNumber}</Text>{i.creditNote && <Text size="xs" c="dimmed">credit note</Text>}</Table.Td>
                <Table.Td><MoneyCell cad={i.totalCad} amount={i.total} currency={i.currency} /></Table.Td>
                <Table.Td><Text size="sm">{date(i.dueDate)}</Text></Table.Td>
                <Table.Td><StatusBadge status={i.status} /></Table.Td>
                <Table.Td><FlagCounts block={i.blockFlags} warn={i.warnFlags} info={i.infoFlags} />
                  {i.topFlag && <Text size="xs" c="dimmed" lineClamp={1} maw={220}>{i.topFlag}</Text>}</Table.Td>
                <Table.Td><Text size="sm">{i.currentApproverName ?? ''}</Text></Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      )}
      <Text size="xs" c="dimmed">{rows.length} invoices · red = blocks payment, amber = needs review, grey = info</Text>
    </Stack>
  )
}
