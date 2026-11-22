import { Card, Group, Progress, SegmentedControl, SimpleGrid, Stack, Table, Text, TextInput, Title } from '@mantine/core'
import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { api, qs } from '../api/client'
import { Empty, ErrorBox, Hint, Loading } from '../components/bits'
import { money, num } from '../lib/format'

interface Row { key: string; label: string; amountCad: string; invoices: number; share: string }
interface Resp { groupBy: string; scope: string; totalCad: string; invoiceCount: number; rows: Row[]; monthly: Record<string, string> }

const GROUPS = [
  { value: 'vendor', label: 'Vendor', noun: 'Vendor', basis: 'Bill totals incl. tax' },
  { value: 'category', label: 'Category', noun: 'Vendor category', basis: 'Bill totals incl. tax, grouped by vendor category' },
  { value: 'dept', label: 'Department', noun: 'Department', basis: 'Pre-tax line amounts, by the department each line is coded to' },
  { value: 'gl', label: 'GL account', noun: 'GL account', basis: 'Pre-tax line amounts, by the GL account each line is coded to' },
]

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec']

function Stat({ label, value, sub }: { label: string; value: string; sub?: string }) {
  return (
    <Card withBorder padding="sm">
      <Text size="xs" c="dimmed" tt="uppercase" fw={600}>{label}</Text>
      <Text fw={700} size="lg" truncate>{value}</Text>
      {sub && <Text size="xs" c="dimmed" truncate>{sub}</Text>}
    </Card>
  )
}

/** Bonus 4: spend by vendor, vendor category, department or GL account (approved, scheduled and paid bills). */
export default function AnalyticsPage() {
  const [groupBy, setGroupBy] = useState('category')
  const [from, setFrom] = useState('2026-01-01')
  const [to, setTo] = useState('2026-12-31')
  const q = useQuery({ queryKey: ['spend', groupBy, from, to], queryFn: () => api.get<Resp>('/analytics/spend' + qs({ groupBy, from: from || undefined, to: to || undefined })) })
  const d = q.data
  const g = GROUPS.find((x) => x.value === groupBy) ?? GROUPS[0]
  const months = d ? Object.entries(d.monthly) : []
  const maxMonth = Math.max(...months.map(([, v]) => num(v)), 1)
  const top = d?.rows[0]
  return (
    <Stack>
      <Group justify="space-between" align="flex-end">
        <div>
          <Title order={3}>Spend analytics</Title>
          <Text size="sm" c="dimmed">CAD, approved / scheduled / paid bills{d && d.scope !== 'all' ? ` · department ${d.scope} only` : ''}</Text>
        </div>
        <Group align="flex-end">
          <SegmentedControl value={groupBy} onChange={setGroupBy} data={GROUPS.map(({ value, label }) => ({ value, label }))} />
          <TextInput type="date" label="From" value={from} onChange={(e) => setFrom(e.currentTarget.value)} />
          <TextInput type="date" label="To" value={to} onChange={(e) => setTo(e.currentTarget.value)} />
        </Group>
      </Group>
      <Hint>{g.basis}. Totals by vendor/category include tax; department/GL totals are pre-tax, so the two views differ by the tax amount.</Hint>
      {q.isError ? <ErrorBox error={q.error} /> : !d ? <Loading /> : d.rows.length === 0 ? (
        <Empty>No approved or paid bills in this date range.</Empty>
      ) : (
        <>
          <SimpleGrid cols={{ base: 1, sm: 2, md: 4 }}>
            <Stat label="Total spend" value={money(d.totalCad)} />
            <Stat label="Bills" value={String(d.invoiceCount)} />
            <Stat label={`${g.noun}s`} value={String(d.rows.length)} />
            <Stat label={`Top ${g.noun.toLowerCase()}`} value={top ? `${top.share}%` : '-'} sub={top ? `${top.label} · ${money(top.amountCad)}` : undefined} />
          </SimpleGrid>
          <Card withBorder>
            <Text fw={600} mb="xs">By month</Text>
            <Group align="flex-end" gap={6} h={140} wrap="nowrap">
              {months.map(([m, v]) => (
                <Stack key={m} gap={2} align="center" justify="flex-end" style={{ flex: 1, height: '100%' }}>
                  <div title={`${m}: ${money(v)}`} style={{ width: '100%', maxWidth: 48, height: `${Math.max((100 * num(v)) / maxMonth, 1)}px`, background: 'var(--mantine-color-indigo-5)', borderRadius: 4 }} />
                  <Text size="xs" c="dimmed">{MONTHS[Number(m.slice(5)) - 1]}{months.length > 12 || m.slice(0, 4) !== months[0][0].slice(0, 4) ? ` ${m.slice(2, 4)}` : ''}</Text>
                </Stack>
              ))}
            </Group>
          </Card>
          <Table striped>
            <Table.Thead><Table.Tr><Table.Th>{g.noun}</Table.Th>
              <Table.Th ta="right">Spend</Table.Th><Table.Th>Share</Table.Th><Table.Th ta="right">Bills</Table.Th></Table.Tr></Table.Thead>
            <Table.Tbody>
              {d.rows.map((r) => (
                <Table.Tr key={r.key}>
                  <Table.Td><Text size="sm">{r.label}</Text>{r.label !== r.key && <Text size="xs" c="dimmed">{r.key}</Text>}</Table.Td>
                  <Table.Td ta="right" ff="monospace">{money(r.amountCad)}</Table.Td>
                  <Table.Td w={240}><Group gap={6} wrap="nowrap"><Progress value={num(r.share)} w={160} /><Text size="xs">{r.share}%</Text></Group></Table.Td>
                  <Table.Td ta="right">{r.invoices}</Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </>
      )}
    </Stack>
  )
}
