import { Anchor, Badge, Button, Card, CloseButton, Grid, Group, Modal, Paper, ScrollArea, Stack, Table, Text, Textarea, TextInput, Timeline, Title } from '@mantine/core'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { api } from '../api/client'
import type { EmailMessage } from '../api/types'
import { Empty, ErrorBox, Loading, StatusBadge } from '../components/bits'
import { useAuth } from '../auth/AuthContext'
import { notifyError, notifyOk } from '../components/notify'
import { dateTime, money, statusLabel } from '../lib/format'
import { useNames } from '../lib/names'
import { KIND } from './InboxPage'

interface AttachmentInfo { name: string; available: boolean; contentType: string | null; sizeBytes: number | null }
interface LinkedInvoice { id: string; status: string; vendorId: string | null; vendorInvoiceNumber: string | null; total: string | null; currency: string | null }
interface HistoryItem { ts: string; actor: string; action: string; detail: string | null; after?: string | null }
// messageId is set for mail fetched over IMAP (not yet in the generated schema.d.ts)
interface EmailView { email: EmailMessage & { messageId?: string | null }; processed: boolean; attachments: AttachmentInfo[]; linkedInvoices: LinkedInvoice[]; history: HistoryItem[] }

const url = (id: string, name: string) => `/api/inbox/${encodeURIComponent(id)}/attachments/${encodeURIComponent(name)}`

function size(b: number | null) {
  if (b == null) return ''
  return b < 1024 * 1024 ? `${Math.max(1, Math.round(b / 1024))} KB` : `${(b / 1024 / 1024).toFixed(1)} MB`
}

