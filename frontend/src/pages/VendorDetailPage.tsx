import { Anchor, Badge, Button, Card, Checkbox, Grid, Group, Modal, Stack, Table, Tabs, Text, TextInput, Textarea, Title } from '@mantine/core'
import { useDisclosure } from '@mantine/hooks'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { api } from '../api/client'
import type { BankChangeRequest, Invoice, Payment, Vendor } from '../api/types'
import { useAuth } from '../auth/AuthContext'
import { ErrorBox, Loading, StatusBadge, Why } from '../components/bits'
import { notifyError, notifyOk } from '../components/notify'
import { date, money } from '../lib/format'
import { useNames } from '../lib/names'

interface BankAccount { id: number; version: number; institution: string | null; transit: string | null; accountNumber: string | null; last4: string; status: string; verified: boolean; verifiedBy: string | null; bankChangeRequestId: string | null }
interface Contract { id: string; description: string; billingType: string; monthlyFee: string | null; hourlyRateCap: string | null; fuelSurchargeCapPct: string | null; notToExceedMonthly: string | null; startDate: string; endDate: string; status: string }
interface CardTxn { id: string; postedDate: string; merchantDescriptor: string; amountCad: string; source: string; memo: string | null; cardholderUserId: string }
interface VendorDetail {
  vendor: Vendor; bankAccounts: BankAccount[]; bankChangeRequests: BankChangeRequest[]; contracts: Contract[]; invoices: Invoice[]
  payments: Payment[]; cardTransactions: CardTxn[]; lookalikes: { id: string; legalName: string; similarity: number }[]
}

