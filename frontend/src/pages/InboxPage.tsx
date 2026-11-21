import { Anchor, Badge, Button, Card, FileButton, Group, Progress, SegmentedControl, Stack, Table, Text, Title, Tooltip } from '@mantine/core'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { api } from '../api/client'
import type { EmailMessage } from '../api/types'
import { useAuth } from '../auth/AuthContext'
import { Empty, ErrorBox, Loading } from '../components/bits'
import { notifyError, notifyOk } from '../components/notify'
import { dateTime } from '../lib/format'

interface InboxStatus {
  unprocessed: number; llmEnabled: boolean; llmModel: string | null; running: boolean
  total?: number; done?: number; invoicesCreated?: number; needsAttention?: number; errors?: string[]
}

const KIND: Record<string, { label: string; color: string }> = {
  INVOICE: { label: 'Invoice', color: 'blue' },
  NO_ATTACHMENT: { label: 'No attachment', color: 'red' },
  PAYMENT_INQUIRY: { label: 'Payment inquiry', color: 'orange' },
  STATEMENT: { label: 'Statement', color: 'gray' },
  NOT_INVOICE: { label: 'Not an invoice', color: 'gray' },
}

export default function InboxPage() {
  const { is } = useAuth()
  const qc = useQueryClient()
  const nav = useNavigate()
  const [filter, setFilter] = useState('all')
  const emails = useQuery({ queryKey: ['inbox'], queryFn: () => api.get<EmailMessage[]>('/inbox') })
  const status = useQuery({
    queryKey: ['inbox-status'], queryFn: () => api.get<InboxStatus>('/inbox/status'),
    refetchInterval: (q) => (q.state.data?.running ? 1500 : false),
  })
  const [uploading, setUploading] = useState(false)
  const s = status.data
  const canAct = is('CLK', 'ADM')

  const process = async () => {
    try {
      await api.post('/inbox/process')
      await qc.invalidateQueries({ queryKey: ['inbox-status'] })
      const poll = setInterval(async () => {
        const st = await api.get<InboxStatus>('/inbox/status')
        qc.setQueryData(['inbox-status'], st)
        if (!st.running) {
          clearInterval(poll)
          qc.invalidateQueries()
          notifyOk(`${st.invoicesCreated} draft bills created, ${st.needsAttention} emails need attention`)
        }
      }, 1500)
    } catch (e) {
      notifyError(e)
    }
  }

  if (emails.isLoading) return <Loading />
  const rows = (emails.data ?? []).filter((e) => filter === 'all' || (filter === 'attention' ? e.processedAt && e.classification !== 'INVOICE' : !e.processedAt))
  return (
    <Stack>
      <Group justify="space-between">
        <div>
          <Title order={3}>AP inbox</Title>
          <Text size="sm" c="dimmed">
            ap@harborline-logistics.example · keyword rules decide first, the LLM only breaks ties ·{' '}
            {s?.llmEnabled ? `reading with ${s.llmModel}` : 'LLM key not set: reading with pdftotext/OCR + rules'}
          </Text>
        </div>
        {canAct && (
          <Group>
            <FileButton accept="application/pdf,image/png,image/jpeg" onChange={async (f) => {
              if (!f) return
              setUploading(true)
              try {
                const fd = new FormData()
                fd.append('file', f)
                const inv = await api.upload<{ id: string }>('/invoices/upload', fd)
                qc.invalidateQueries()
                nav(`/invoices/${inv.id}`)
              } catch (e) {
                notifyError(e)
              } finally {
                setUploading(false)
              }
            }}>
              {(props) => <Button variant="default" {...props} loading={uploading}>Upload a file</Button>}
            </FileButton>
            <Button onClick={process} loading={s?.running} disabled={!s?.unprocessed}>Process inbox ({s?.unprocessed ?? 0})</Button>
          </Group>
        )}
      </Group>
      {s?.running && (
        <Card withBorder>
          <Text size="sm" mb={4}>Reading {s.done}/{s.total} emails… {s.invoicesCreated} bills so far</Text>
          <Progress value={s.total ? (100 * (s.done ?? 0)) / s.total : 0} animated />
        </Card>
      )}
      {!!s?.errors?.length && <ErrorBox error={s.errors.join('; ')} />}
      <SegmentedControl w="fit-content" value={filter} onChange={setFilter}
        data={[{ value: 'all', label: 'All' }, { value: 'attention', label: 'Needs attention' }, { value: 'new', label: 'Unprocessed' }]} />
      {rows.length === 0 ? (
        <Empty>{filter === 'attention' ? 'Nothing needs attention.' : 'No emails. Process the inbox to begin.'}</Empty>
      ) : (
        <Table striped highlightOnHover>
          <Table.Thead>
            <Table.Tr><Table.Th>Received</Table.Th><Table.Th>From</Table.Th><Table.Th>Subject</Table.Th><Table.Th>Classification</Table.Th><Table.Th>Result</Table.Th></Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {rows.map((e) => (
              <Table.Tr key={e.id}>
                <Table.Td><Text size="xs">{dateTime(e.receivedAt)}</Text></Table.Td>
                <Table.Td><Text size="sm">{e.fromName}</Text><Text size="xs" c="dimmed">{e.fromAddress}</Text></Table.Td>
                <Table.Td><Text size="sm">{e.subject}</Text>
                  {e.attachments && <Text size="xs" c="dimmed">📎 {e.attachments.split(',').join(', ')}</Text>}</Table.Td>
                <Table.Td>
                  {e.classification ? (
                    <Tooltip label={`Decided by ${e.classifiedBy === 'LLM' ? 'the LLM (tiebreaker)' : 'keyword rules'}`}>
                      <Group gap={4} wrap="nowrap">
                        <Badge color={KIND[e.classification]?.color} variant="light">{KIND[e.classification]?.label ?? e.classification}</Badge>
                        {e.intakeCode && <Badge variant="outline" size="xs">{e.intakeCode}</Badge>}
                      </Group>
                    </Tooltip>
                  ) : <Badge color="gray" variant="outline">unprocessed</Badge>}
                </Table.Td>
                <Table.Td maw={380}>
                  {e.classification && e.classification !== 'INVOICE' && <Text size="xs">{e.reason}</Text>}
                  <Group gap={6}>
                    {(e.linkedInvoiceIds ?? '').split(',').filter(Boolean).map((id) => (
                      <Anchor key={id} component={Link} to={`/invoices/${id}`} size="sm">{id}</Anchor>
                    ))}
                  </Group>
                </Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      )}
    </Stack>
  )
}