/** One inbox email, before or after processing: the message as received, then what intake did with it. */
export default function EmailDetailPage() {
  const { id } = useParams()
  const q = useQuery({ queryKey: ['inbox', id], queryFn: () => api.get<EmailView>(`/inbox/${id}`) })
  const [preview, setPreview] = useState<string | null>(null)
  const [replying, setReplying] = useState(false)
  const [processing, setProcessing] = useState(false)
  const { is } = useAuth()
  const n = useNames()
  const qc = useQueryClient()

  if (q.isLoading) return <Loading />
  if (q.error) return <ErrorBox error={q.error} />
  const d = q.data!
  const e = d.email
  const kind = e.classification ? KIND[e.classification] : undefined
  // Nothing is previewed until someone clicks Preview; the email then uses the full width.
  const shownInfo = preview ? d.attachments.find((a) => a.name === preview && a.available) : undefined
  const shown = shownInfo?.name ?? null

  return (
    <Stack>
      <Anchor component={Link} to="/inbox" size="sm">← Inbox</Anchor>
      <Group justify="space-between" align="flex-start">
        <Title order={3}>{e.subject || '(no subject)'}</Title>
        <Group gap="xs">
          {d.processed
            ? <Badge color={kind?.color} variant="light" size="lg">{kind?.label ?? e.classification}</Badge>
            : <Badge color="gray" variant="outline" size="lg">Not Processed</Badge>}
          {is('CLK', 'ADM') && !d.processed && (
            <Button loading={processing} onClick={async () => {
              setProcessing(true)
              try {
                await api.post(`/inbox/${e.id}/process`)
                notifyOk('Email processed')
                await qc.invalidateQueries()
              } catch (err) {
                notifyError(err)
              } finally {
                setProcessing(false)
              }
            }}>Process</Button>
          )}
          {is('CLK', 'ADM') && <Button variant="default" onClick={() => setReplying(true)}>Reply</Button>}
        </Group>
      </Group>
      {replying && <ReplyModal email={e} draft={latestDraft(d.history)} onClose={() => setReplying(false)} onSaved={() => qc.invalidateQueries({ queryKey: ['inbox', id] })} />}

      <Grid>
        <Grid.Col span={{ base: 12, md: shown ? 6 : 12 }}>
          <Stack>
            <Card withBorder>
              <Text fw={600} mb="xs">Message</Text>
              <div className="ap-table-scroll"><Table withRowBorders={false} verticalSpacing={2}>
                <Table.Tbody>
                  <Table.Tr><Table.Td w={90}><Text size="sm" c="dimmed">From</Text></Table.Td>
                    <Table.Td><Text size="sm">{e.fromName} {e.fromAddress && <Text span c="dimmed">&lt;{e.fromAddress}&gt;</Text>}</Text></Table.Td></Table.Tr>
                  <Table.Tr><Table.Td><Text size="sm" c="dimmed">To</Text></Table.Td><Table.Td><Text size="sm">{e.toAddress ?? '—'}</Text></Table.Td></Table.Tr>
                  <Table.Tr><Table.Td><Text size="sm" c="dimmed">Received</Text></Table.Td><Table.Td><Text size="sm">{dateTime(e.receivedAt)}</Text></Table.Td></Table.Tr>
                  <Table.Tr><Table.Td><Text size="sm" c="dimmed">Source</Text></Table.Td>
                    <Table.Td><Text size="sm">{e.messageId ? 'AP mailbox (IMAP)' : 'Dataset inbox (emails.json)'}</Text></Table.Td></Table.Tr>
                </Table.Tbody>
              </Table></div>
              <Paper withBorder p="sm" mt="sm" bg="var(--mantine-color-gray-0)">
                <Text size="sm" style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>{e.body || '(empty body)'}</Text>
              </Paper>
            </Card>

            <Card withBorder>
              <Text fw={600} mb="xs">Attachments</Text>
              {d.attachments.length === 0 ? <Text size="sm" c="dimmed">No attachments.</Text> : (
                <Stack gap={6}>
                  {d.attachments.map((a) => (
                    <Group key={a.name} justify="space-between" wrap="nowrap">
                      <Text size="sm" c={a.available ? undefined : 'red'} truncate>
                        📎 {a.name} <Text span size="xs" c="dimmed">{a.available ? size(a.sizeBytes) : 'File missing'}</Text>
                      </Text>
                      {a.available && (
                        <Group gap={4} wrap="nowrap">
                          <Button size="xs" variant={shown === a.name ? 'filled' : 'default'}
                            onClick={() => setPreview(shown === a.name ? null : a.name)}>{shown === a.name ? 'Hide Preview' : 'Preview'}</Button>
                          <Button size="xs" variant="default" component="a" href={url(e.id!, a.name)} target="_blank" rel="noopener">Open</Button>
                        </Group>
                      )}
                    </Group>
                  ))}
                </Stack>
              )}
            </Card>

            <Card withBorder>
              <Text fw={600} mb="xs">Processing Result</Text>
              {!d.processed ? (
                <Text size="sm" c="dimmed">Not processed yet. Process it to sort the email and turn any invoice attachments into draft bills.</Text>
              ) : (
                <Stack gap="xs">
                  <Text size="sm"><Text span c="dimmed">Decided by </Text>{e.classifiedBy === 'LLM' ? 'the LLM (no keyword rule matched)' : 'keyword rules'}
                    <Text span c="dimmed"> · {dateTime(e.processedAt)}</Text></Text>
                  {e.reason && <Text size="sm">{e.reason}</Text>}
                  {e.needsAttention && <Badge color="orange" variant="light" w="fit-content">Needs Attention</Badge>}
                  {d.linkedInvoices.length > 0 && (
                    <div className="ap-table-scroll"><Table striped>
                      <Table.Thead><Table.Tr><Table.Th>Bill</Table.Th><Table.Th>Vendor Invoice #</Table.Th><Table.Th>Total</Table.Th><Table.Th>Status</Table.Th></Table.Tr></Table.Thead>
                      <Table.Tbody>
                        {d.linkedInvoices.map((i) => (
                          <Table.Tr key={i.id}>
                            <Table.Td><Anchor component={Link} to={`/invoices/${i.id}`} size="sm">{i.id}</Anchor></Table.Td>
                            <Table.Td><Text size="sm">{i.vendorInvoiceNumber ?? '—'}</Text></Table.Td>
                            <Table.Td><Text size="sm">{money(i.total, i.currency ?? 'CAD')}</Text></Table.Td>
                            <Table.Td><StatusBadge status={i.status} /></Table.Td>
                          </Table.Tr>
                        ))}
                      </Table.Tbody>
                    </Table></div>
                  )}
                </Stack>
              )}
            </Card>

            {d.history.length > 0 && (
              <Card withBorder>
                <Text fw={600} mb="xs">History</Text>
                <Timeline bulletSize={14} lineWidth={1}>
                  {d.history.map((h, i) => (
                    <Timeline.Item key={i} title={<Group gap="xs"><Badge size="sm" variant="light">{HISTORY_LABEL[h.action] ?? statusLabel(h.action)}</Badge><Text size="xs" c="dimmed">{dateTime(h.ts)} · {n.person(h.actor)}</Text></Group>}>
                      {h.detail && <Text size="xs" style={{ whiteSpace: 'pre-wrap' }}>{n.text(h.detail)}</Text>}
                    </Timeline.Item>
                  ))}
                </Timeline>
              </Card>
            )}
          </Stack>
        </Grid.Col>

        {shown && shownInfo && (
          <Grid.Col span={{ base: 12, md: 6 }}>
            <Card withBorder p={0} h={720}>
              <Group justify="space-between" px="sm" py={6} style={{ borderBottom: '1px solid var(--mantine-color-gray-3)' }}>
                <Text size="sm" fw={500} truncate>{shown}</Text>
                <CloseButton aria-label="Close preview" onClick={() => setPreview(null)} />
              </Group>
              <div style={{ height: 'calc(100% - 41px)' }}>
                {shownInfo.contentType?.startsWith('image/')
                  ? <ScrollArea h="100%"><img src={url(e.id!, shown)} alt={shown} style={{ width: '100%' }} /></ScrollArea>
                  : shownInfo.contentType === 'application/pdf'
                    ? <iframe key={shown} title={shown} src={`${url(e.id!, shown)}#view=FitH`} style={{ width: '100%', height: '100%', border: 0 }} />
                    : <Empty>This file type can't be previewed. Use Open to download it.</Empty>}
              </div>
            </Card>
          </Grid.Col>
        )}
      </Grid>
    </Stack>
  )
}

const HISTORY_LABEL: Record<string, string> = {
  EMAIL_CLASSIFIED: 'Processed',
  EMAIL_REPLY_SENT: 'Reply Sent',
  EMAIL_REPLY_DRAFTED: 'Reply Draft Saved',
}

/**
 * D-102: reply to the sender. Outbound email is out of scope for this build, so Send does not leave the system: the
 * reply (or draft) is recorded against the email and shows in its History.
 */
interface Draft { to: string; subject: string; body: string; ts: string; actor: string }

/** The saved draft to reopen: the last reply event, if it is a draft (a later Send means there is none). */
function latestDraft(history: HistoryItem[]): Draft | null {
  const last = [...history].reverse().find((h) => h.action === 'EMAIL_REPLY_DRAFTED' || h.action === 'EMAIL_REPLY_SENT')
  if (!last || last.action !== 'EMAIL_REPLY_DRAFTED') return null
  let meta: { to?: string; subject?: string } = {}
  try { meta = last.after ? JSON.parse(last.after) : {} } catch { /* the body alone is still worth restoring */ }
  const clean = (v?: string) => (v && v !== 'null' ? v : '')
  return { to: clean(meta.to), subject: clean(meta.subject), body: last.detail ?? '', ts: last.ts, actor: last.actor }
}

function ReplyModal({ email, draft, onClose, onSaved }: { email: EmailMessage; draft: Draft | null; onClose: () => void; onSaved: () => void }) {
  const { me } = useAuth()
  const n = useNames()
  // Signature: the person replying, their title, and the company.
  const signature = ['Thank you,', me?.fullName, me?.title, 'Harborline Logistics'].filter(Boolean).join('\n')
  // What the pop-up opened with (the template, or the reopened draft): Save Draft stays off until something changes.
  const [initial] = useState(() => ({
    to: draft?.to || email.fromAddress || '',
    subject: draft?.subject || (email.subject?.startsWith('Re:') ? email.subject : `Re: ${email.subject ?? ''}`),
    body: draft?.body || `Hello ${email.fromName ?? ''},\n\n\n\n${signature}`,
  }))
  const [to, setTo] = useState(initial.to)
  const [subject, setSubject] = useState(initial.subject)
  const [body, setBody] = useState(initial.body)
  const changed = to !== initial.to || subject !== initial.subject || body !== initial.body
  const [busy, setBusy] = useState<'send' | 'draft' | null>(null)
  const save = async (draft: boolean) => {
    setBusy(draft ? 'draft' : 'send')
    try {
      await api.post(`/inbox/${email.id}/reply`, { to, subject, body, draft })
      notifyOk(draft ? 'Draft saved' : `Reply sent to ${to}`)
      onSaved()
      onClose()
    } catch (e) {
      notifyError(e)
    } finally {
      setBusy(null)
    }
  }
  return (
    <Modal opened onClose={onClose} title="Reply" size="lg">
      <Stack>
        <TextInput label="To" value={to} onChange={(e) => setTo(e.currentTarget.value)} />
        <TextInput label="Subject" value={subject} onChange={(e) => setSubject(e.currentTarget.value)} />
        <Textarea label="Message" value={body} onChange={(e) => setBody(e.currentTarget.value)} autosize minRows={8} />
        {draft && <Text size="xs" c="blue">Opened the draft saved by {n.person(draft.actor)} on {dateTime(draft.ts)}</Text>}
        <Text size="xs" c="dimmed">Outgoing email is simulated in this demo: the reply is saved to this email's history.</Text>
        <Group justify="flex-end">
          <Button variant="subtle" color="gray" onClick={onClose}>Cancel</Button>
          <Button variant="default" loading={busy === 'draft'} disabled={!!busy || !changed} onClick={() => save(true)}>Save Draft</Button>
          <Button loading={busy === 'send'} disabled={!!busy || !to.trim() || !body.trim()} onClick={() => save(false)}>Send</Button>
        </Group>
      </Stack>
    </Modal>
  )
}
