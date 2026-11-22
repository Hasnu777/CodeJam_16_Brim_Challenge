import { Anchor, Badge, Button, Code, Grid, Group, Modal, Stack, Table, Text, TextInput, Title, Tooltip } from '@mantine/core'
import { useQuery } from '@tanstack/react-query'
import { IconShieldCheck, IconShieldX } from '@tabler/icons-react'
import { useState } from 'react'
import { Link } from 'react-router-dom'
import { api, qs } from '../api/client'
import type { AuditEvent } from '../api/types'
import { useAuth } from '../auth/AuthContext'
import { Loading } from '../components/bits'
import { notifyError, notifyOk } from '../components/notify'
import { dateTime, statusLabel } from '../lib/format'
import { useNames } from '../lib/names'

interface Verify { valid: boolean; events: number; headHash: string | null; breaks: { id: number; problems: string[] }[] }

export default function AuditPage() {
  const { is } = useAuth()
  const n = useNames()
  const entityLabel = (entity?: string | null, id?: string | null) =>
    entity === 'vendor' ? n.vendor(id) : entity === 'user' ? n.person(id) : `${statusLabel(entity ?? '')} ${id ?? ''}`.trim()
  const [f, setF] = useState({ entity: '', entityId: '', actor: '', action: '', from: '', to: '' })
  const [applied, setApplied] = useState(f)
  const [sel, setSel] = useState<AuditEvent | null>(null)
  const list = useQuery({ queryKey: ['audit', applied], queryFn: () => api.get<AuditEvent[]>('/audit' + qs({ ...applied, limit: 1000 })) })
  const verify = useQuery({ queryKey: ['audit-verify'], queryFn: () => api.get<Verify>('/audit/verify') })
  const v = verify.data
  const set = (k: keyof typeof f) => (e: { currentTarget: { value: string } }) => setF({ ...f, [k]: e.currentTarget.value })

  // Admins: a broken chain is re-sealed (recorded as AUDIT_RESEALED and kept on Fraud alerts). Everyone else: just re-check.
  const verifyChain = async () => {
    try {
      if (is('ADM') && v && !v.valid) {
        await api.post('/audit/reseal')
        notifyOk('Chain re-sealed; recorded as AUDIT_RESEALED and kept on Fraud alerts')
      }
    } catch (e) {
      notifyError(e)
    }
    verify.refetch()
    list.refetch()
  }

  return (
      <Stack>
        <Group justify="space-between">
          <div>
            <Title order={3}>Audit Log</Title>
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
            <Button variant="default" onClick={verifyChain}>Verify Chain</Button>
            <Button component="a" href={'/api/audit/export.csv' + qs(applied)}>Export CSV</Button>
          </Group>
        </Group>
        <Group align="flex-end">
          <TextInput label="Entity" placeholder="invoice, payment, vendor…" value={f.entity} onChange={set('entity')} w={150} />
          <TextInput label="Entity ID" placeholder="AP-00473" value={f.entityId} onChange={set('entityId')} w={130} />
          <TextInput label="Actor (or on behalf of)" placeholder="Person id, e.g. U003" value={f.actor} onChange={set('actor')} w={150} />
          <TextInput label="Action" placeholder="APPROVED" value={f.action} onChange={set('action')} w={170} />
          <TextInput label="From" type="date" value={f.from} onChange={set('from')} />
          <TextInput label="To" type="date" value={f.to} onChange={set('to')} />
          <Button onClick={() => setApplied(f)}>Filter</Button>
          <Button variant="subtle" onClick={() => { const e = { entity: '', entityId: '', actor: '', action: '', from: '', to: '' }; setF(e); setApplied(e) }}>Clear</Button>
        </Group>
        {list.isLoading ? <Loading /> : (
            <div className="ap-table-scroll"><Table striped highlightOnHover>
              <Table.Thead><Table.Tr><Table.Th>#</Table.Th><Table.Th>When</Table.Th><Table.Th>Actor</Table.Th><Table.Th>Action</Table.Th><Table.Th>Entity</Table.Th><Table.Th>Detail</Table.Th></Table.Tr></Table.Thead>
              <Table.Tbody>
                {(list.data ?? []).map((e) => (
                    <Table.Tr key={e.id} style={{ cursor: 'pointer' }} onClick={() => setSel(e)}>
                      <Table.Td><Text size="xs" c="dimmed">{e.id}</Text></Table.Td>
                      <Table.Td><Text size="xs">{dateTime(e.ts)}</Text></Table.Td>
                      <Table.Td><Text size="sm">{n.person(e.actor)}{e.onBehalfOf ? <Text span c="grape"> for {n.person(e.onBehalfOf)}</Text> : null}</Text></Table.Td>
                      <Table.Td><Badge size="sm" variant="light" color={e.action === 'ACCESS_DENIED' || e.action === 'AUDIT_RESEALED' ? 'red' : undefined}>{statusLabel(e.action ?? '')}</Badge></Table.Td>
                      <Table.Td>{e.entity === 'invoice' ? <Anchor component={Link} to={`/invoices/${e.entityId}`} size="sm" onClick={(ev) => ev.stopPropagation()}>{e.entityId}</Anchor>
                          : <Text size="sm">{entityLabel(e.entity, e.entityId)}</Text>}</Table.Td>
                      <Table.Td maw={460}><Text size="xs" lineClamp={2}>{n.text(e.detail)}</Text></Table.Td>
                    </Table.Tr>
                ))}
              </Table.Tbody>
            </Table></div>
        )}
        <Modal opened={!!sel} onClose={() => setSel(null)} size="xl" title={sel ? `Event #${sel.id} · ${statusLabel(sel.action ?? '')}` : ''}>
          {sel && (
              <Stack>
                <Text size="sm">{dateTime(sel.ts)} · {n.person(sel.actor)}{sel.onBehalfOf ? ` on behalf of ${n.person(sel.onBehalfOf)}` : ''} · {entityLabel(sel.entity, sel.entityId)}</Text>
                {sel.detail && <Text size="sm">{n.text(sel.detail)}</Text>}
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
