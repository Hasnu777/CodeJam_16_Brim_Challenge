import { Anchor, Badge, Button, Card, Group, SimpleGrid, Stack, Table, Tabs, Text, Title } from '@mantine/core'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { api } from '../api/client'
import { useAuth } from '../auth/AuthContext'
import { Loading } from '../components/bits'
import { notifyError, notifyOk } from '../components/notify'
import { date, money } from '../lib/format'

interface Match { bankTxnId: string | null; postedDate: string | null; description: string; amount: string; paymentId: string | null; invoiceId: string | null; vendorName: string | null; status: string; explanation: string }
interface Resp { matched: Match[]; unmatchedStatementLines: Match[]; unmatchedPayments: Match[]; summary: Record<string, number> }

/** Bonus 2: match settled payments to bank statement debits; explain everything that doesn't match. */
export default function ReconciliationPage() {
  const { is } = useAuth()
  const qc = useQueryClient()
  const q = useQuery({ queryKey: ['recon'], queryFn: () => api.get<Resp>('/reconciliation') })
  if (q.isLoading || !q.data) return <Loading />
  const { matched, unmatchedStatementLines, unmatchedPayments, summary } = q.data
  const table = (rows: Match[], showPay = true) => (
    <Table striped>
      <Table.Thead><Table.Tr><Table.Th>Date</Table.Th><Table.Th>Statement / reference</Table.Th><Table.Th ta="right">Amount</Table.Th>
        {showPay && <Table.Th>Payment</Table.Th>}<Table.Th>Explanation</Table.Th></Table.Tr></Table.Thead>
      <Table.Tbody>
        {rows.map((r, i) => (
          <Table.Tr key={i}>
            <Table.Td><Text size="xs">{date(r.postedDate)}</Text></Table.Td>
            <Table.Td><Text size="sm">{r.description}</Text>{r.vendorName && <Text size="xs" c="dimmed">{r.vendorName}</Text>}</Table.Td>
            <Table.Td ta="right" ff="monospace">{money(r.amount)}</Table.Td>
            {showPay && <Table.Td>{r.paymentId}{r.invoiceId && <> · <Anchor component={Link} to={`/invoices/${r.invoiceId}`} size="sm">{r.invoiceId}</Anchor></>}</Table.Td>}
            <Table.Td><Group gap={4}>{r.status !== 'MATCHED' && <Badge size="xs" color={r.status === 'UNMATCHED' ? 'orange' : 'red'}>{r.status}</Badge>}
              <Text size="xs">{r.explanation}</Text></Group></Table.Td>
          </Table.Tr>
        ))}
      </Table.Tbody>
    </Table>
  )
  return (
    <Stack>
      <Group justify="space-between">
        <div>
          <Title order={3}>Bank reconciliation</Title>
          <Text size="sm" c="dimmed">Statement debits matched to payments by reference digits / cheque number and amount. Deterministic; every leftover gets a likely explanation.</Text>
        </div>
        {is('FM', 'ADM') && summary.canSettle > 0 && (
          <Button onClick={() => api.post<{ settled: string[] }>('/reconciliation/apply').then((r) => { notifyOk(`${r.settled.length} payments settled from the statement`); qc.invalidateQueries() }).catch(notifyError)}>
            Settle {summary.canSettle} from statement</Button>
        )}
      </Group>
      <SimpleGrid cols={{ base: 2, md: 4 }}>
        {[['Statement lines', summary.statementLines], ['Matched', summary.matched], ['Unmatched lines', summary.unmatchedLines], ['Payments not on statement', summary.unmatchedPayments]]
          .map(([k, v]) => <Card key={k} withBorder><Text size="xs" c="dimmed" tt="uppercase" fw={600}>{k}</Text><Text size="xl" fw={700}>{v}</Text></Card>)}
      </SimpleGrid>
      <Tabs defaultValue="lines">
        <Tabs.List>
          <Tabs.Tab value="lines">Unmatched statement lines ({unmatchedStatementLines.length})</Tabs.Tab>
          <Tabs.Tab value="payments">Payments not on the statement ({unmatchedPayments.length})</Tabs.Tab>
          <Tabs.Tab value="matched">Matched ({matched.length})</Tabs.Tab>
        </Tabs.List>
        <Tabs.Panel value="lines" pt="sm">{table(unmatchedStatementLines, false)}</Tabs.Panel>
        <Tabs.Panel value="payments" pt="sm">{table(unmatchedPayments)}</Tabs.Panel>
        <Tabs.Panel value="matched" pt="sm">{table(matched.slice(0, 300))}</Tabs.Panel>
      </Tabs>
    </Stack>
  )
}
