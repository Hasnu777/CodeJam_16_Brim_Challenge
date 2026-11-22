import {
  Anchor, Badge, Button, Card, Code, Grid, Group, Modal, Paper, ScrollArea, Select, Stack, Table, Tabs, Text, TextInput, Textarea,
  Timeline, Title, Tooltip,
} from '@mantine/core'
import { useDisclosure } from '@mantine/hooks'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { IconAlertTriangle, IconCheck, IconClock, IconX } from '@tabler/icons-react'
import { useEffect, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { api } from '../api/client'
import type { Department, GlAccount, InvoiceDetail, InvoiceLine } from '../api/types'
import { useAuth } from '../auth/AuthContext'
import { ErrorBox, Hint, Loading, StatusBadge, Why } from '../components/bits'
import { FlagCard } from '../components/FlagCard'
import { notifyError, notifyOk } from '../components/notify'
import { date, dateTime, money, num, statusLabel } from '../lib/format'
import { useNames } from '../lib/names'

const EDITABLE = ['RECEIVED', 'EXTRACTED', 'IN_REVIEW', 'ON_HOLD', 'DUPLICATE']
const PAYMENT_HOLDS = ['V01', 'V02', 'B01', 'B02']

export default function InvoiceDetailPage() {
  const { id } = useParams()
  const { me, is } = useAuth()
  const n = useNames()
  const qc = useQueryClient()
  const nav = useNavigate()
  const q = useQuery({ queryKey: ['invoice', id], queryFn: () => api.get<InvoiceDetail>(`/invoices/${id}`) })
  const gl = useQuery({ queryKey: ['gl'], queryFn: () => api.get<GlAccount[]>('/ref/gl-accounts') })
  const depts = useQuery({ queryKey: ['departments'], queryFn: () => api.get<Department[]>('/ref/departments') })
  const [busy, setBusy] = useState<string | null>(null)

  if (q.isLoading) return <Loading />
  if (q.error || !q.data) return <ErrorBox error={q.error} />
  const d = q.data
  const inv = d.invoice
  const refresh = () => qc.invalidateQueries()
  const act = async (name: string, fn: () => Promise<unknown>, ok?: string) => {
    setBusy(name)
    try {
      await fn()
      if (ok) notifyOk(ok)
      await refresh()
    } catch (e) {
      notifyError(e)
    } finally {
      setBusy(null)
    }
  }

  const editable = is('CLK') && !inv.historical && EDITABLE.includes(inv.status!)
  const openFlags = d.flags.filter((f) => f.status === 'OPEN')
  const doneFlags = d.flags.filter((f) => f.status !== 'OPEN')
  // Current approval round only; an earlier round (rejected, reopened, resubmitted) is shown separately.
  const steps = d.approvalSteps.filter((s) => s.decision !== 'CANCELLED' && !s.superseded)
  const earlierSteps = d.approvalSteps.filter((s) => s.decision !== 'CANCELLED' && s.superseded && !s.historical)
  const current = steps.find((s) => s.decision === 'PENDING')
  const actingIds = [me!.id, ...me!.actingFor.map((a) => a.userId)]
  const myTurn = !!current && inv.status === 'PENDING_APPROVAL' && actingIds.includes(current.assignedUserId)
  const approveBlockers: string[] = []
  if (inv.status === 'PENDING_APPROVAL' && current && !myTurn) approveBlockers.push(`Step ${current.seq} is assigned to ${d.users[current.assignedUserId] ?? current.assignedUserId}`)
  if (myTurn && me!.id === inv.requesterUserId) approveBlockers.push('You requested this invoice (self-approval is not allowed)')
  const actingFor = current && me!.id !== current.assignedUserId ? current.assignedUserId : current?.onBehalfOfUserId
  if (myTurn && actingFor && actingFor === inv.requesterUserId) approveBlockers.push('You would act on behalf of the requester (self-approval by proxy); an Admin can re-route this step')
  const blockingOpen = openFlags.filter((f) => f.severity === 'BLOCK' && !PAYMENT_HOLDS.includes(f.ruleCode!))
  if (myTurn && blockingOpen.length) approveBlockers.push(`Resolve first: ${blockingOpen.map((f) => f.title).join(', ')}`)
  const submitBlockers: string[] = []
  if (blockingOpen.length) submitBlockers.push(`A Finance Manager must resolve or override: ${blockingOpen.map((f) => f.title).join(', ')}`)
  const warns = openFlags.filter((f) => f.severity === 'WARN')
  if (warns.length) submitBlockers.push(`Acknowledge the warnings: ${[...new Set(warns.map((f) => f.title))].join(', ')}`)
  if (!inv.departmentId) submitBlockers.push('Set the department')
  const low = new Set((inv.lowConfidenceFields ?? '').split(',').filter(Boolean))

  return (
    <Stack>
      <Group justify="space-between" align="flex-start">
        <div>
          <Group gap="xs">
            <Title order={3}>{inv.id}</Title>
            <StatusBadge status={inv.status} />
            {inv.creditNote && <Badge color="gray">credit note</Badge>}
            {inv.historical && <Badge variant="outline">history</Badge>}
          </Group>
          <Text>
            {d.vendor ? <Anchor component={Link} to={`/vendors/${d.vendor.id}`}>{d.vendor.legalName}</Anchor> : <Text span c="red">{inv.vendorNameRaw ?? 'Unknown vendor'} (not in vendor master)</Text>}
            {' · '}#{inv.vendorInvoiceNumber ?? '?'} · {money(inv.totalCad)}
            {inv.currency !== 'CAD' && <Text span c="dimmed"> ({money(inv.total, inv.currency)})</Text>}
          </Text>
          {inv.holdReason && <Text c="red" size="sm">On hold: {inv.holdReason}</Text>}
        </div>
        <Group>
          {is('CLK') && inv.status === 'IN_REVIEW' && (
            <Why reasons={submitBlockers}>
              <Button disabled={submitBlockers.length > 0} loading={busy === 'submit'}
                onClick={() => act('submit', () => api.post(`/invoices/${id}/submit`), 'Submitted for approval')}>Submit for approval</Button>
            </Why>
          )}
          {is('CLK') && !inv.vendorId && !inv.historical && (
            <Button variant="light" onClick={() => nav(`/vendors?onboard=${inv.id}`)}>Onboard this vendor</Button>
          )}
          {is('CLK') && editable && inv.documentId && (
            <Button variant="default" loading={busy === 'reextract'} onClick={() => act('reextract', () => api.post(`/invoices/${id}/reextract`), 'Re-read the document')}>Re-extract</Button>
          )}
          {is('APR', 'FM', 'ADM') && inv.status === 'PENDING_APPROVAL' && <ApproveReject id={id!} blockers={approveBlockers} onDone={refresh} />}
          {is('CLK', 'FM', 'ADM') && !inv.historical && ['IN_REVIEW', 'PENDING_APPROVAL', 'APPROVED', 'SCHEDULED', 'EXTRACTED'].includes(inv.status!) && (
            <HoldButton id={id!} onDone={refresh} />
          )}
          {is('CLK', 'ADM') && inv.status === 'REJECTED' && !inv.historical && (
            <ReasonButton label="Reopen" title="Reopen for the AP clerk to fix and resubmit" color="blue"
              placeholder="What will be fixed, e.g. corrected the department after the FM's rejection"
              onConfirm={(reason) => api.post(`/invoices/${id}/reopen`, { reason })} done="Reopened: back in review" onDone={refresh} />
          )}
          {is('ADM') && inv.status === 'PENDING_APPROVAL' && (
            <ReasonButton label="Re-route" title="Re-route the pending approval steps" color="gray"
              placeholder="Why, e.g. the approver left the company / is away with no delegate"
              hint="Re-resolves only the steps still waiting, using today's people, leave and delegations. Steps already decided stay, and nobody who already acted is picked again."
              onConfirm={(reason) => api.post(`/invoices/${id}/reroute`, { reason })} done="Pending steps re-routed" onDone={refresh} />
          )}
          {is('CLK', 'FM', 'ADM') && inv.status === 'ON_HOLD' && (
            <Button variant="default" onClick={() => act('rh', () => api.post(`/invoices/${id}/release-hold`, { note: 'Hold lifted' }), 'Hold lifted')}>Lift hold</Button>
          )}
        </Group>
      </Group>

      {d.recommendation && is('APR', 'FM', 'ADM', 'AUD') && ['PENDING_APPROVAL', 'IN_REVIEW'].includes(inv.status!) && (
        <Card withBorder padding="sm" style={{ borderLeft: `4px solid var(--mantine-color-${d.recommendation.verdict === 'APPROVE' ? 'teal' : d.recommendation.verdict === 'HOLD' ? 'red' : 'yellow'}-6)` }}>
          <Text fw={600}>{n.text(d.recommendation.headline)}</Text>
          {d.recommendation.reasons.map((r) => <Text key={r} size="xs" c={r.startsWith('+') ? 'teal' : 'dimmed'}>{n.text(r)}</Text>)}
        </Card>
      )}

      {openFlags.length > 0 && (
        <Stack gap="xs">
          {openFlags.map((f) => (
            <FlagCard key={f.id} flag={f} users={d.users}
              canResolve={f.severity === 'BLOCK' ? is('FM', 'ADM') : is('CLK', 'FM', 'ADM')}
              onResolve={(resolution, note) => act('flag', () => api.post(`/flags/${f.id}/resolve`, { resolution, note }), `${f.ruleCode} ${resolution.toLowerCase()}`)} />
          ))}
          {openFlags.some((f) => f.severity === 'BLOCK') && is('CLK') && (
            <Hint>BLOCK flags can only be overridden by a Finance Manager or Controller. Vendor and bank holds (V01, V02, B01, B02) clear automatically when the vendor is approved or the bank change is verified.</Hint>
          )}
        </Stack>
      )}

      <Grid>
        <Grid.Col span={{ base: 12, lg: 5 }}>
          <Paper withBorder h={760} style={{ overflow: 'hidden' }}>
            {inv.documentId ? <DocumentViewer id={inv.id!} /> : <Stack p="md"><Text c="dimmed">No document: this bill was loaded from the 10-month history CSVs.</Text></Stack>}
          </Paper>
        </Grid.Col>
        <Grid.Col span={{ base: 12, lg: 7 }}>
          <Stack>
            <HeaderFields d={d} editable={editable} low={low} depts={depts.data ?? []} onSaved={refresh} />
            <LinesTable id={id!} lines={d.lines} editable={editable} gl={gl.data ?? []} depts={depts.data ?? []} onSaved={refresh} />
            <Card withBorder padding="sm">
              <Group justify="space-between" mb={4}><Text fw={600}>Taxes</Text>
                <Text size="sm" c="dimmed">Ship-to {inv.shipToProvince ?? '?'}</Text></Group>
              <Table>
                <Table.Tbody>
                  <Table.Tr><Table.Td>Subtotal</Table.Td><Table.Td /><Table.Td ta="right" ff="monospace">{money(inv.subtotal, inv.currency)}</Table.Td></Table.Tr>
                  {d.taxes.map((t) => (
                    <Table.Tr key={t.id}><Table.Td>{t.label}</Table.Td><Table.Td><Badge variant="light" size="sm">{t.taxCode ?? '?'}</Badge></Table.Td>
                      <Table.Td ta="right" ff="monospace">{money(t.amount, inv.currency)}</Table.Td></Table.Tr>
                  ))}
                  {d.taxes.length === 0 && (num(inv.gstHstAmount) !== 0 || num(inv.pstQstAmount) !== 0) && (<>
                    <Table.Tr><Table.Td>GST/HST</Table.Td><Table.Td /><Table.Td ta="right" ff="monospace">{money(inv.gstHstAmount)}</Table.Td></Table.Tr>
                    <Table.Tr><Table.Td>PST/QST</Table.Td><Table.Td /><Table.Td ta="right" ff="monospace">{money(inv.pstQstAmount)}</Table.Td></Table.Tr>
                  </>)}
                  <Table.Tr><Table.Td fw={700}>Total</Table.Td><Table.Td /><Table.Td ta="right" ff="monospace" fw={700}>{money(inv.total, inv.currency)}</Table.Td></Table.Tr>
                  {inv.currency !== 'CAD' && (
                    <Table.Tr><Table.Td colSpan={3}><Text size="sm" c="dimmed">
                      {money(inv.total, inv.currency)} ≈ {money(inv.totalCad)} at {String(inv.fxRateToCad ?? '?')} ({date(inv.fxRateDate)})
                    </Text></Table.Td></Table.Tr>
                  )}
                </Table.Tbody>
              </Table>
            </Card>
          </Stack>
        </Grid.Col>
      </Grid>

      <Tabs defaultValue={steps.length ? 'chain' : 'history'}>
        <Tabs.List>
          <Tabs.Tab value="chain">Approval chain {steps.length ? `(${steps.length})` : ''}</Tabs.Tab>
          <Tabs.Tab value="history">History ({d.history.length})</Tabs.Tab>
          <Tabs.Tab value="related">Related</Tabs.Tab>
          {doneFlags.length > 0 && <Tabs.Tab value="resolved">Resolved flags ({doneFlags.length})</Tabs.Tab>}
          {d.extractions.length > 0 && <Tabs.Tab value="extraction">Extraction</Tabs.Tab>}
        </Tabs.List>
        <Tabs.Panel value="chain" pt="md">
          {steps.length === 0 ? <Text c="dimmed">Not routed yet. The chain is built from the policy table when the clerk submits.</Text> : (
            <Timeline active={steps.filter((s) => s.decision === 'APPROVED').length - 1} bulletSize={24}>
              {steps.map((s, i) => (
                <Timeline.Item key={s.id} title={`${i + 1}. ${n.role(s.requiredRole)} · ${d.users[s.assignedUserId] ?? n.person(s.assignedUserId)}`}
                  bullet={s.decision === 'APPROVED' ? <IconCheck size={14} /> : s.decision === 'REJECTED' ? <IconX size={14} /> : <IconClock size={14} />}
                  color={s.decision === 'REJECTED' ? 'red' : undefined}>
                  {s.onBehalfOfUserId && <Text size="sm" c="grape">on behalf of {d.users[s.onBehalfOfUserId] ?? s.onBehalfOfUserId}</Text>}
                  <Text size="sm">{s.decision === 'PENDING' ? 'Waiting' : `${s.decision.toLowerCase()} by ${d.users[s.actedBy!] ?? s.actedBy}${s.actedOnBehalfOf ? ` on behalf of ${d.users[s.actedOnBehalfOf] ?? s.actedOnBehalfOf}` : ''} · ${dateTime(s.actedAt)}`}</Text>
                  {s.comment && <Text size="sm" fs="italic">"{s.comment}"</Text>}
                  {s.policyIds && <Text size="xs" c="dimmed">Why this step: {s.policyIds.split(',').map((p) => n.policy(p.trim())).join('; ')}</Text>}
                  {s.routingNotes && s.routingNotes.split('\n').map((note) => <Text key={note} size="xs" c="dimmed">↳ {n.text(note)}</Text>)}
                </Timeline.Item>
              ))}
            </Timeline>
          )}
          {earlierSteps.length > 0 && (
            <Stack gap={4} mt="md">
              <Text size="sm" fw={600} c="dimmed">Earlier approval round (before the invoice was rejected and resubmitted)</Text>
              {earlierSteps.map((s) => (
                <Text key={s.id} size="xs" c="dimmed">
                  {n.role(s.requiredRole)} · {d.users[s.assignedUserId] ?? n.person(s.assignedUserId)}: {s.decision === 'PENDING' ? 'not reached' : `${s.decision.toLowerCase()} by ${d.users[s.actedBy!] ?? s.actedBy}`}{s.comment ? ` ("${s.comment}")` : ''}
                </Text>
              ))}
            </Stack>
          )}
          {inv.routedAmountCad && num(inv.routedAmountCad) !== num(inv.totalCad) && (
            <Text size="sm" mt="sm" c="orange"><IconAlertTriangle size={14} /> Routed at the combined split amount {money(inv.routedAmountCad)}, not this bill's {money(inv.totalCad)}.</Text>
          )}
        </Tabs.Panel>
        <Tabs.Panel value="history" pt="md">
          <Timeline bulletSize={14} lineWidth={1}>
            {d.history.map((h) => (
              <Timeline.Item key={h.id} title={<Group gap="xs"><Badge size="sm" variant="light">{statusLabel(h.action ?? '')}</Badge>
                <Text size="sm">{d.users[h.actor!] ?? h.actor}{h.onBehalfOf ? ` on behalf of ${d.users[h.onBehalfOf] ?? h.onBehalfOf}` : ''}</Text>
                <Text size="xs" c="dimmed">{dateTime(h.ts)}</Text></Group>}>
                {h.detail && <Text size="sm">{n.text(h.detail)}</Text>}
              </Timeline.Item>
            ))}
          </Timeline>
        </Tabs.Panel>
        <Tabs.Panel value="related" pt="md">
          <Stack gap="xs">
            <Text size="sm">Requester: {inv.requesterUserId ? d.users[inv.requesterUserId] ?? n.person(inv.requesterUserId) : '—'} · Department: {n.dept(inv.departmentId)}</Text>
            <Text size="sm">PO: {inv.poId ?? '—'} · Contract: {inv.contractId ?? '—'} · Source: {inv.source}{inv.emailId ? ` (${inv.emailId}, ${inv.senderEmail})` : ''}</Text>
            <Text size="sm">Vendor match: {inv.vendorMatchMethod}</Text>
            {d.payments.map((p) => (
              <Text key={p.id} size="sm">Payment <Anchor component={Link} to="/payments">{p.id}</Anchor> · {p.method} · {money(p.amountCad)} · <StatusBadge status={p.status} /> {p.releasedBy ? `released by ${d.users[p.releasedBy] ?? p.releasedBy}` : ''}</Text>
            ))}
          </Stack>
        </Tabs.Panel>
        <Tabs.Panel value="resolved" pt="md">
          <Stack gap="xs">{doneFlags.map((f) => <FlagCard key={f.id} flag={f} canResolve={false} users={d.users} />)}</Stack>
        </Tabs.Panel>
        <Tabs.Panel value="extraction" pt="md">
          {d.extractions.map((x) => (
            <Stack key={x.id} gap="xs">
              <Text size="sm">Method {x.method}{x.model ? ` · ${x.model}` : ''}{x.fromCache ? ' · cached' : ''} · {x.durationMs} ms{x.error ? ` · ${x.error}` : ''}</Text>
              <Text size="xs" c="dimmed">What the reader returned (before normalization and clerk corrections):</Text>
              <ScrollArea h={260}><Code block style={{ fontSize: 11 }}>{x.rawJson ? JSON.stringify(JSON.parse(x.rawJson), null, 2) : '(none: manual entry)'}</Code></ScrollArea>
            </Stack>
          ))}
        </Tabs.Panel>
      </Tabs>
    </Stack>
  )
}

function DocumentViewer({ id }: { id: string }) {
  const [type, setType] = useState<string | null>(null)
  useEffect(() => {
    fetch(`/api/invoices/${id}/document`, { method: 'HEAD' }).then((r) => setType(r.headers.get('content-type'))).catch(() => setType('application/pdf'))
  }, [id])
  if (!type) return null
  if (type.startsWith('image/')) {
    return <ScrollArea h="100%"><img src={`/api/invoices/${id}/document`} alt="Invoice scan" style={{ width: '100%' }} /></ScrollArea>
  }
  return <iframe title="Invoice document" src={`/api/invoices/${id}/document#view=FitH`} style={{ width: '100%', height: '100%', border: 0 }} />
}

function HeaderFields({ d, editable, low, depts, onSaved }: { d: InvoiceDetail; editable: boolean; low: Set<string>; depts: Department[]; onSaved: () => void }) {
  const inv = d.invoice
  const [f, setF] = useState({
    vendorInvoiceNumber: inv.vendorInvoiceNumber ?? '', invoiceDate: inv.invoiceDate ?? '', dueDate: inv.dueDate ?? '',
    total: inv.total != null ? String(inv.total) : '', subtotal: inv.subtotal != null ? String(inv.subtotal) : '',
    departmentId: inv.departmentId ?? '', poId: inv.poId ?? '', shipToProvince: inv.shipToProvince ?? '',
  })
  const [saving, setSaving] = useState(false)
  const hl = (k: string) => (low.has(k) ? { backgroundColor: 'var(--mantine-color-yellow-1)' } : undefined)
  const save = async () => {
    setSaving(true)
    try {
      await api.put(`/invoices/${inv.id}`, {
        vendorInvoiceNumber: f.vendorInvoiceNumber || null, invoiceDate: f.invoiceDate || null, dueDate: f.dueDate || null,
        total: f.total || null, subtotal: f.subtotal || null, departmentId: f.departmentId || null, poId: f.poId,
        shipToProvince: f.shipToProvince || null,
      })
      notifyOk('Saved; coding and validation re-run')
      onSaved()
    } catch (e) {
      notifyError(e)
    } finally {
      setSaving(false)
    }
  }
  const field = (label: string, key: keyof typeof f, lowKey?: string) => (
    <TextInput label={label} value={f[key]} disabled={!editable} onChange={(e) => setF({ ...f, [key]: e.currentTarget.value })}
      styles={{ input: hl(lowKey ?? key) }} description={low.has(lowKey ?? key) ? 'low confidence: check the document' : undefined} />
  )
  return (
    <Card withBorder padding="sm">
      <Group justify="space-between" mb="xs"><Text fw={600}>Invoice fields</Text>
        {low.size > 0 && <Badge color="yellow" variant="light">highlighted = read with low confidence</Badge>}</Group>
      <Grid gutter="xs">
        <Grid.Col span={4}>{field('Invoice #', 'vendorInvoiceNumber', 'invoiceNumber')}</Grid.Col>
        <Grid.Col span={4}>{field('Invoice date', 'invoiceDate')}</Grid.Col>
        <Grid.Col span={4}>{field('Due date', 'dueDate')}</Grid.Col>
        <Grid.Col span={4}>{field('Subtotal', 'subtotal')}</Grid.Col>
        <Grid.Col span={4}>{field('Total', 'total')}</Grid.Col>
        <Grid.Col span={4}><TextInput label="Currency" value={inv.currency ?? ''} disabled /></Grid.Col>
        <Grid.Col span={4}>
          <Select label="Department" value={f.departmentId || null} disabled={!editable} styles={{ input: hl('department') }}
            data={depts.map((x) => ({ value: x.id!, label: x.name! }))} onChange={(v) => setF({ ...f, departmentId: v ?? '' })}
            description={low.has('department') ? 'could not be inferred: please choose' : undefined} />
        </Grid.Col>
        <Grid.Col span={4}>{field('PO', 'poId')}</Grid.Col>
        <Grid.Col span={4}>{field('Ship-to province', 'shipToProvince')}</Grid.Col>
        <Grid.Col span={6}><TextInput label="Remit-to account" value={inv.remitAccount ? `${inv.remitInstitution ?? ''}-${inv.remitTransit ?? ''}-${inv.remitAccount}` : '—'} disabled /></Grid.Col>
        <Grid.Col span={6}><TextInput label="GST # on invoice" value={inv.gstNumberOnInvoice ?? '—'} disabled /></Grid.Col>
      </Grid>
      {editable && <Group justify="flex-end" mt="xs"><Button size="xs" onClick={save} loading={saving}>Save fields</Button></Group>}
    </Card>
  )
}

function LinesTable({ id, lines, editable, gl, depts, onSaved }: { id: string; lines: InvoiceLine[]; editable: boolean; gl: GlAccount[]; depts: Department[]; onSaved: () => void }) {
  const n = useNames()
  const setLine = async (l: InvoiceLine, body: Record<string, string>) => {
    try {
      await api.put(`/invoices/${id}/lines/${l.lineNo}`, body)
      onSaved()
    } catch (e) {
      notifyError(e)
    }
  }
  return (
    <Card withBorder padding="sm">
      <Group justify="space-between" mb="xs"><Text fw={600}>Lines and coding</Text>
        {editable && <Button size="xs" variant="light" onClick={() => api.post(`/invoices/${id}/coding/accept-all`).then(onSaved).catch(notifyError)}>Accept all suggestions</Button>}</Group>
      <Table>
        <Table.Thead><Table.Tr><Table.Th>Description</Table.Th><Table.Th>Qty × price</Table.Th><Table.Th ta="right">Amount</Table.Th><Table.Th>GL account</Table.Th><Table.Th>Dept</Table.Th></Table.Tr></Table.Thead>
        <Table.Tbody>
          {lines.map((l) => (
            <Table.Tr key={l.id}>
              <Table.Td><Text size="sm">{l.description}</Text>{l.lineType && <Text size="xs" c="dimmed">{l.lineType} · {[l.gstHstCode, l.pstQstCode].filter(Boolean).join(' + ')}</Text>}</Table.Td>
              <Table.Td><Text size="sm" ff="monospace">{l.quantity != null ? num(l.quantity) : ''} × {money(l.unitPrice)}</Text></Table.Td>
              <Table.Td ta="right"><Text size="sm" ff="monospace">{money(l.amount)}</Text></Table.Td>
              <Table.Td>
                <Tooltip multiline w={300} label={n.text(l.suggestionReason) || 'No suggestion'} withArrow>
                  <div>
                    {editable ? (
                      <Select size="xs" w={190} searchable value={l.glAccount ?? null} data={gl.map((g) => ({ value: g.accountNumber!, label: `${g.accountNumber} · ${g.name}` }))}
                        onChange={(v) => v && setLine(l, { glAccount: v })} />
                    ) : <Text size="sm">{n.gl(l.glAccount)}</Text>}
                    <Text size="xs" c={l.suggestionOverridden ? 'orange' : 'dimmed'}>{l.suggestionOverridden ? `overridden (suggested ${n.gl(l.suggestedGlAccount)})` : 'why?'}</Text>
                  </div>
                </Tooltip>
              </Table.Td>
              <Table.Td>
                {editable ? (
                  <Select size="xs" w={150} value={l.departmentId ?? null} data={depts.map((x) => ({ value: x.id!, label: x.name! }))} onChange={(v) => v && setLine(l, { departmentId: v })} />
                ) : <Text size="sm">{n.dept(l.departmentId)}</Text>}
              </Table.Td>
            </Table.Tr>
          ))}
          {lines.length === 0 && <Table.Tr><Table.Td colSpan={5}><Text c="dimmed" size="sm">No lines read. Enter the totals above (manual-entry draft).</Text></Table.Td></Table.Tr>}
        </Table.Tbody>
      </Table>
    </Card>
  )
}

function ApproveReject({ id, blockers, onDone }: { id: string; blockers: string[]; onDone: () => void }) {
  const [opened, { open, close }] = useDisclosure(false)
  const [mode, setMode] = useState<'approve' | 'reject'>('approve')
  const [text, setText] = useState('')
  const [busy, setBusy] = useState(false)
  const go = async () => {
    setBusy(true)
    try {
      await api.post(`/invoices/${id}/${mode}`, mode === 'approve' ? { comment: text } : { reason: text })
      notifyOk(mode === 'approve' ? 'Approved' : 'Rejected')
      close()
      onDone()
    } catch (e) {
      notifyError(e)
    } finally {
      setBusy(false)
    }
  }
  return (
    <>
      <Why reasons={blockers}><Button color="teal" disabled={blockers.length > 0} onClick={() => { setMode('approve'); open() }}>Approve</Button></Why>
      <Why reasons={blockers.filter((b) => !b.startsWith('Open BLOCK'))}>
        <Button color="red" variant="light" disabled={blockers.some((b) => !b.startsWith('Open BLOCK'))} onClick={() => { setMode('reject'); open() }}>Reject</Button>
      </Why>
      <Modal opened={opened} onClose={close} title={mode === 'approve' ? 'Approve this step' : 'Reject this invoice'}>
        <Stack>
          <Textarea label={mode === 'approve' ? 'Comment (optional)' : 'Reason (required)'} value={text} onChange={(e) => setText(e.currentTarget.value)} autosize minRows={2} />
          <Button color={mode === 'approve' ? 'teal' : 'red'} loading={busy} disabled={mode === 'reject' && !text.trim()} onClick={go}>
            {mode === 'approve' ? 'Approve' : 'Reject'}
          </Button>
        </Stack>
      </Modal>
    </>
  )
}

function ReasonButton({ label, title, color, placeholder, hint, onConfirm, done, onDone }: {
  label: string; title: string; color: string; placeholder: string; hint?: string
  onConfirm: (reason: string) => Promise<unknown>; done: string; onDone: () => void
}) {
  const [opened, { open, close }] = useDisclosure(false)
  const [reason, setReason] = useState('')
  const [busy, setBusy] = useState(false)
  const go = async () => {
    setBusy(true)
    try {
      await onConfirm(reason)
      notifyOk(done)
      close(); setReason(''); onDone()
    } catch (e) {
      notifyError(e)
    } finally {
      setBusy(false)
    }
  }
  return (
    <>
      <Button variant="light" color={color} onClick={open}>{label}</Button>
      <Modal opened={opened} onClose={close} title={title}>
        <Stack>
          {hint && <Text size="sm" c="dimmed">{hint}</Text>}
          <Textarea label="Reason (required, recorded in the audit log)" placeholder={placeholder} value={reason}
            onChange={(e) => setReason(e.currentTarget.value)} autosize minRows={2} />
          <Button color={color} loading={busy} disabled={!reason.trim()} onClick={go}>{label}</Button>
        </Stack>
      </Modal>
    </>
  )
}

function HoldButton({ id, onDone }: { id: string; onDone: () => void }) {
  const [opened, { open, close }] = useDisclosure(false)
  const [reason, setReason] = useState('')
  return (
    <>
      <Button variant="subtle" color="red" onClick={open}>Hold</Button>
      <Modal opened={opened} onClose={close} title="Place a manual hold">
        <Stack>
          <TextInput label="Reason" value={reason} onChange={(e) => setReason(e.currentTarget.value)} />
          <Button color="red" disabled={!reason.trim()} onClick={() => api.post(`/invoices/${id}/hold`, { reason }).then(() => { close(); onDone() }).catch(notifyError)}>Hold</Button>
        </Stack>
      </Modal>
    </>
  )
}

