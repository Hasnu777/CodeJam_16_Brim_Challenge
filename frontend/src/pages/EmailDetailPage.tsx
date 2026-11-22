import { Anchor, Badge, Button, Card, CloseButton, Grid, Group, Paper, ScrollArea, Stack, Table, Text, Timeline, Title } from '@mantine/core'
import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { api } from '../api/client'
import type { EmailMessage } from '../api/types'
import { Empty, ErrorBox, Loading, StatusBadge } from '../components/bits'
import { dateTime, money } from '../lib/format'
import { KIND } from './InboxPage'

interface AttachmentInfo { name: string; available: boolean; contentType: string | null; sizeBytes: number | null }
interface LinkedInvoice { id: string; status: string; vendorId: string | null; vendorInvoiceNumber: string | null; total: string | null; currency: string | null }
interface HistoryItem { ts: string; actor: string; action: string; detail: string | null }
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
      <Anchor component={Link} to="/inbox" size="sm">← AP inbox</Anchor>
      <Group justify="space-between" align="flex-start">
        <div>
          <Title order={3}>{e.subject || '(no subject)'}</Title>
          <Text size="sm" c="dimmed">{e.id}</Text>
        </div>
        {d.processed
          ? <Group gap={4}><Badge color={kind?.color} variant="light" size="lg">{kind?.label ?? e.classification}</Badge>{e.intakeCode && <Badge variant="outline">{e.intakeCode}</Badge>}</Group>
          : <Badge color="gray" variant="outline" size="lg">Unprocessed</Badge>}
      </Group>

      <Grid>
        <Grid.Col span={{ base: 12, md: shown ? 6 : 12 }}>
          <Stack>
            <Card withBorder>
              <Text fw={600} mb="xs">Message as received</Text>
              <Table withRowBorders={false} verticalSpacing={2}>
                <Table.Tbody>
                  <Table.Tr><Table.Td w={90}><Text size="sm" c="dimmed">From</Text></Table.Td>
                    <Table.Td><Text size="sm">{e.fromName} {e.fromAddress && <Text span c="dimmed">&lt;{e.fromAddress}&gt;</Text>}</Text></Table.Td></Table.Tr>
                  <Table.Tr><Table.Td><Text size="sm" c="dimmed">To</Text></Table.Td><Table.Td><Text size="sm">{e.toAddress ?? '—'}</Text></Table.Td></Table.Tr>
                  <Table.Tr><Table.Td><Text size="sm" c="dimmed">Received</Text></Table.Td><Table.Td><Text size="sm">{dateTime(e.receivedAt)}</Text></Table.Td></Table.Tr>
                  <Table.Tr><Table.Td><Text size="sm" c="dimmed">Source</Text></Table.Td>
                    <Table.Td><Text size="sm">{e.messageId ? 'AP mailbox (IMAP)' : 'Dataset inbox (emails.json)'}</Text></Table.Td></Table.Tr>
                </Table.Tbody>
              </Table>
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
                        📎 {a.name} <Text span size="xs" c="dimmed">{a.available ? size(a.sizeBytes) : 'file missing'}</Text>
                      </Text>
                      {a.available && (
                        <Group gap={4} wrap="nowrap">
                          <Button size="xs" variant={shown === a.name ? 'filled' : 'default'}
                            onClick={() => setPreview(shown === a.name ? null : a.name)}>{shown === a.name ? 'Hide preview' : 'Preview'}</Button>
                          <Button size="xs" variant="default" component="a" href={url(e.id!, a.name)} target="_blank" rel="noopener">Open</Button>
                        </Group>
                      )}
                    </Group>
                  ))}
                </Stack>
              )}
            </Card>

            <Card withBorder>
              <Text fw={600} mb="xs">Processing result</Text>
              {!d.processed ? (
                <Text size="sm" c="dimmed">Not processed yet. "Process inbox" will classify this email and turn invoice attachments into draft bills.</Text>
              ) : (
                <Stack gap="xs">
                  <Text size="sm"><Text span c="dimmed">Decided by </Text>{e.classifiedBy === 'LLM' ? 'the LLM (no keyword rule matched)' : 'keyword rules'}
                    <Text span c="dimmed"> · {dateTime(e.processedAt)}</Text></Text>
                  {e.reason && <Text size="sm">{e.reason}</Text>}
                  {e.needsAttention && <Badge color="orange" variant="light" w="fit-content">Needs attention</Badge>}
                  {d.linkedInvoices.length > 0 && (
                    <Table striped>
                      <Table.Thead><Table.Tr><Table.Th>Bill</Table.Th><Table.Th>Vendor invoice #</Table.Th><Table.Th>Total</Table.Th><Table.Th>Status</Table.Th></Table.Tr></Table.Thead>
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
                    </Table>
                  )}
                </Stack>
              )}
            </Card>

            {d.history.length > 0 && (
              <Card withBorder>
                <Text fw={600} mb="xs">History</Text>
                <Timeline bulletSize={14} lineWidth={1}>
                  {d.history.map((h, i) => (
                    <Timeline.Item key={i} title={<Group gap="xs"><Badge size="sm" variant="light">{h.action}</Badge><Text size="xs" c="dimmed">{dateTime(h.ts)} · {h.actor}</Text></Group>}>
                      {h.detail && <Text size="xs">{h.detail}</Text>}
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
