import { Alert, Badge, Button, Card, Grid, Group, JsonInput, MultiSelect, NumberInput, Select, Stack, Switch, Table, Tabs, Text, Title } from '@mantine/core'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { api } from '../api/client'
import type { ApprovalPolicy, InvoiceSummary, RouteResult } from '../api/types'
import { useAuth } from '../auth/AuthContext'
import { Loading } from '../components/bits'
import { ValidationRules } from '../components/ValidationRules'
import { notifyError, notifyOk } from '../components/notify'
import { money } from '../lib/format'
import { useNames } from '../lib/names'

const ROLES = ['dept_manager', 'finance_manager', 'controller_admin', 'cfo']
const KINDS = ['approval', 'match', 'payment_release', 'assignment']

/** Plain-English rendering of the condition tree so non-developers can read the rules. */
function describe(json?: string | null): string {
  try {
    return render(JSON.parse(json ?? '{}'))
  } catch {
    return '(invalid JSON)'
  }
}
function render(n: Record<string, unknown>): string {
  if ('always' in n) return 'always'
  if ('all' in n) return (n.all as Record<string, unknown>[]).map(render).join(' AND ')
  if ('any' in n) return '(' + (n.any as Record<string, unknown>[]).map(render).join(' OR ') + ')'
  if ('not' in n) return 'NOT ' + render(n.not as Record<string, unknown>)
  const ops: Record<string, string> = { eq: '=', ne: '≠', gt: '>', gte: '≥', lt: '<', lte: '≤', in: 'in' }
  return `${n.field} ${ops[String(n.op)] ?? n.op} ${typeof n.value === 'number' ? n.value.toLocaleString() : JSON.stringify(n.value)}`
}

/** Policies: who approves and releases (routing rules as data), and which checks run on every bill. */
export default function PoliciesPage() {
  return (
    <Stack>
      <Title order={3}>Policies</Title>
      <Tabs defaultValue="approval" keepMounted={false}>
        <Tabs.List>
          <Tabs.Tab value="approval">Approval and Payment Rules</Tabs.Tab>
          <Tabs.Tab value="validation">Validation Rules</Tabs.Tab>
        </Tabs.List>
        <Tabs.Panel value="approval" pt="md"><ApprovalRules /></Tabs.Panel>
        <Tabs.Panel value="validation" pt="md"><ValidationRules /></Tabs.Panel>
      </Tabs>
    </Stack>
  )
}

