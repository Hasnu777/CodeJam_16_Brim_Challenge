import { Alert, Anchor, Badge, Button, Card, Checkbox, Code, Group, Modal, SimpleGrid, Stack, Table, Tabs, Text, Title, Tooltip } from '@mantine/core'
import { IconCreditCard } from '@tabler/icons-react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link } from 'react-router-dom'
import { api } from '../api/client'
import type { CardAdvice, CardChargeResult, CardOpportunity, InvoiceSummary, PaymentBatch, PaymentRow, VirtualCard } from '../api/types'
import { useAuth } from '../auth/AuthContext'
import { Empty, Loading, MoneyCell, Why } from '../components/bits'
import { notifyError, notifyOk } from '../components/notify'
import { date, money, num } from '../lib/format'

const TABS = ['READY', 'SCHEDULED', 'RELEASED', 'SENT', 'SETTLED', 'FAILED']

export default function PaymentsPage() {
  const { is } = useAuth()
  const qc = useQueryClient()
  const [tab, setTab] = useState<string>('SCHEDULED')
  const [picked, setPicked] = useState<string[]>([])
  const [batchFile, setBatchFile] = useState<PaymentBatch | null>(null)
  const [tryCard, setTryCard] = useState<PaymentRow | null>(null)
  const pays = useQuery({ queryKey: ['payments', 'all'], queryFn: () => api.get<PaymentRow[]>('/payments?includeHistory=true') })
  const card = useQuery({ queryKey: ['payments', 'card-opportunity'], queryFn: () => api.get<CardOpportunity>('/analytics/card-opportunity') })
  const ready = useQuery({ queryKey: ['invoices', 'approved'], queryFn: () => api.get<InvoiceSummary[]>('/invoices?status=APPROVED&includeHistory=true') })
  const run = async (fn: () => Promise<unknown>, ok: string) => {
    try {
      await fn()
      notifyOk(ok)
      setPicked([])
      await qc.invalidateQueries()
    } catch (e) {
      notifyError(e)
    }
  }
  if (pays.isLoading) return <Loading />
  const all = pays.data ?? []
  const byStatus = (s: string) => all.filter((p) => p.payment?.status === s)
  const readyRows = (ready.data ?? []).filter((i) => !i.creditNote)
  const total = (rows: PaymentRow[]) => rows.reduce((s, r) => s + num(r.payment?.amountCad), 0)

  return (
    <Stack>
      <Group justify="space-between">
        <div>
          <Title order={3}>Payments</Title>
          <Text size="sm" c="dimmed">Clerks prepare; a Finance Manager (up to $100,000) or the Controller releases; never someone who approved the invoice.</Text>
        </div>
        {is('FM', 'ADM') && byStatus('RELEASED').length > 0 && (
          <Button onClick={() => run(async () => setBatchFile(await api.post<PaymentBatch>('/payments/batch')), 'Payment batch created')}>
            Create payment batch ({byStatus('RELEASED').length})
          </Button>
        )}
      </Group>
      {card.data && <CardOpportunityPanel data={card.data} />}
      <Tabs value={tab} onChange={(v) => { setTab(v ?? 'SCHEDULED'); setPicked([]) }}>
        <Tabs.List>
          {TABS.map((t) => {
            const rows = t === 'READY' ? [] : byStatus(t)
            const n = t === 'READY' ? readyRows.length : rows.length
            return <Tabs.Tab key={t} value={t}>{t === 'READY' ? 'Ready to schedule' : t.charAt(0) + t.slice(1).toLowerCase()} <Badge size="xs" variant="light" ml={4}>{n}</Badge>
              {t !== 'READY' && n > 0 && <Text span size="xs" c="dimmed" ml={4}>{money(total(rows))}</Text>}</Tabs.Tab>
          })}
        </Tabs.List>
        <Tabs.Panel value="READY" pt="md">
          {readyRows.length === 0 ? <Empty>No approved invoices waiting to be scheduled.</Empty> : (
            <Stack>
              {is('CLK') && <Group><Button disabled={!picked.length} onClick={() => run(() => api.post('/payments/prepare', { invoiceIds: picked }), `${picked.length} payment(s) scheduled`)}>
                Prepare {picked.length || ''} payment{picked.length === 1 ? '' : 's'}</Button>
                <Text size="sm" c="dimmed">Scheduled from terms; early-pay discounts and credit notes applied automatically.</Text></Group>}
              <Table striped>
                <Table.Thead><Table.Tr>{is('CLK') && <Table.Th />}<Table.Th>Invoice</Table.Th><Table.Th>Vendor</Table.Th><Table.Th>Amount</Table.Th><Table.Th>Due</Table.Th></Table.Tr></Table.Thead>
                <Table.Tbody>
                  {readyRows.map((i) => (
                    <Table.Tr key={i.id}>
                      {is('CLK') && <Table.Td><Checkbox checked={picked.includes(i.id!)} onChange={(e) => setPicked((p) => e.currentTarget.checked ? [...p, i.id!] : p.filter((x) => x !== i.id))} /></Table.Td>}
                      <Table.Td><Anchor component={Link} to={`/invoices/${i.id}`}>{i.id}</Anchor></Table.Td>
                      <Table.Td>{i.vendorName}</Table.Td>
                      <Table.Td><MoneyCell cad={i.totalCad} amount={i.total} currency={i.currency} /></Table.Td>
                      <Table.Td>{date(i.dueDate)}</Table.Td>
                    </Table.Tr>
                  ))}
                </Table.Tbody>
              </Table>
            </Stack>
          )}
        </Tabs.Panel>
        {TABS.filter((t) => t !== 'READY').map((t) => (
          <Tabs.Panel key={t} value={t} pt="md">
            {byStatus(t).length === 0 ? <Empty>No {t.toLowerCase()} payments.</Empty> : (
              <Table striped>
                <Table.Thead><Table.Tr><Table.Th>Payment</Table.Th><Table.Th>Invoice</Table.Th><Table.Th>Vendor</Table.Th><Table.Th>Amount</Table.Th>
                  <Table.Th>{t === 'SETTLED' ? 'Paid' : 'Scheduled'}</Table.Th><Table.Th>Method / account</Table.Th><Table.Th /></Table.Tr></Table.Thead>
                <Table.Tbody>
                  {byStatus(t).map((r) => {
                    const p = r.payment!
                    const blockers = r.releaseBlockers ?? []
                    return (
                      <Table.Tr key={p.id}>
                        <Table.Td><Text size="sm">{p.id}</Text>{p.historical && <Text size="xs" c="dimmed">history</Text>}</Table.Td>
                        <Table.Td><Anchor component={Link} to={`/invoices/${p.invoiceId}`} size="sm">{p.invoiceId}</Anchor><Text size="xs" c="dimmed">#{r.invoiceNumber}</Text></Table.Td>
                        <Table.Td>{r.vendorName}</Table.Td>
                        <Table.Td>
                          <MoneyCell cad={p.amountCad} amount={p.amount} currency={p.currency} />
                          {num(p.discountTaken) > 0 && <Badge size="xs" color="teal" variant="light">−{money(p.discountTaken)} early-pay</Badge>}
                          {r.earlyDiscountAvailable && <Badge size="xs" color="teal">pay by {date(p.discountDeadline)}</Badge>}
                          {num(p.creditApplied) > 0 && <Badge size="xs" color="gray" variant="light">credit −{money(p.creditApplied)}</Badge>}
                        </Table.Td>
                        <Table.Td>{date(t === 'SETTLED' ? p.paidDate : p.scheduledDate)}</Table.Td>
                        <Table.Td><Text size="sm">{p.method === 'CARD' && r.virtualCard ? 'Brim virtual card' : `${p.method} ${r.bankAccount ?? ''}`}</Text>
                          {p.method !== 'CARD' && r.bankAccount && !r.bankVerified && <Badge size="xs" color="red">unverified</Badge>}
                          {r.virtualCard && <VirtualCardCell card={r.virtualCard} vendorName={r.vendorName}
                            onTry={is('FM', 'ADM') && r.virtualCard.status === 'ISSUED' ? () => setTryCard(r) : undefined} />}
                          {r.cardAdvice && <CardAdviceCell advice={r.cardAdvice} canSwitch={is('CLK', 'FM', 'ADM')}
                            onSwitch={(m) => run(() => api.post(`/payments/${p.id}/method`, { method: m }), m === 'CARD' ? `${p.id} moved to Brim card` : `${p.id} moved back to EFT`)} />}</Table.Td>
                        <Table.Td>
                          {t === 'SCHEDULED' && is('FM', 'ADM') && (
                            <Why reasons={blockers}><Button size="xs" disabled={blockers.length > 0}
                              onClick={() => run(() => api.post(`/payments/${p.id}/release`), `${p.id} released`)}>Release</Button></Why>
                          )}
                          {t === 'SCHEDULED' && is('CLK') && blockers.length > 0 && !blockers.every((b) => b.includes('clerks')) && (
                            <Text size="xs" c="red">{blockers.filter((b) => !b.includes('clerks')).join(' · ')}</Text>
                          )}
                          {t === 'SCHEDULED' && is('CLK') && blockers.some((b) => b.includes('re-prepare')) && (
                            <Button size="xs" variant="light" mt={4}
                              onClick={() => run(() => api.post(`/payments/${p.id}/reprepare`), `${p.id} now pays the vendor's current account`)}>
                              Re-prepare with new bank details</Button>
                          )}
                          {t === 'SENT' && is('FM', 'ADM') && (
                            <Group gap={4}>
                              <Button size="xs" variant="light" onClick={() => run(() => api.post(`/payments/${p.id}/settle`), `${p.id} settled`)}>Mark settled</Button>
                              <Button size="xs" variant="subtle" color="red" onClick={() => run(() => api.post(`/payments/${p.id}/fail`, { reason: 'Returned by bank' }), `${p.id} failed; rescheduled`)}>Failed</Button>
                            </Group>
                          )}
                          {t === 'FAILED' && <Text size="xs" c="red">{p.failureReason}</Text>}
                          {p.batchId && <Text size="xs" c="dimmed">{p.batchId}</Text>}
                        </Table.Td>
                      </Table.Tr>
                    )
                  })}
                </Table.Tbody>
              </Table>
            )}
          </Tabs.Panel>
        ))}
      </Tabs>
      {tryCard && <TryChargeModal row={tryCard} otherVendorId={all.find((x) => x.payment?.vendorId && x.payment.vendorId !== tryCard.payment?.vendorId)?.payment?.vendorId ?? null}
        onClose={() => { setTryCard(null); void qc.invalidateQueries() }} />}
      <Modal opened={!!batchFile} onClose={() => setBatchFile(null)} title={`Simulated payment file ${batchFile?.id ?? ''}`} size="xl">
        <Code block>{batchFile?.fileContent}</Code>
        {batchFile && <Anchor href={`/api/payments/batches/${batchFile.id}/file`} mt="sm" display="block">Download CSV</Anchor>}
      </Modal>
    </Stack>
  )
}

