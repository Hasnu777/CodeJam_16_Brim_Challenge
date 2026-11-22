import { Alert, Anchor, Badge, Button, Card, Checkbox, Group, Modal, Radio, SimpleGrid, Stack, Table, Tabs, Text, Textarea, Title, Tooltip } from '@mantine/core'
import { IconInfoCircle } from '@tabler/icons-react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link } from 'react-router-dom'
import { api } from '../api/client'
import { useAuth } from '../auth/AuthContext'
import { Loading } from '../components/bits'
import { notifyError, notifyOk } from '../components/notify'
import { date, dateTime, money, statusLabel } from '../lib/format'
import { useNames } from '../lib/names'

type Kind = 'LINE' | 'PAYMENT'
interface Match { bankTxnId: string | null; postedDate: string | null; description: string; amount: string; paymentId: string | null; invoiceId: string | null; vendorName: string | null; status: string; explanation: string }
interface Resolution { itemKey: string; kind: Kind; itemId: string; resolution: string; note: string | null; resolvedBy: string; resolvedAt: string }
interface Option { code: string; kind: Kind; label: string; description: string; scope: 'app' | 'external'; outOfScope: string | null; closes: boolean; noteRequired: boolean }
interface Resp {
  matched: Match[]; unmatchedStatementLines: Match[]; unmatchedPayments: Match[]; summary: Record<string, number>
  resolutions: Record<string, Resolution>; options: Option[]
}

const idOf = (kind: Kind, m: Match) => (kind === 'LINE' ? m.bankTxnId : m.paymentId) ?? ''
const keyOf = (kind: Kind, m: Match) => `${kind}:${idOf(kind, m)}`

function ScopeBadge({ o }: { o: Option }) {
  return o.scope === 'app'
    ? <Badge size="xs" variant="light" color="teal">Done in this app</Badge>
    : <Tooltip multiline w={320} label={o.outOfScope}><Badge size="xs" variant="light" color="gray" style={{ cursor: 'help' }}>Recorded only: needs bank or ledger</Badge></Tooltip>
}

/**
 * Bonus 2: match payments to bank statement debits, then explain every leftover (D-109). The Finance Manager or
 * Controller records how each leftover was explained; options that need a bank or general ledger are recorded as the
 * decision only, since the dataset has neither.
 */
