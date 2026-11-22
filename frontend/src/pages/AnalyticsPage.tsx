import { Card, Divider, Group, Progress, SegmentedControl, SimpleGrid, Stack, Table, Text, TextInput, Title } from '@mantine/core'
import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { api, qs } from '../api/client'
import { Empty, ErrorBox, Hint, Loading } from '../components/bits'
import { money, num } from '../lib/format'
import { MonthlySpendChart } from '../components/MonthlySpendChart'

interface Row { key: string; label: string; amountCad: string; invoices: number; share: string }
interface Resp { groupBy: string; scope: string; totalCad: string; invoiceCount: number; rows: Row[]; monthly: Record<string, string> }

const GROUPS = [
  { value: 'vendor', label: 'Vendor', noun: 'Vendor', basis: 'Bill totals incl. tax, per vendor.', taxIncluded: true },
  { value: 'category', label: 'Category', noun: 'Category', basis: 'Bill totals incl. tax, grouped by vendor category.', taxIncluded: true },
  { value: 'dept', label: 'Department', noun: 'Department', basis: 'Pre-tax line amounts, by the department each line is coded to. Tax is excluded, so this total is lower than total spend above.', taxIncluded: false },
  { value: 'gl', label: 'GL account', noun: 'GL account', basis: 'Pre-tax line amounts, by the GL account each line is coded to. Tax is excluded, so this total is lower than total spend above.', taxIncluded: false },
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
  return (
    <Stack>
      <Group justify="space-between" align="flex-end">
        <div>
          <Title order={3}>Spend analytics</Title>
          <Text size="sm" c="dimmed">CAD, approved / scheduled / paid bills{o && o.scope !== 'all' ? ` · department ${o.scope} only` : ''}</Text>
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
            <Stat label="Total spend (incl. tax)" value={money(o.totalCad)} />
            <Stat label="Bills" value={String(o.invoiceCount)} />
          </SimpleGrid>
          <Card withBorder>
            <Group justify="space-between" mb="xs"><Text fw={600}>By month (incl. tax)</Text><Text fw={700}>{money(o.totalCad)}</Text></Group>
            {/* Lovable line chart (chronological, per-point amounts) in place of afra-work's bars; same o.monthly data. */}
            <MonthlySpendChart monthly={o.monthly} />
          </Card>

          <Divider my="xs" />

          <Group justify="space-between" align="flex-end">
            <Title order={4}>Breakdown</Title>
            <SegmentedControl value={groupBy} onChange={setGroupBy} data={GROUPS.map(({ value, label }) => ({ value, label }))} />
          </Group>
          <Hint>{g.basis}</Hint>
          {breakdown.isError ? <ErrorBox error={breakdown.error} /> : !d ? <Loading /> : (
            <>
              <SimpleGrid cols={{ base: 1, sm: 3 }}>
                <Stat label={g.taxIncluded ? 'Breakdown total (incl. tax)' : 'Breakdown total (pre-tax)'} value={money(d.totalCad)} />
                <Stat label={`${g.noun === 'Category' ? 'Categories' : g.noun + 's'}`} value={String(d.rows.length)} />
                <Stat label={`Top ${g.noun.toLowerCase()}`} value={top ? `${top.share}%` : '-'} sub={top ? `${top.label} · ${money(top.amountCad)}` : undefined} />
              </SimpleGrid>
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
        </>
      )}
    </Stack>
  )
}
