import { Card, Group, Progress, SegmentedControl, Stack, Table, Text, TextInput, Title } from '@mantine/core'
import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { api, qs } from '../api/client'
import { Loading } from '../components/bits'
import { money, num } from '../lib/format'

interface Row { key: string; label: string; amountCad: string; invoices: number; share: string }
interface Resp { groupBy: string; scope: string; totalCad: string; rows: Row[]; monthly: Record<string, string> }

/** Bonus 4: spend by vendor, department or GL account (approved, scheduled and paid bills). */
export default function AnalyticsPage() {
  const [groupBy, setGroupBy] = useState('vendor')
  const [from, setFrom] = useState('2026-01-01')
  const [to, setTo] = useState('2026-12-31')
  const q = useQuery({ queryKey: ['spend', groupBy, from, to], queryFn: () => api.get<Resp>('/analytics/spend' + qs({ groupBy, from, to })) })
  const d = q.data
  const maxMonth = d ? Math.max(...Object.values(d.monthly).map(num), 1) : 1
  return (
    <Stack>
      <Group justify="space-between" align="flex-end">
        <div>
          <Title order={3}>Spend analytics</Title>
          <Text size="sm" c="dimmed">CAD, approved / scheduled / paid bills{d && d.scope !== 'all' ? ` · department ${d.scope} only` : ''}</Text>
        </div>
        <Group align="flex-end">
          <SegmentedControl value={groupBy} onChange={setGroupBy} data={[{ value: 'vendor', label: 'Vendor' }, { value: 'dept', label: 'Department' }, { value: 'gl', label: 'GL account' }]} />
          <TextInput type="date" label="From" value={from} onChange={(e) => setFrom(e.currentTarget.value)} />
          <TextInput type="date" label="To" value={to} onChange={(e) => setTo(e.currentTarget.value)} />
        </Group>
      </Group>
      {!d ? <Loading /> : (
        <>
          <Card withBorder>
            <Group justify="space-between" mb="xs"><Text fw={600}>By month</Text><Text fw={700}>{money(d.totalCad)}</Text></Group>
            <Group align="flex-end" gap={6} h={120} wrap="nowrap">
              {Object.entries(d.monthly).map(([m, v]) => (
                <Stack key={m} gap={2} align="center" style={{ flex: 1 }}>
                  <div title={`${m}: ${money(v)}`} style={{ width: '100%', height: `${(100 * num(v)) / maxMonth}px`, background: 'var(--mantine-color-indigo-5)', borderRadius: 4 }} />
                  <Text size="xs" c="dimmed">{m.slice(5)}</Text>
                </Stack>
              ))}
            </Group>
          </Card>
          <Table striped>
            <Table.Thead><Table.Tr><Table.Th>{groupBy === 'vendor' ? 'Vendor' : groupBy === 'dept' ? 'Department' : 'GL account'}</Table.Th>
              <Table.Th ta="right">Spend</Table.Th><Table.Th>Share</Table.Th><Table.Th ta="right">Invoices</Table.Th></Table.Tr></Table.Thead>
            <Table.Tbody>
              {d.rows.map((r) => (
                <Table.Tr key={r.key}>
                  <Table.Td><Text size="sm">{r.label}</Text>{groupBy === 'gl' && <Text size="xs" c="dimmed">account {r.key}</Text>}</Table.Td>
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