/** Bonus 5: per-payment Brim card advice, with the arithmetic in the tooltip. */
function CardAdviceCell({ advice, canSwitch, onSwitch }: { advice: CardAdvice; canSwitch: boolean; onSwitch: (method: 'CARD' | 'EFT') => void }) {
  if (advice.verdict === 'NOT_ELIGIBLE') return null
  const tip = <Stack gap={2}><Text size="xs" fw={600}>{advice.headline}</Text>{advice.notes.map((n) => <Text key={n} size="xs">{n}</Text>)}</Stack>
  if (advice.verdict === 'KEEP') return <Tooltip label={advice.headline} multiline w={280}><Badge size="xs" color="gray" variant="light">no card advice</Badge></Tooltip>
  const onCard = advice.verdict === 'ON_CARD'
  return (
    <Group gap={4} mt={2} wrap="nowrap">
      <Tooltip label={tip} multiline w={320}>
        <Badge size="xs" color="grape" variant={onCard ? 'filled' : 'light'} leftSection={<IconCreditCard size={10} />}>
          +{money(advice.benefitCad)} · {advice.floatDays}d
        </Badge>
      </Tooltip>
      {canSwitch && (onCard
        ? <Button size="compact-xs" variant="subtle" color="gray" onClick={() => onSwitch('EFT')}>Use EFT</Button>
        : <Button size="compact-xs" variant="light" color="grape" onClick={() => onSwitch('CARD')}>Pay by Brim card</Button>)}
    </Group>
  )
}

