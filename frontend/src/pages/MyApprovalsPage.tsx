import { Anchor, Badge, Stack, Table, Text, Title } from '@mantine/core'
import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { api } from '../api/client'
import type { QueueItem } from '../api/types'
import { useAuth } from '../auth/AuthContext'
import { Empty, ErrorBox, Loading, MoneyCell } from '../components/bits'
import { money, num } from '../lib/format'
import { useNames } from '../lib/names'

export default function MyApprovalsPage() {
  const { me } = useAuth()
  const n = useNames()
  const q = useQuery({ queryKey: ['approvals'], queryFn: () => api.get<QueueItem[]>('/approvals/mine') })
  if (q.isLoading) return <Loading />
  if (q.error) return <ErrorBox error={q.error} />
  const rows = q.data ?? []
  return (
    <Stack>
      <div>
        <Title order={3}>My approvals</Title>
        <Text size="sm" c="dimmed">
          Steps assigned to you{me!.actingFor.length ? ` or to ${me!.actingFor.map((a) => a.fullName).join(', ')} (you are their delegate)` : ''}.
          Limit {money(me!.approvalLimitCad)}.
        </Text>
      </div>
      {rows.length === 0 ? <Empty>No invoices are waiting for you.</Empty> : (
        <Table striped highlightOnHover>
          <Table.Thead>
            <Table.Tr><Table.Th>Invoice</Table.Th><Table.Th>Vendor</Table.Th><Table.Th>Amount</Table.Th><Table.Th>Step</Table.Th>
              <Table.Th>For</Table.Th><Table.Th>Top flag</Table.Th><Table.Th>Waiting</Table.Th></Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {rows.map((r) => (
              <Table.Tr key={r.invoiceId}>
                <Table.Td><Anchor component={Link} to={`/invoices/${r.invoiceId}`}>{r.invoiceId}</Anchor><Text size="xs" c="dimmed">#{r.vendorInvoiceNumber}</Text></Table.Td>
                <Table.Td>{r.vendorName}</Table.Td>
                <Table.Td>
                  <MoneyCell cad={r.totalCad} amount={r.total} currency={r.currency} />
                  {r.routedAmountCad && num(r.routedAmountCad) !== num(r.totalCad) && <Text size="xs" c="orange">routed at {money(r.routedAmountCad)} (split)</Text>}
                </Table.Td>
                <Table.Td><Text size="sm">{r.stepSeq} of {r.stepCount} · {n.role(r.role)}</Text></Table.Td>
                <Table.Td>{r.onBehalfOfUserId ? <Badge color="grape" variant="light">on behalf of {n.person(r.onBehalfOfUserId)}</Badge>
                  : r.asDelegate ? <Badge color="grape" variant="light">delegate for {n.person(r.assignedUserId)}</Badge> : <Text size="sm">you</Text>}</Table.Td>
                <Table.Td><Text size="xs">{n.text(r.topFlag) || '—'}</Text></Table.Td>
                <Table.Td><Text size="sm">{r.daysWaiting} d</Text></Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      )}
    </Stack>
  )
}