function ApprovalRules() {
  const { is } = useAuth()
  const n = useNames()
  const qc = useQueryClient()
  const q = useQuery({ queryKey: ['policies'], queryFn: () => api.get<ApprovalPolicy[]>('/policies') })
  const invoices = useQuery({ queryKey: ['invoices', 'policy-preview'], queryFn: () => api.get<InvoiceSummary[]>('/invoices'), enabled: is('ADM', 'FM') })
  const [rows, setRows] = useState<ApprovalPolicy[]>([])
  const [dirty, setDirty] = useState(false)
  const [previewId, setPreviewId] = useState<string | null>(null)
  const [preview, setPreview] = useState<{ current: RouteResult; withEdits: RouteResult } | null>(null)
  useEffect(() => { if (q.data) { setRows(q.data); setDirty(false) } }, [q.data])
  if (q.isLoading) return <Loading />
  const edit = is('ADM')
  const update = (i: number, patch: Partial<ApprovalPolicy>) => { setRows((r) => r.map((x, j) => (j === i ? { ...x, ...patch } : x))); setDirty(true) }

  const save = async () => {
    try {
      await api.put('/policies', rows)
      notifyOk('Rules saved; new submissions route with them immediately')
      qc.invalidateQueries({ queryKey: ['policies'] })
    } catch (e) {
      notifyError(e)
    }
  }
  const runPreview = async () => {
    if (!previewId) return
    try {
      setPreview(await api.post('/policies/preview', { invoiceId: previewId, policies: rows }))
    } catch (e) {
      notifyError(e)
    }
  }

  return (
    <Stack>
      <Group justify="space-between">
        <div>
          <Text size="sm" c="dimmed">The routing engine reads only this table. Amounts are the invoice total in CAD, tax included.</Text>
        </div>
        {edit && <Group>
          <Button variant="default" disabled={!dirty} onClick={() => { setRows(q.data ?? []); setDirty(false) }}>Discard</Button>
          <Button disabled={!dirty} onClick={save}>Save Rules</Button>
        </Group>}
      </Group>
      {!edit && <Alert color="gray">Read-only. Only the Controller / Admin can change rules.</Alert>}
      <Table striped>
        <Table.Thead><Table.Tr><Table.Th>Rule</Table.Th><Table.Th>Kind</Table.Th><Table.Th>Order</Table.Th><Table.Th>When</Table.Th><Table.Th>Adds / allows</Table.Th><Table.Th>On</Table.Th></Table.Tr></Table.Thead>
        <Table.Tbody>
          {rows.map((p, i) => (
            <Table.Tr key={p.id ?? i}>
              <Table.Td><Text fw={600}>{p.id}</Text><Text size="xs" c="dimmed" maw={220}>{p.sourceText}</Text></Table.Td>
              <Table.Td>{edit ? <Select size="xs" w={140} data={KINDS} value={p.kind} onChange={(v) => update(i, { kind: v ?? 'approval' })} /> : <Badge variant="light">{p.kind}</Badge>}</Table.Td>
              <Table.Td>{edit ? <NumberInput size="xs" w={70} value={p.priority} onChange={(v) => update(i, { priority: Number(v) })} /> : p.priority}</Table.Td>
              <Table.Td miw={280}>
                <Text size="sm">{describe(p.conditionJson)}</Text>
                {edit && <JsonInput size="xs" mt={4} autosize minRows={1} maxRows={6} validationError="Invalid JSON" value={p.conditionJson ?? ''}
                  onChange={(v) => update(i, { conditionJson: v })} />}
                {p.notes && <Text size="xs" c="dimmed">{p.notes}</Text>}
              </Table.Td>
              <Table.Td>{edit ? <MultiSelect size="xs" w={240} data={ROLES.map((r) => ({ value: r, label: n.role(r) }))} value={(p.addsRoles ?? '').split(',').filter(Boolean)} onChange={(v) => update(i, { addsRoles: v.join(',') })} />
                : (p.addsRoles ?? '').split(',').filter(Boolean).map((r) => <Badge key={r} size="sm" variant="outline" mr={4}>{n.role(r)}</Badge>)}</Table.Td>
              <Table.Td><Switch checked={p.enabled} disabled={!edit} onChange={(e) => update(i, { enabled: e.currentTarget.checked })} /></Table.Td>
            </Table.Tr>
          ))}
        </Table.Tbody>
      </Table>
      {edit && <Button variant="subtle" w="fit-content" onClick={() => { setRows((r) => [...r, { id: `P${String(r.length + 1).padStart(2, '0')}x`, kind: 'approval', priority: 200, conditionJson: '{"field":"amountCad","op":"gt","value":100000}', addsRoles: 'finance_manager', enabled: true, notes: 'New rule' } as ApprovalPolicy]); setDirty(true) }}>+ Add rule</Button>}

      {is('ADM', 'FM') && (
        <Card withBorder>
          <Title order={5}>Preview Routing</Title>
          <Text size="sm" c="dimmed" mb="sm">Who would approve an invoice under the rules above (including unsaved edits)?</Text>
          <Group align="flex-end">
            <Select searchable w={420} label="Invoice" value={previewId} onChange={setPreviewId}
              data={(invoices.data ?? []).map((i) => ({ value: i.id!, label: `${i.id} · ${i.vendorName ?? '?'} · ${money(i.totalCad)} · ${i.departmentId ? n.dept(i.departmentId) : 'no department'}` }))} />
            <Button onClick={runPreview} disabled={!previewId}>Preview</Button>
          </Group>
          {preview && (
            <Grid mt="md">
              {(['current', 'withEdits'] as const).map((k) => (
                <Grid.Col span={6} key={k}>
                  <Text fw={600}>{k === 'current' ? 'Saved rules' : 'With your edits'}</Text>
                  <Text size="xs" c="dimmed">Routed at {money(preview[k].amountCad)} · matched {preview[k].matchedPolicies.join(', ') || 'none'}</Text>
                  {preview[k].steps.map((s) => (
                    <Text key={s.seq} size="sm">{s.seq}. {n.role(s.role)} → <b>{n.person(s.assigneeId)}</b>{s.onBehalfOfId ? ` (on behalf of ${n.person(s.onBehalfOfId)})` : ''}
                      {s.notes.map((note) => <Text key={note} size="xs" c="dimmed">↳ {n.text(note)}</Text>)}</Text>
                  ))}
                  {preview[k].problems.map((p) => <Text key={p} size="xs" c="red">{n.text(p)}</Text>)}
                </Grid.Col>
              ))}
            </Grid>
          )}
        </Card>
      )}
    </Stack>
  )
}