function CardOpportunityPanel({ data }: { data: CardOpportunity }) {
  const { scheduledSwitchable: s, paidByEftCouldHaveBeenCard: h, paidByCard: c, terms } = data
  const pct = (r: string) => `${(Number(r) * 100).toFixed(Number(r) * 100 % 1 ? 1 : 0)}%`
  const top = data.byVendor.slice(0, 3)
  return (
    <Card withBorder p="lg" className="ap-stat">
      <Group gap="xs" mb="xs"><IconCreditCard size={18} /><Text fw={600}>Brim card opportunity</Text>
        <Text size="xs" c="dimmed">{data.cardAcceptingVendors} vendors accept card. Paying by card keeps cash in the bank until the statement is due. Each card payment gets its own single-use Brim virtual card when it is released, locked to that vendor and amount.</Text></Group>
      <SimpleGrid cols={{ base: 1, sm: 3 }}>
        <div>
          <Text size="xs" c="dimmed">Scheduled now, could move to card</Text>
          <Text fw={700} size="lg">{money(s.benefitCad)}</Text>
          <Text size="xs" c="dimmed">{s.count} payment{s.count === 1 ? '' : 's'} · {money(s.amountCad)}{s.count > 0 ? ` · ${s.avgFloatDays} days avg float` : ''}</Text>
        </div>
        <div>
          <Text size="xs" c="dimmed">Left on the table (paid by EFT/cheque)</Text>
          <Text fw={700} size="lg" c={num(h.benefitCad) > 0 ? 'grape' : undefined}>{money(h.benefitCad)}</Text>
          <Text size="xs" c="dimmed">{h.count} payments · {money(h.amountCad)}{top.length > 0 ? ` · mostly ${top.map((v) => v.vendorName).join(', ')}` : ''}</Text>
        </div>
        <div>
          <Text size="xs" c="dimmed">Already captured on Brim card</Text>
          <Text fw={700} size="lg">{money(c.benefitCad)}</Text>
          <Text size="xs" c="dimmed">{c.count} payments · {money(c.amountCad)} · {c.avgFloatDays} days avg float</Text>
        </div>
      </SimpleGrid>
      <Text size="xs" c="dimmed" mt="xs">Assumptions: statement closes month-end, due {terms.graceDays} days later; cash valued at {pct(terms.costOfCashAnnual)}/yr; rewards {pct(terms.rewardsRate)}; CAD only, up to {money(terms.perTxnLimitCad)} per charge.</Text>
    </Card>
  )
}

