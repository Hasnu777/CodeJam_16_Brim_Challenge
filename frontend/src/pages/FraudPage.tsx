import { Anchor, Badge, Card, Group, SimpleGrid, Stack, Table, Text, Title } from '@mantine/core'
import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { api } from '../api/client'
import { Empty, Loading, SeverityBadge } from '../components/bits'
import { money } from '../lib/format'
import { humanize } from '../lib/names'

interface Alert { kind: string; severity: string; title: string; message: string; invoiceId: string | null; vendorId: string | null; vendorName: string | null; amountCad: string | null; ref: string }
interface Resp { alerts: Alert[]; blockedAmountCad: string; countsByCode: Record<string, number> }

/** Bonus 1: every payment-fraud signal in one place, most dangerous first. */
export default function FraudPage() {
  const q = useQuery({ queryKey: ['fraud'], queryFn: () => api.get<Resp>('/fraud/alerts') })
  if (q.isLoading || !q.data) return <Loading />
  const { alerts, blockedAmountCad, countsByCode } = q.data
  return (
    <Stack>
      <div>
        <Title order={3}>Fraud alerts</Title>
        <Text size="sm" c="dimmed">Bank details changed or unverified, remit-to accounts that differ, lookalike vendors and sender domains, bills already paid by card.</Text>
      </div>
      <SimpleGrid cols={{ base: 2, md: 4 }}>
        <Card withBorder><Text size="xs" c="dimmed" tt="uppercase" fw={600}>Payments held</Text><Text size="xl" fw={700} c="red">{money(blockedAmountCad)}</Text></Card>
        <Card withBorder><Text size="xs" c="dimmed" tt="uppercase" fw={600}>Alerts</Text><Text size="xl" fw={700}>{alerts.length}</Text></Card>
        <Card withBorder style={{ gridColumn: 'span 2' }}><Text size="xs" c="dimmed" tt="uppercase" fw={600}>By signal</Text>
          <Group gap={6} mt={4}>{Object.entries(countsByCode).map(([k, v]) => <Badge key={k} variant="light">{k} × {v}</Badge>)}</Group></Card>
      </SimpleGrid>
      {alerts.length === 0 ? <Empty>No open fraud signals.</Empty> : (
        <Table striped>
          <Table.Thead><Table.Tr><Table.Th /><Table.Th>Signal</Table.Th><Table.Th>Vendor</Table.Th><Table.Th>Bill</Table.Th><Table.Th ta="right">At stake</Table.Th></Table.Tr></Table.Thead>
          <Table.Tbody>
            {alerts.map((a, i) => (
              <Table.Tr key={i}>
                <Table.Td><SeverityBadge severity={a.severity} /></Table.Td>
                <Table.Td maw={560}><Text size="sm" fw={600}>{a.title}</Text><Text size="sm">{humanize(a.message)}</Text></Table.Td>
                <Table.Td>{a.vendorId ? <Anchor component={Link} to={`/vendors/${a.vendorId}`}>{a.vendorName}</Anchor> : a.vendorName}</Table.Td>
                <Table.Td>{a.invoiceId && <Anchor component={Link} to={`/invoices/${a.invoiceId}`}>{a.invoiceId}</Anchor>}</Table.Td>
                <Table.Td ta="right" ff="monospace">{money(a.amountCad)}</Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      )}
    </Stack>
  )
}
