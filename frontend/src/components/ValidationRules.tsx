import { Alert, Badge, Button, Group, Modal, Select, Stack, Switch, Table, Text, Textarea, Tooltip } from '@mantine/core'
import { IconLock } from '@tabler/icons-react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { api } from '../api/client'
import { useAuth } from '../auth/AuthContext'
import { ErrorBox, Loading } from './bits'
import { notifyError, notifyOk } from './notify'
import { SEVERITY_LABEL } from '../lib/format'

type Severity = 'BLOCK' | 'WARN' | 'INFO'
interface RuleSetting {
  code: string; category: string; title: string; explanation: string
  severity: Severity; defaultSeverity: Severity; enabled: boolean; locked: boolean
}
type Edit = { enabled: boolean; severity: Severity }

const SEVERITIES: { value: Severity; label: string }[] = (['BLOCK', 'WARN', 'INFO'] as Severity[]).map((s) => ({ value: s, label: SEVERITY_LABEL[s] ?? s }))

/**
 * Policies > Validation Rules (D-108): which checks run on every bill and how serious each finding is. The checks are
 * tested code; switching one off or changing its severity is an Admin decision, recorded with a reason.
 */
export function ValidationRules() {
  const { is } = useAuth()
  const qc = useQueryClient()
  const q = useQuery({ queryKey: ['rules'], queryFn: () => api.get<RuleSetting[]>('/rules') })
  const [edits, setEdits] = useState<Record<string, Edit>>({})
  const [asking, setAsking] = useState(false)
  const [reason, setReason] = useState('')
  const [busy, setBusy] = useState(false)
  if (q.isLoading) return <Loading />
  if (q.error) return <ErrorBox error={q.error} />
  const canEdit = is('ADM')
  const rules = q.data ?? []
  const current = (r: RuleSetting): Edit => edits[r.code] ?? { enabled: r.enabled, severity: r.severity }
  const set = (r: RuleSetting, patch: Partial<Edit>) => {
    const next = { ...current(r), ...patch }
    setEdits((e) => {
      const copy = { ...e }
      if (next.enabled === r.enabled && next.severity === r.severity) delete copy[r.code]
      else copy[r.code] = next
      return copy
    })
  }
  const changed = Object.keys(edits)
  const save = async () => {
    setBusy(true)
    try {
      for (const code of changed) await api.put(`/rules/${code}`, { ...edits[code], reason })
      notifyOk(`${changed.length} rule${changed.length === 1 ? '' : 's'} updated; open bills re-checked`)
      setEdits({}); setReason(''); setAsking(false)
      qc.invalidateQueries()
    } catch (e) {
      notifyError(e)
    } finally {
      setBusy(false)
    }
  }
  const categories = [...new Set(rules.map((r) => r.category))]

  return (
    <Stack>
      <Group justify="space-between" align="flex-start">
        <Text size="sm" c="dimmed" maw={720}>
          The checks run on every bill before it can be submitted. Switch a check off, or change how serious its finding is:
          Blocking stops payment until it is resolved, Warning needs a reviewer to acknowledge it, Info is a note. Locked checks
          are core fraud and duplicate controls and always block.
        </Text>
        {canEdit && <Group>
          <Button variant="default" disabled={!changed.length} onClick={() => setEdits({})}>Discard</Button>
          <Button disabled={!changed.length} onClick={() => setAsking(true)}>Save Changes{changed.length ? ` (${changed.length})` : ''}</Button>
        </Group>}
      </Group>
      {!canEdit && <Alert color="gray">Read-only. Only the Controller / Admin can change rules.</Alert>}
      <Table striped>
        <Table.Thead><Table.Tr><Table.Th>Check</Table.Th><Table.Th w={170}>Severity</Table.Th><Table.Th w={80}>On</Table.Th></Table.Tr></Table.Thead>
        <Table.Tbody>
          {categories.map((cat) => [
            <Table.Tr key={cat}><Table.Td colSpan={3} bg="var(--mantine-color-gray-1)"><Text fw={700} size="sm">{cat}</Text></Table.Td></Table.Tr>,
            ...rules.filter((r) => r.category === cat).map((r) => {
              const c = current(r)
              return (
                <Table.Tr key={r.code} style={edits[r.code] ? { background: 'var(--mantine-color-yellow-0)' } : undefined}>
                  <Table.Td>
                    <Group gap={6}>
                      <Text size="sm" fw={600} c={c.enabled ? undefined : 'dimmed'}>{r.title}</Text>
                      {r.locked && <Tooltip label="Core fraud or duplicate control: always on, always blocking"><IconLock size={14} /></Tooltip>}
                      {c.severity !== r.defaultSeverity && <Badge size="xs" variant="light" color="gray">Default: {SEVERITY_LABEL[r.defaultSeverity]}</Badge>}
                    </Group>
                    <Text size="xs" c="dimmed">{r.explanation}</Text>
                  </Table.Td>
                  <Table.Td>
                    <Select size="xs" data={SEVERITIES} value={c.severity} allowDeselect={false} disabled={!canEdit || r.locked || !c.enabled}
                      onChange={(v) => v && set(r, { severity: v as Severity })} />
                  </Table.Td>
                  <Table.Td>
                    <Switch checked={c.enabled} disabled={!canEdit || r.locked} aria-label={`${r.title} on`}
                      onChange={(e) => set(r, { enabled: e.currentTarget.checked })} />
                  </Table.Td>
                </Table.Tr>
              )
            }),
          ])}
        </Table.Tbody>
      </Table>
      <Modal opened={asking} onClose={() => setAsking(false)} title="Save Rule Changes">
        <Stack>
          <Text size="sm">
            {changed.length} rule{changed.length === 1 ? '' : 's'} will change. Open bills are re-checked straight away. The change and
            your reason go in the audit log.
          </Text>
          <Textarea label="Reason" required autosize minRows={2} value={reason} onChange={(e) => setReason(e.currentTarget.value)} />
          <Group justify="flex-end">
            <Button variant="subtle" color="gray" onClick={() => setAsking(false)}>Cancel</Button>
            <Button loading={busy} disabled={!reason.trim()} onClick={save}>Save Changes</Button>
          </Group>
        </Stack>
      </Modal>
    </Stack>
  )
}