const CARD_STATUS: Record<string, { label: string; color: string }> = {
  ISSUED: { label: 'issued', color: 'grape' },
  CHARGED: { label: 'charged', color: 'blue' },
  CLOSED: { label: 'closed', color: 'gray' },
  CANCELLED: { label: 'cancelled', color: 'red' },
}

/** D-100: the payment's single-use Brim virtual card, with its locks in the tooltip. */
function VirtualCardCell({ card, vendorName, onTry }: { card: VirtualCard; vendorName?: string | null; onTry?: () => void }) {
  const st = CARD_STATUS[card.status ?? ''] ?? { label: card.status ?? '', color: 'gray' }
  const tip = (
    <Stack gap={2}>
      <Text size="xs" fw={600}>Brim virtual card {card.network} ···· {card.last4}</Text>
      <Text size="xs">Single use · locked to {vendorName ?? card.vendorId}</Text>
      <Text size="xs">Limit {money(card.spendLimit)} {card.currency} · expires {date(card.expiresOn)}</Text>
      {card.chargedAmount != null && <Text size="xs">Charged {money(card.chargedAmount)}</Text>}
      {(card.declinedAttempts ?? 0) > 0 && <Text size="xs">{card.declinedAttempts} declined attempt(s); last: {card.lastDecline}</Text>}
    </Stack>
  )
  return (
    <Stack gap={4} mt={4} align="flex-start">
      <Tooltip label={tip} multiline w={320}>
        <Badge size="sm" color={st.color} variant="light" leftSection={<IconCreditCard size={12} />} style={{ overflow: 'visible' }}>
          ···· {card.last4} {st.label}
        </Badge>
      </Tooltip>
      {(card.declinedAttempts ?? 0) > 0 && <Badge size="xs" color="red" variant="outline">{card.declinedAttempts} declined</Badge>}
      {onTry && <Button size="compact-xs" variant="light" color="grape" onClick={onTry}>Try a charge</Button>}
    </Stack>
  )
}

