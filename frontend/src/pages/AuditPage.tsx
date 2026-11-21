import { Anchor, Badge, Button, Code, Grid, Group, Modal, Stack, Table, Text, TextInput, Title, Tooltip } from '@mantine/core'
import { useQuery } from '@tanstack/react-query'
import { IconShieldCheck, IconShieldX } from '@tabler/icons-react'
import { useState } from 'react'
import { Link } from 'react-router-dom'
import { api, qs } from '../api/client'
import type { AuditEvent } from '../api/types'
import { Loading } from '../components/bits'
import { dateTime } from '../lib/format'

interface Verify { valid: boolean; events: number; headHash: string | null; breaks: { id: number; problems: string[] }[] }

export default function AuditPage() {
  const [f, setF] = useState({ entity: '', entityId: '', actor: '', action: '', from: '', to: '' })
  const [applied, setApplied] = useState(f)
  const [sel, setSel] = useState<AuditEvent | null>(null)
  const list = useQuery({ queryKey: ['audit', applied], queryFn: () => api.get<AuditEvent[]>('/audit' + qs({ ...applied, limit: 1000 })) })
  const verify = useQuery({ queryKey: ['audit-verify'], queryFn: () => api.get<Verify>('/audit/verify') })
  const v = verify.data
  const set = (k: keyof typeof f) => (e: { currentTarget: { value: string } }) => setF({ ...f, [k]: e.currentTarget.value })
  return (
    <Stack>
      <Group justify="space-between">
        <div>
          <Title order={3}>Audit log</Title>
          <Text size="sm" c="dimmed">Append-only (the database rejects UPDATE/DELETE) and hash-chained: each event stores the previous event's hash.</Text>
        </div>
        <Group>
          {v && (
            <Tooltip label={v.valid ? `Head ${v.headHash?.slice(0, 16)}…` : v.breaks.map((b) => `#${b.id}: ${b.problems.join(', ')}`).join(' · ')} multiline w={360}>
              <Badge size="lg" color={v.valid ? 'green' : 'red'} leftSection={v.valid ? <IconShieldCheck size={14} /> : <IconShieldX size={14} />}>
                {v.valid ? `Chain verified · ${v.events} events` : `Chain BROKEN at ${v.breaks.length} event(s)`}
              </Badge>
            </Tooltip>
          )}
          <Button variant="default" onClick={() => verify.refetch()}>Verify chain</Button>
          <Button component="a" href={'/api/audit/export.csv' + qs(applied)}>Export CSV</Button>
        </Group>
      </Group>
      <Group align="flex-end">
        <TextInput label="Entity" placeholder="invoice, payment, vendor…" value={f.entity} onChange={set('entity')} w={150} />
        <TextInput label="Entity id" placeholder="AP-00473" value={f.entityId} onChange={set('entityId')} w={130} />
        <TextInput label="Actor (or on behalf of)" placeholder="U003" value={f.actor} onChange={set('actor')} w={150} />
        <TextInput label="Action" placeholder="APPROVED" value={f.action} onChange={set('action')} w={170} />
        <TextInput label="From" type="date" value={f.from} onChange={set('from')} />
        <TextInput label="To" type="date" value={f.to} onChange={set('to')} />
        <Button onClick={() => setApplied(f)}>Filter</Button>
        <Button variant="subtle" onClick={() => { const e = { entity: '', entityId: '', actor: '', action: '', from: '', to: '' }; setF(e); setApplied(e) }}>Clear</Button>
      </Group>
      {list.isLoading ? <Loading /> : (
        <Table striped highlightOnHover>
          <Table.Thead><Table.Tr><Table.Th>#</Table.Th><Table.Th>When</Table.Th><Table.Th>Actor</Table.Th><Table.Th>Action</Table.Th><Table.Th>Entity</Table.Th><Table.Th>Detail</Table.Th></Table.Tr></Table.Thead>
          <Table.Tbody>
            {(list.data ?? []).map((e) => (
              <Table.Tr key={e.id} style={{ cursor: 'pointer' }} onClick={() => setSel(e)}>
                <Table.Td><Text size="xs" c="dimmed">{e.id}</Text></Table.Td>
                <Table.Td><Text size="xs">{dateTime(e.ts)}</Text></Table.Td>
                <Table.Td><Text size="sm">{e.actor}{e.onBehalfOf ? <Text span c="grape"> for {e.onBehalfOf}</Text> : null}</Text></Table.Td>
                <Table.Td><Badge size="sm" variant="light" color={e.action === 'ACCESS_DENIED' ? 'red' : undefined}>{e.action}</Badge></Table.Td>
                <Table.Td>{e.entity === 'invoice' ? <Anchor component={Link} to={`/invoices/${e.entityId}`} size="sm" onClick={(ev) => ev.stopPropagation()}>{e.entityId}</Anchor>
                  : <Text size="sm">{e.entity} {e.entityId}</Text>}</Table.Td>
                <Table.Td maw={460}><Text size="xs" lineClamp={2}>{e.detail}</Text></Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      )}
      <Modal opened={!!sel} onClose={() => setSel(null)} size="xl" title={sel ? `Event #${sel.id} · ${sel.action}` : ''}>
        {sel && (
          <Stack>
            <Text size="sm">{dateTime(sel.ts)} · {sel.actor}{sel.onBehalfOf ? ` on behalf of ${sel.onBehalfOf}` : ''} · {sel.entity} {sel.entityId}</Text>
            {sel.detail && <Text size="sm">{sel.detail}</Text>}
            <Grid>
              <Grid.Col span={6}><Text fw={600} size="sm">Before</Text><Code block>{pretty(sel.beforeJson)}</Code></Grid.Col>
              <Grid.Col span={6}><Text fw={600} size="sm">After</Text><Code block>{pretty(sel.afterJson)}</Code></Grid.Col>
            </Grid>
            <Text size="xs" c="dimmed" ff="monospace">prev {sel.prevHash}<br />hash {sel.hash}</Text>
          </Stack>
        )}
      </Modal>
    </Stack>
  )
}

function pretty(s?: string | null): string {
  if (!s) return '—'
  try {
    return JSON.stringify(JSON.parse(s), null, 2)
  } catch {
    return s
  }
}