export default function ReconciliationPage() {
  const { is } = useAuth()
  const n = useNames()
  const qc = useQueryClient()
  const q = useQuery({ queryKey: ['recon'], queryFn: () => api.get<Resp>('/reconciliation') })
  const [picked, setPicked] = useState<Record<Kind, string[]>>({ LINE: [], PAYMENT: [] })
  const [resolving, setResolving] = useState<{ kind: Kind; ids: string[]; preset?: string } | null>(null)
  if (q.isLoading || !q.data) return <Loading />
  const { matched, unmatchedStatementLines, unmatchedPayments, summary, resolutions, options } = q.data
  const canAct = is('FM', 'ADM')
  const opt = (kind: Kind, code?: string) => options.find((o) => o.kind === kind && o.code === code)
  const res = (kind: Kind, m: Match) => resolutions[keyOf(kind, m)]
  const closed = (kind: Kind, m: Match) => !!opt(kind, res(kind, m)?.resolution)?.closes
  const open = { LINE: unmatchedStatementLines.filter((m) => !closed('LINE', m)), PAYMENT: unmatchedPayments.filter((m) => !closed('PAYMENT', m)) }
  const explained = [
    ...unmatchedStatementLines.filter((m) => closed('LINE', m)).map((m) => ({ kind: 'LINE' as Kind, m })),
    ...unmatchedPayments.filter((m) => closed('PAYMENT', m)).map((m) => ({ kind: 'PAYMENT' as Kind, m })),
  ]
  const fees = open.LINE.filter((m) => m.explanation.startsWith('Bank fee')).map((m) => idOf('LINE', m))
  const reopen = (kind: Kind, id: string) =>
    api.post('/reconciliation/resolve', { kind, itemIds: [id], resolution: null })
      .then(() => { notifyOk('Reopened'); qc.invalidateQueries({ queryKey: ['recon'] }) }).catch(notifyError)

  const openTable = (kind: Kind, rows: Match[]) => {
    const sel = picked[kind]
    const toggle = (id: string, on: boolean) => setPicked((p) => ({ ...p, [kind]: on ? [...p[kind], id] : p[kind].filter((x) => x !== id) }))
    return (
      <Stack gap="xs">
        {canAct && (
          <Group>
            <Button size="xs" disabled={!sel.length} onClick={() => setResolving({ kind, ids: sel })}>Resolve Selected{sel.length ? ` (${sel.length})` : ''}</Button>
            {kind === 'LINE' && fees.length > 0 && (
              <Button size="xs" variant="light" onClick={() => setResolving({ kind, ids: fees, preset: 'BANK_CHARGE' })}>Mark {fees.length} Bank Fees as Bank Charges</Button>
            )}
          </Group>
        )}
        {rows.length === 0 ? <Text c="dimmed" size="sm">Nothing left to explain here.</Text> : (
          <Table striped>
            <Table.Thead><Table.Tr>
              {canAct && <Table.Th w={70}>Select</Table.Th>}
              <Table.Th>Date</Table.Th><Table.Th>{kind === 'LINE' ? 'Statement Line' : 'Payment'}</Table.Th><Table.Th ta="right">Amount</Table.Th>
              <Table.Th>Likely Explanation</Table.Th>{canAct && <Table.Th />}
            </Table.Tr></Table.Thead>
            <Table.Tbody>
              {rows.map((r) => {
                const id = idOf(kind, r)
                const r0 = res(kind, r)
                return (
                  <Table.Tr key={id}>
                    {canAct && <Table.Td><Checkbox aria-label={`Select ${id}`} checked={sel.includes(id)} onChange={(e) => { const on = e.currentTarget.checked; toggle(id, on) }} /></Table.Td>}
                    <Table.Td><Text size="xs">{date(r.postedDate)}</Text></Table.Td>
                    <Table.Td>
                      <Text size="sm">{kind === 'LINE' ? r.description : <>{r.paymentId}{r.invoiceId && <> · <Anchor component={Link} to={`/invoices/${r.invoiceId}`} size="sm">{r.invoiceId}</Anchor></>}</>}</Text>
                      {r.vendorName && <Text size="xs" c="dimmed">{r.vendorName}{kind === 'PAYMENT' ? ` · ${statusLabel(r.status)}` : ''}</Text>}
                    </Table.Td>
                    <Table.Td ta="right" ff="monospace">{money(r.amount)}</Table.Td>
                    <Table.Td>
                      {r.status === 'AMOUNT_DIFFERS' && <Badge size="xs" color="red" mb={2}>Amount Differs</Badge>}
                      <Text size="xs">{r.explanation}</Text>
                      {r0 && <Group gap={6} mt={4}><Badge size="xs" color="yellow" variant="light">Under Investigation</Badge>
                        <Text size="xs" c="dimmed">{r0.note} · {n.person(r0.resolvedBy)}, {dateTime(r0.resolvedAt)}</Text></Group>}
                    </Table.Td>
                    {canAct && <Table.Td><Button size="xs" variant="default" onClick={() => setResolving({ kind, ids: [id] })}>Resolve</Button></Table.Td>}
                  </Table.Tr>
                )
              })}
            </Table.Tbody>
          </Table>
        )}
      </Stack>
    )
  }

  return (
    <Stack>
      <Group justify="space-between">
        <div>
          <Title order={3}>Bank Reconciliation</Title>
          <Text size="sm" c="dimmed">The bank statement matched to our payments. Every line left over is explained before the month is closed.</Text>
        </div>
        {canAct && summary.canSettle > 0 && (
          <Button onClick={() => api.post<{ settled: string[] }>('/reconciliation/apply').then((r) => { notifyOk(`${r.settled.length} payments settled from the statement`); qc.invalidateQueries() }).catch(notifyError)}>
            Settle {summary.canSettle} From Statement</Button>
        )}
      </Group>
      <SimpleGrid cols={{ base: 2, md: 4 }}>
        {[['Statement Lines', summary.statementLines], ['Matched', summary.matched], ['Still To Explain', open.LINE.length + open.PAYMENT.length], ['Explained', explained.length]]
          .map(([k, v]) => <Card key={k} withBorder><Text size="xs" c="dimmed" fw={600}>{k}</Text><Text size="xl" fw={700}>{v}</Text></Card>)}
      </SimpleGrid>
      <Alert variant="light" color="blue" icon={<IconInfoCircle size={16} />} title="Handling What Doesn't Match">
        <Text size="sm">
          The Finance Manager or Controller explains each leftover. <b>Statement lines</b> are money the bank moved with no payment behind it:
          a bank charge, a supplier's pre-authorized debit (find the bill), or a debit nobody authorized (dispute it). <b>Payments not on the
          statement</b> are payments we recorded that the bank doesn't show yet: usually timing, or a cheque that was never cashed (confirm
          with the vendor; stop and reissue if lost). Every decision is audited. Options marked <i>Recorded only</i> are carried out in the
          bank or general ledger, which this dataset doesn't include, so the app records the decision and what happens next.
        </Text>
      </Alert>
      <Tabs defaultValue="lines">
        <Tabs.List>
          <Tabs.Tab value="lines">Statement Lines To Explain ({open.LINE.length})</Tabs.Tab>
          <Tabs.Tab value="payments">Payments Not on the Statement ({open.PAYMENT.length})</Tabs.Tab>
          <Tabs.Tab value="explained">Explained ({explained.length})</Tabs.Tab>
          <Tabs.Tab value="matched">Matched ({matched.length})</Tabs.Tab>
        </Tabs.List>
        <Tabs.Panel value="lines" pt="sm">{openTable('LINE', open.LINE)}</Tabs.Panel>
        <Tabs.Panel value="payments" pt="sm">{openTable('PAYMENT', open.PAYMENT)}</Tabs.Panel>
        <Tabs.Panel value="explained" pt="sm">
          {explained.length === 0 ? <Text c="dimmed" size="sm">Nothing explained yet.</Text> : (
            <Table striped>
              <Table.Thead><Table.Tr><Table.Th>Date</Table.Th><Table.Th>Item</Table.Th><Table.Th ta="right">Amount</Table.Th><Table.Th>Resolution</Table.Th><Table.Th>By</Table.Th>{canAct && <Table.Th />}</Table.Tr></Table.Thead>
              <Table.Tbody>
                {explained.map(({ kind, m }) => {
                  const r0 = res(kind, m)!
                  const o = opt(kind, r0.resolution)!
                  return (
                    <Table.Tr key={keyOf(kind, m)}>
                      <Table.Td><Text size="xs">{date(m.postedDate)}</Text></Table.Td>
                      <Table.Td><Text size="sm">{kind === 'LINE' ? m.description : m.paymentId}</Text>
                        <Text size="xs" c="dimmed">{kind === 'LINE' ? 'Statement line' : `Payment · ${m.vendorName ?? ''}`}</Text></Table.Td>
                      <Table.Td ta="right" ff="monospace">{money(m.amount)}</Table.Td>
                      <Table.Td><Group gap={6}><Text size="sm" fw={600}>{o.label}</Text><ScopeBadge o={o} /></Group>
                        {r0.note && <Text size="xs" c="dimmed">{r0.note}</Text>}</Table.Td>
                      <Table.Td><Text size="xs">{n.person(r0.resolvedBy)}</Text><Text size="xs" c="dimmed">{dateTime(r0.resolvedAt)}</Text></Table.Td>
                      {canAct && <Table.Td><Button size="xs" variant="subtle" color="gray" onClick={() => reopen(kind, idOf(kind, m))}>Reopen</Button></Table.Td>}
                    </Table.Tr>
                  )
                })}
              </Table.Tbody>
            </Table>
          )}
        </Tabs.Panel>
        <Tabs.Panel value="matched" pt="sm">
          <Table striped>
            <Table.Thead><Table.Tr><Table.Th>Date</Table.Th><Table.Th>Statement Line</Table.Th><Table.Th ta="right">Amount</Table.Th><Table.Th>Payment</Table.Th><Table.Th>How</Table.Th></Table.Tr></Table.Thead>
            <Table.Tbody>
              {matched.slice(0, 300).map((r) => (
                <Table.Tr key={r.bankTxnId ?? r.paymentId}>
                  <Table.Td><Text size="xs">{date(r.postedDate)}</Text></Table.Td>
                  <Table.Td><Text size="sm">{r.description}</Text>{r.vendorName && <Text size="xs" c="dimmed">{r.vendorName}</Text>}</Table.Td>
                  <Table.Td ta="right" ff="monospace">{money(r.amount)}</Table.Td>
                  <Table.Td>{r.paymentId}{r.invoiceId && <> · <Anchor component={Link} to={`/invoices/${r.invoiceId}`} size="sm">{r.invoiceId}</Anchor></>}</Table.Td>
                  <Table.Td><Group gap={4}>{r.status !== 'MATCHED' && <Badge size="xs" color="red">{statusLabel(r.status)}</Badge>}<Text size="xs">{r.explanation}</Text></Group></Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </Tabs.Panel>
      </Tabs>
      {resolving && (
        <ResolveModal kind={resolving.kind} ids={resolving.ids} preset={resolving.preset} options={options.filter((o) => o.kind === resolving.kind)}
          onClose={() => setResolving(null)}
          onDone={() => { setPicked((p) => ({ ...p, [resolving.kind]: [] })); setResolving(null); qc.invalidateQueries({ queryKey: ['recon'] }) }} />
      )}
    </Stack>
  )
}

function ResolveModal({ kind, ids, preset, options, onClose, onDone }: {
  kind: Kind; ids: string[]; preset?: string; options: Option[]; onClose: () => void; onDone: () => void
}) {
  const [code, setCode] = useState<string | null>(preset ?? null)
  const [note, setNote] = useState('')
  const [busy, setBusy] = useState(false)
  const o = options.find((x) => x.code === code)
  const save = async () => {
    setBusy(true)
    try {
      await api.post('/reconciliation/resolve', { kind, itemIds: ids, resolution: code, note: note.trim() || null })
      notifyOk(`${ids.length} item${ids.length === 1 ? '' : 's'}: ${o?.label}`)
      onDone()
    } catch (e) {
      notifyError(e)
    } finally {
      setBusy(false)
    }
  }
  return (
    <Modal opened onClose={onClose} size="lg" title={`Resolve ${ids.length} ${kind === 'LINE' ? 'Statement Line' : 'Payment'}${ids.length === 1 ? '' : 's'}`}>
      <Stack>
        <Radio.Group value={code} onChange={setCode}>
          <Stack gap="xs">
            {options.map((x) => (
              <Radio key={x.code} value={x.code} label={<Group gap={6}><Text size="sm" fw={600}>{x.label}</Text><ScopeBadge o={x} /></Group>}
                description={x.description} />
            ))}
          </Stack>
        </Radio.Group>
        {o?.scope === 'external' && (
          <Alert color="gray" variant="light" title="What happens next (outside this app)"><Text size="sm">{o.outOfScope}</Text></Alert>
        )}
        <Textarea label={o?.noteRequired ? 'Note' : 'Note (optional)'} required={o?.noteRequired} autosize minRows={2} value={note}
          placeholder={kind === 'LINE' ? 'e.g. Called the bank: monthly pre-authorized debit from the warehouse internet provider' : 'e.g. Spoke to the landlord on Nov 3: cheque never arrived'}
          onChange={(e) => setNote(e.currentTarget.value)} />
        <Group justify="flex-end">
          <Button variant="subtle" color="gray" onClick={onClose}>Cancel</Button>
          <Button loading={busy} disabled={!o || (o.noteRequired && !note.trim())} onClick={save}>Save</Button>
        </Group>
      </Stack>
    </Modal>
  )
}