/** Demo of the card locks: the right charge goes through once; anything else is declined and audited. */
function TryChargeModal({ row, otherVendorId, onClose }: { row: PaymentRow; otherVendorId: string | null; onClose: () => void }) {
  const p = row.payment!
  const card = row.virtualCard!
  const [results, setResults] = useState<{ label: string; r: CardChargeResult }[]>([])
  const [busy, setBusy] = useState(false)
  const limit = num(card.spendLimit)
  const attempt = async (label: string, merchantVendorId: string | null, amount: number) => {
    setBusy(true)
    try {
      const r = await api.post<CardChargeResult>(`/payments/${p.id}/card-charge`, { merchantVendorId, amount: amount.toFixed(2) })
      setResults((x) => [{ label, r }, ...x])
    } catch (e) {
      notifyError(e)
    } finally {
      setBusy(false)
    }
  }
  return (
    <Modal opened onClose={onClose} size="lg" title={`Try a charge on ${card.network} ···· ${card.last4}`}>
      <Stack>
        <Text size="sm" c="dimmed">
          This card exists only for {p.id}: {row.vendorName} may charge it once, up to {money(card.spendLimit)} {card.currency}, until {date(card.expiresOn)}.
          Every attempt is written to the audit log.
        </Text>
        <SimpleGrid cols={{ base: 1, sm: 2 }}>
          <Button variant="light" color="red" loading={busy} onClick={() => attempt(`${row.vendorName} charges ${money(limit + 100)} (over the limit)`, p.vendorId ?? null, limit + 100)}>
            Vendor overcharges by $100</Button>
          <Button variant="light" color="red" loading={busy} disabled={!otherVendorId}
            onClick={() => attempt('Another merchant uses the card number', otherVendorId, Math.min(limit, 50))}>
            Another merchant uses it</Button>
          <Button variant="light" loading={busy} onClick={() => attempt(`${row.vendorName} charges the bill (${money(limit)})`, p.vendorId ?? null, limit)}>
            Vendor charges the bill</Button>
          <Button variant="light" color="red" loading={busy} onClick={() => attempt(`${row.vendorName} charges again (${money(limit)})`, p.vendorId ?? null, limit)}>
            Vendor charges a second time</Button>
        </SimpleGrid>
        {results.map(({ label, r }, i) => (
          <Alert key={results.length - i} color={r.approved ? 'teal' : 'red'} title={r.approved ? 'Approved' : 'Declined'}>
            <Text size="sm">{label}</Text>
            <Text size="xs" c="dimmed">{r.reason}</Text>
          </Alert>
        ))}
      </Stack>
    </Modal>
  )
}
