import { Card, Divider, Group, Progress, SegmentedControl, SimpleGrid, Stack, Table, Text, TextInput, Title } from '@mantine/core'
import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api, qs } from '../api/client'
import { Empty, ErrorBox, Hint, Loading } from '../components/bits'
import { money, num } from '../lib/format'
import { MonthlySpendChart } from '../components/MonthlySpendChart'
import { useNames } from '../lib/names'
import type { DrillDown } from './InvoiceListPage'

interface Row { key: string; label: string; amountCad: string; invoices: number; share: string; invoiceIds: string[] }
interface Resp { groupBy: string; scope: string; totalCad: string; invoiceCount: number; rows: Row[]; monthly: Record<string, string> }

const GROUPS = [
  { value: 'vendor', label: 'Vendor', noun: 'Vendor', basis: 'Bill totals incl. tax, per vendor.', taxIncluded: true },
  { value: 'category', label: 'Category', noun: 'Category', basis: 'Bill totals incl. tax, grouped by vendor category.', taxIncluded: true },
  { value: 'dept', label: 'Department', noun: 'Department', basis: 'Pre-tax line amounts, by the department each line is coded to. Tax is excluded, so this total is lower than total spend above.', taxIncluded: false },
  { value: 'gl', label: 'GL Account', noun: 'GL Account', basis: 'Pre-tax line amounts, by the GL account each line is coded to. Tax is excluded, so this total is lower than total spend above.', taxIncluded: false },
]

function Stat({ label, value, sub }: { label: string; value: string; sub?: string }) {
  return (
    <Card withBorder padding="sm" className="ap-stat">
      <Text size="xs" c="dimmed" tt="uppercase" fw={600}>{label}</Text>
      <Text fw={700} size="lg" truncate>{value}</Text>
      {sub && <Text size="xs" c="dimmed" truncate>{sub}</Text>}
    </Card>
  )
}

/** Bonus 4: spend by vendor, vendor category, department or GL account (approved, scheduled and paid bills). */
export default function AnalyticsPage() {
  const [groupBy, setGroupBy] = useState('category')
  const nav = useNavigate()
  const n = useNames()
  const [from, setFrom] = useState('2026-01-01')
  const [to, setTo] = useState('2026-12-31')
  const range = { from: from || undefined, to: to || undefined }
  // Overview: always tax-inclusive bill totals, independent of the breakdown toggle.
  const overview = useQuery({ queryKey: ['spend', 'vendor', from, to], queryFn: () => api.get<Resp>('/analytics/spend' + qs({ groupBy: 'vendor', ...range })) })
  const breakdown = useQuery({ queryKey: ['spend', groupBy, from, to], queryFn: () => api.get<Resp>('/analytics/spend' + qs({ groupBy, ...range })) })
  const o = overview.data
  const d = breakdown.data
  const g = GROUPS.find((x) => x.value === groupBy) ?? GROUPS[0]
  const top = d?.rows[0]
  // Names only: vendor and department ids stay out of the UI; a GL account keeps its number (accountants use it).
  const rowLabel = (r: Row) => (groupBy === 'gl' && r.label !== r.key ? `${r.key} · ${r.label}` : r.label === 'uncoded' ? 'Not coded' : r.label)
  const open = (r: Row) => nav('/invoices', { state: { drill: { label: rowLabel(r), ids: r.invoiceIds } satisfies DrillDown } })
  return (
    <Stack>
      <Group justify="space-between" align="flex-end">
        <div>
          <Title order={3}>Spend Analytics</Title>
          <Text size="sm" c="dimmed">CAD, approved, scheduled and paid bills{o && o.scope !== 'all' ? ` · ${n.dept(o.scope)} only` : ''}</Text>
        </div>
        <Group align="flex-end">
          <TextInput type="date" label="From" value={from} onChange={(e) => setFrom(e.currentTarget.value)} />
          <TextInput type="date" label="To" value={to} onChange={(e) => setTo(e.currentTarget.value)} />
        </Group>
      </Group>

      {overview.isError ? <ErrorBox error={overview.error} /> : !o ? <Loading /> : o.invoiceCount === 0 ? (
        <Empty>No approved or paid bills in this date range.</Empty>
      ) : (
        <>
          <SimpleGrid cols={{ base: 1, sm: 2 }}>
            <Stat label="Total Spend (Incl. Tax)" value={money(o.totalCad)} />
            <Stat label="Bills" value={String(o.invoiceCount)} />
          </SimpleGrid>
          <Card withBorder>
            <Group justify="space-between" mb="xs"><Text fw={600}>By Month (Incl. Tax)</Text><Text fw={700}>{money(o.totalCad)}</Text></Group>
            {/* Lovable line chart (chronological, per-point amounts) in place of afra-work's bars; same o.monthly data. */}
            <MonthlySpendChart monthly={o.monthly} maxHeight={320} />
          </Card>

          <Divider my="xs" />

          <Group justify="space-between" align="flex-end">
            <Title order={4}>Breakdown</Title>
            <SegmentedControl value={groupBy} onChange={setGroupBy} data={GROUPS.map(({ value, label }) => ({ value, label }))} />
          </Group>
          <Hint>{g.basis} Click a row to see its bills.</Hint>
          {breakdown.isError ? <ErrorBox error={breakdown.error} /> : !d ? <Loading /> : (
            <>
              <SimpleGrid cols={{ base: 1, sm: 3 }}>
                <Stat label={g.taxIncluded ? 'Breakdown Total (Incl. Tax)' : 'Breakdown Total (Pre-Tax)'} value={money(d.totalCad)} />
                <Stat label={`${g.noun === 'Category' ? 'Categories' : g.noun + 's'}`} value={String(d.rows.length)} />
                <Stat label={`Top ${g.noun}`} value={top ? `${top.share}%` : '-'} sub={top ? `${rowLabel(top)} · ${money(top.amountCad)}` : undefined} />
              </SimpleGrid>
              <div className="ap-table-scroll"><Table striped>
                <Table.Thead><Table.Tr><Table.Th>{g.noun}</Table.Th>
                  <Table.Th ta="right">Spend</Table.Th><Table.Th>Share</Table.Th><Table.Th ta="right">Bills</Table.Th></Table.Tr></Table.Thead>
                <Table.Tbody>
                  {d.rows.map((r) => (
                    <Table.Tr key={r.key} style={{ cursor: 'pointer' }} onClick={() => open(r)} title="Show these bills">
                      <Table.Td><Text size="sm" c="var(--primary)">{rowLabel(r)}</Text></Table.Td>
                      <Table.Td ta="right" ff="monospace">{money(r.amountCad)}</Table.Td>
                      <Table.Td w={240}><Group gap={6} wrap="nowrap"><Progress value={num(r.share)} w={160} /><Text size="xs">{r.share}%</Text></Group></Table.Td>
                      <Table.Td ta="right">{r.invoices}</Table.Td>
                    </Table.Tr>
                  ))}
                </Table.Tbody>
              </Table></div>
            </>
          )}
        </>
      )}
    </Stack>
  )
}
