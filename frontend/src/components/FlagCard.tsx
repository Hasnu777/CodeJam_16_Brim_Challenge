import { Anchor, Badge, Button, Card, Code, Collapse, Group, Stack, Text, Textarea } from '@mantine/core'
import { useDisclosure } from '@mantine/hooks'
import { useState } from 'react'
import { Link } from 'react-router-dom'
import type { Flag } from '../api/types'
import { dateTime } from '../lib/format'
import { SeverityBadge } from './bits'

const BORDER: Record<string, string> = { BLOCK: 'var(--mantine-color-red-6)', WARN: 'var(--mantine-color-yellow-6)', INFO: 'var(--mantine-color-gray-5)' }

/** One finding: title, one-sentence explanation with numbers, evidence, links to matched records, resolution. */
export function FlagCard({ flag, canResolve, onResolve, users }: {
  flag: Flag
  canResolve: boolean
  onResolve?: (resolution: string, note: string) => Promise<void>
  users?: Record<string, string>
}) {
  const [open, { toggle }] = useDisclosure(false)
  const [note, setNote] = useState('')
  const [busy, setBusy] = useState(false)
  const evidence: Record<string, unknown> = safe(flag.evidenceJson)
  const links = ['matchedInvoiceId', 'resemblesVendorId', 'creditsInvoiceId', 'invoiceIds', 'otherVendorIds']
    .flatMap((k) => (Array.isArray(evidence[k]) ? (evidence[k] as string[]) : evidence[k] ? [String(evidence[k])] : []))
  const resolved = flag.status !== 'OPEN'
  const block = flag.severity === 'BLOCK'
  const go = async (resolution: string) => {
    if (!onResolve) return
    setBusy(true)
    try {
      await onResolve(resolution, note)
      setNote('')
    } finally {
      setBusy(false)
    }
  }
  return (
    <Card withBorder padding="sm" radius="md" style={{ borderLeft: `4px solid ${BORDER[flag.severity!] ?? 'gray'}`, opacity: resolved ? 0.65 : 1 }}>
      <Group justify="space-between" wrap="nowrap" align="flex-start">
        <Stack gap={4} style={{ flex: 1 }}>
          <Group gap="xs">
            <SeverityBadge severity={flag.severity} />
            <Text fw={600} size="sm">{flag.ruleCode} · {flag.title}</Text>
            {resolved && <Badge color="green" variant="light" size="sm">{flag.resolution === 'AUTO_CLEARED' ? 'cleared' : flag.status?.toLowerCase()}</Badge>}
          </Group>
          <Text size="sm">{flag.message}</Text>
          {links.length > 0 && (
            <Group gap="xs">
              <Text size="xs" c="dimmed">Related:</Text>
              {links.map((id) => (
                <Anchor key={id} component={Link} size="xs" to={id.startsWith('V') ? `/vendors/${id}` : `/invoices/${id}`}>{id}</Anchor>
              ))}
            </Group>
          )}
          {resolved && flag.resolvedBy && (
            <Text size="xs" c="dimmed">
              {flag.resolution === 'AUTO_CLEARED' ? 'Cleared automatically' : `${flag.status} by ${users?.[flag.resolvedBy] ?? flag.resolvedBy}`}
              {' '}{dateTime(flag.resolvedAt)}{flag.note ? ` — "${flag.note}"` : ''}
            </Text>
          )}
        </Stack>
        <Anchor size="xs" onClick={toggle}>{open ? 'hide' : 'evidence'}</Anchor>
      </Group>
      <Collapse in={open}>
        <Code block mt="xs" style={{ fontSize: 11 }}>{JSON.stringify(evidence, null, 2)}</Code>
      </Collapse>
      {!resolved && canResolve && onResolve && (
        <Group mt="xs" gap="xs" align="flex-end">
          <Textarea size="xs" autosize minRows={1} placeholder={block ? 'Why is it safe to override? (required)' : 'Note (optional)'}
            value={note} onChange={(e) => setNote(e.currentTarget.value)} style={{ flex: 1 }} />
          {block ? (
            <Button size="xs" color="red" variant="light" loading={busy} disabled={!note.trim()} onClick={() => go('OVERRIDDEN')}>Override</Button>
          ) : (
            <>
              <Button size="xs" variant="light" loading={busy} onClick={() => go('ACKNOWLEDGED')}>Acknowledge</Button>
              <Button size="xs" variant="subtle" loading={busy} onClick={() => go('RESOLVED')}>Resolved</Button>
            </>
          )}
        </Group>
      )}
    </Card>
  )
}

function safe(s?: string | null): Record<string, unknown> {
  if (!s) return {}
  try {
    return JSON.parse(s)
  } catch {
    return {}
  }
}