export default function VendorDetailPage() {
  const { id } = useParams()
  const { me, is } = useAuth()
  const n = useNames()
  const qc = useQueryClient()
  const q = useQuery({ queryKey: ['vendor', id], queryFn: () => api.get<VendorDetail>(`/vendors/${id}`) })
  const [note, setNote] = useState('')
  if (q.isLoading) return <Loading />
  if (q.error || !q.data) return <ErrorBox error={q.error} />
  const { vendor: v, bankAccounts, bankChangeRequests, contracts, invoices, cardTransactions, lookalikes } = q.data
  const run = async (fn: () => Promise<unknown>, ok: string) => {
    try {
      await fn()
      notifyOk(ok)
      await qc.invalidateQueries()
    } catch (e) {
      notifyError(e)
    }
  }
  const approveBlockers = v.createdBy === me!.id ? ['You onboarded this vendor; a different person must approve it'] : []
  return (
    <Stack>
      <Group justify="space-between" align="flex-start">
        <div>
          <Group gap="xs"><Title order={3}>{v.legalName}</Title>
            <Badge color={v.status === 'active' ? 'green' : v.status === 'on_hold' ? 'red' : 'orange'}>{v.status?.replace('_', ' ')}</Badge></Group>
          <Text c="dimmed">{v.category} · {v.address}</Text>
        </div>
        <Group>
          {is('FM', 'ADM') && v.status !== 'active' && (
            <Why reasons={approveBlockers}>
              <Button disabled={approveBlockers.length > 0} onClick={() => run(() => api.post(`/vendors/${id}/approve`, { note: 'Approved after review' }), 'Vendor approved')}>Approve vendor</Button>
            </Why>
          )}
          {is('CLK', 'ADM') && <BankChangeButton vendorId={id!} onDone={() => qc.invalidateQueries()} />}
        </Group>
      </Group>
      {lookalikes.length > 0 && (
        <Card withBorder style={{ borderLeft: '4px solid var(--mantine-color-red-6)' }} padding="sm">
          <Text size="sm">Similar vendor names: {lookalikes.map((l) => <Anchor key={l.id} component={Link} to={`/vendors/${l.id}`} mr="sm">{l.legalName} ({l.id}, {l.similarity})</Anchor>)}</Text>
        </Card>
      )}
      <Grid>
        <Grid.Col span={{ base: 12, md: 5 }}>
          <Card withBorder>
            <Text fw={600} mb="xs">Master data</Text>
            <Table><Table.Tbody>
              {[['GST/HST #', v.gstHstNumber], ['QST #', v.qstNumber], ['Country', v.country], ['Terms', `${v.paymentTermsDays} days${v.earlyPayDiscount ? ` · ${v.earlyPayDiscount}` : ''}`],
                ['Payment method', v.preferredPaymentMethod], ['AP contact', v.apContactEmail], ['Default GL', v.defaultGlAccount], ['PO required', v.requiresPo ? 'yes' : 'no'],
                ['Onboarded', date(v.onboardedAt)], ['Approved by', v.approvedBy], ['Created by', v.createdBy]].map(([k, val]) => (
                <Table.Tr key={k}><Table.Td c="dimmed">{k}</Table.Td><Table.Td>{val ?? '—'}</Table.Td></Table.Tr>
              ))}
            </Table.Tbody></Table>
          </Card>
        </Grid.Col>
        <Grid.Col span={{ base: 12, md: 7 }}>
          <Card withBorder>
            <Text fw={600} mb="xs">Bank account versions (never edited in place)</Text>
            <Table>
              <Table.Thead><Table.Tr><Table.Th>v</Table.Th><Table.Th>Account</Table.Th><Table.Th>Status</Table.Th><Table.Th>Verified</Table.Th><Table.Th>Change</Table.Th></Table.Tr></Table.Thead>
              <Table.Tbody>
                {bankAccounts.map((a) => (
                  <Table.Tr key={a.id}>
                    <Table.Td>{a.version}</Table.Td>
                    <Table.Td ff="monospace">{a.institution ? `${a.institution}-${a.transit}-` : ''}…{a.last4}</Table.Td>
                    <Table.Td><Badge size="sm" variant="light" color={a.status === 'ACTIVE' ? 'green' : a.status === 'PENDING' ? 'orange' : 'gray'}>{a.status}</Badge></Table.Td>
                    <Table.Td>{a.verified ? <Badge size="sm" color="green" variant="light">yes{a.verifiedBy ? ` (${n.person(a.verifiedBy)})` : ''}</Badge> : <Badge size="sm" color="red">no</Badge>}</Table.Td>
                    <Table.Td>{a.bankChangeRequestId}</Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
            <Text fw={600} mt="md" mb="xs">Bank change requests</Text>
            {bankChangeRequests.length === 0 ? <Text size="sm" c="dimmed">None.</Text> : bankChangeRequests.map((c) => (
              <Card key={c.id} withBorder padding="xs" mb="xs" style={{ borderLeft: `4px solid var(--mantine-color-${c.status === 'PENDING' ? 'red' : 'gray'}-5)` }}>
                <Group justify="space-between">
                  <Text size="sm"><b>{c.id}</b> · {date(c.changedAt)} · …{c.oldAccountLast4} → …{c.newAccountLast4} · via {c.requestChannel?.replace(/_/g, ' ')} · requested by {n.person(c.requestedBy)}</Text>
                  <Badge color={c.status === 'PENDING' ? 'red' : c.status === 'VERIFIED' ? 'green' : 'gray'}>{c.status}</Badge>
                </Group>
                {c.appliedBeforeVerification && c.status === 'PENDING' && <Text size="xs" c="red">Already written into the vendor master before anyone verified it: payments to this vendor are held.</Text>}
                {c.status === 'PENDING' && is('FM', 'ADM') && (
                  <Group mt="xs" align="flex-end">
                    <Textarea size="xs" label="Callback note" placeholder="Called the number on file (not the one in the email)…" value={note} onChange={(e) => setNote(e.currentTarget.value)} style={{ flex: 1 }} autosize />
                    <Why reasons={c.requestedBy === me!.id ? ['You requested this change; someone else must verify it'] : []}>
                      <Button size="xs" color="green" disabled={c.requestedBy === me!.id}
                        onClick={() => run(() => api.post(`/bank-change-requests/${c.id}/verify`, { callbackDone: true, approve: true, note }), `${c.id} verified`)}>Callback done, verify</Button>
                    </Why>
                    <Button size="xs" color="red" variant="light" disabled={c.requestedBy === me!.id}
                      onClick={() => run(() => api.post(`/bank-change-requests/${c.id}/verify`, { callbackDone: true, approve: false, note }), `${c.id} rejected`)}>Reject</Button>
                  </Group>
                )}
                {c.verifiedBy && <Text size="xs" c="dimmed">{c.status?.toLowerCase()} by {n.person(c.verifiedBy)}{c.note ? ` — ${c.note}` : ''}</Text>}
              </Card>
            ))}
          </Card>
        </Grid.Col>
      </Grid>
      <Tabs defaultValue="invoices">
        <Tabs.List>
          <Tabs.Tab value="invoices">Invoices ({invoices.length})</Tabs.Tab>
          <Tabs.Tab value="contracts">Contracts ({contracts.length})</Tabs.Tab>
          <Tabs.Tab value="cards">Card transactions ({cardTransactions.length})</Tabs.Tab>
        </Tabs.List>
        <Tabs.Panel value="invoices" pt="sm">
          <Table striped><Table.Tbody>
            {invoices.slice(0, 60).map((i) => (
              <Table.Tr key={i.id}><Table.Td><Anchor component={Link} to={`/invoices/${i.id}`}>{i.id}</Anchor></Table.Td><Table.Td>#{i.vendorInvoiceNumber}</Table.Td>
                <Table.Td>{date(i.invoiceDate)}</Table.Td><Table.Td ff="monospace">{money(i.totalCad)}</Table.Td><Table.Td><StatusBadge status={i.status} /></Table.Td></Table.Tr>
            ))}
          </Table.Tbody></Table>
        </Tabs.Panel>
        <Tabs.Panel value="contracts" pt="sm">
          <Table><Table.Tbody>
            {contracts.map((c) => (
              <Table.Tr key={c.id}><Table.Td>{c.id}</Table.Td><Table.Td>{c.description}</Table.Td>
                <Table.Td>{c.monthlyFee ? `${money(c.monthlyFee)}/mo` : ''}{c.hourlyRateCap ? ` cap ${money(c.hourlyRateCap)}/h` : ''}{c.fuelSurchargeCapPct ? ` fuel ≤ ${c.fuelSurchargeCapPct}%` : ''}{c.notToExceedMonthly ? ` NTE ${money(c.notToExceedMonthly)}/mo` : ''}</Table.Td>
                <Table.Td>{date(c.startDate)} – {date(c.endDate)}</Table.Td><Table.Td><Badge variant="light" color={c.status === 'active' ? 'green' : 'red'}>{c.status}</Badge></Table.Td></Table.Tr>
            ))}
          </Table.Tbody></Table>
        </Tabs.Panel>
        <Tabs.Panel value="cards" pt="sm">
          <Table><Table.Tbody>
            {cardTransactions.slice(0, 40).map((t) => (
              <Table.Tr key={t.id}><Table.Td>{t.id}</Table.Td><Table.Td>{date(t.postedDate)}</Table.Td><Table.Td>{t.merchantDescriptor}</Table.Td>
                <Table.Td ff="monospace">{money(t.amountCad)}</Table.Td><Table.Td>{t.source.replace(/_/g, ' ')}</Table.Td><Table.Td><Text size="xs">{t.memo}</Text></Table.Td></Table.Tr>
            ))}
          </Table.Tbody></Table>
        </Tabs.Panel>
      </Tabs>
    </Stack>
  )
}

function BankChangeButton({ vendorId, onDone }: { vendorId: string; onDone: () => void }) {
  const [opened, { open, close }] = useDisclosure(false)
  const [f, setF] = useState({ institution: '', transit: '', accountNumber: '', channel: 'email_request', note: '' })
  const [phone, setPhone] = useState(false)
  return (
    <>
      <Button variant="default" onClick={open}>Request bank change</Button>
      <Modal opened={opened} onClose={close} title="Record a bank-detail change request">
        <Stack>
          <Text size="sm" c="dimmed">This never changes the account in use. It creates a new unverified version and holds payments until a Finance Manager verifies it by calling the vendor.</Text>
          <Group grow>
            <TextInput label="Institution" value={f.institution} onChange={(e) => setF({ ...f, institution: e.currentTarget.value })} />
            <TextInput label="Transit" value={f.transit} onChange={(e) => setF({ ...f, transit: e.currentTarget.value })} />
          </Group>
          <TextInput label="Account" value={f.accountNumber} onChange={(e) => setF({ ...f, accountNumber: e.currentTarget.value })} />
          <Checkbox label="Received by phone (otherwise: by email)" checked={phone} onChange={(e) => setPhone(e.currentTarget.checked)} />
          <Textarea label="Note" value={f.note} onChange={(e) => setF({ ...f, note: e.currentTarget.value })} />
          <Button disabled={!f.accountNumber.trim()} onClick={() => api.post(`/vendors/${vendorId}/bank-change-requests`, { ...f, channel: phone ? 'phone_request' : 'email_request' })
            .then(() => { notifyOk('Request recorded; payments held until verified'); close(); onDone() }).catch(notifyError)}>Record request</Button>
        </Stack>
      </Modal>
    </>
  )
}
