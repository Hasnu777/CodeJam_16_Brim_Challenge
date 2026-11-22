import { Badge, Button, Collapse, Group, Paper, Stack, Text, UnstyledButton } from '@mantine/core'
import { useDisclosure } from '@mantine/hooks'
import { IconChevronRight } from '@tabler/icons-react'
import { useQuery } from '@tanstack/react-query'
import { api } from '../api/client'
import { date, money } from '../lib/format'

export interface PoSuggestion {
  poId: string
  status: string
  issueDate: string | null
  departmentId: string | null
  requesterUserId: string | null
  description: string | null
  currency: string | null
  total: number | null
  billed: number
  remaining: number
  bestMatch: boolean
  reasons: string[]
  warnings: string[]
}

/**
 * Open POs of the invoice's vendor, best match first, each with why it ranks where it does. Shown under a P13
 * (PO required) or PO1 (bad PO) flag. Nothing links automatically: the clerk clicks Link.
 */
export function PoSuggestions({ invoiceId, canLink, onLink, busy }: {
  invoiceId: string
  canLink: boolean
  onLink: (poId: string) => void
  busy: boolean
}) {
  const q = useQuery({
    queryKey: ['po-suggestions', invoiceId],
    queryFn: () => api.get<PoSuggestion[]>(`/invoices/${invoiceId}/po-suggestions`),
  })
  if (q.isLoading) return <Text size="xs" c="dimmed">Looking for open POs for this vendor…</Text>
  if (q.error) return <Text size="xs" c="red">Could not load PO suggestions</Text>
  const list = q.data ?? []
  if (list.length === 0) {
    return (
      <Text size="xs" c="dimmed">
        This vendor has no open PO. Ask the vendor for their PO number, or get one raised; a Finance Manager can override with a reason.
      </Text>
    )
  }
  return (
    <Stack gap={6} mt={4}>
      <Text size="xs" fw={600}>{list.length === 1 ? 'Open PO for this vendor' : `${list.length} open POs for this vendor`}</Text>
      {list.map((s) => <SuggestionCard key={s.poId} s={s} canLink={canLink} busy={busy} onLink={onLink} />)}
    </Stack>
  )
}

/** One PO: always shows id, badge and remaining balance; the dates, totals and why-it-ranks-here lines fold away. */
function SuggestionCard({ s, canLink, busy, onLink }: { s: PoSuggestion; canLink: boolean; busy: boolean; onLink: (poId: string) => void }) {
  const [open, { toggle }] = useDisclosure(false)
  const cur = s.currency ?? 'CAD'
  return (
    <Paper withBorder p="xs" radius="sm" style={s.bestMatch ? { borderColor: 'var(--mantine-color-teal-5)' } : undefined}>
      <Group justify="space-between" wrap="nowrap" align="center">
        <UnstyledButton onClick={toggle} style={{ flex: 1, minWidth: 0 }} aria-expanded={open}>
          <Group gap="xs" wrap="nowrap">
            <IconChevronRight size={14} style={{ transform: open ? 'rotate(90deg)' : undefined, transition: 'transform 150ms', flexShrink: 0 }} />
            <Text size="sm" fw={600}>{s.poId}</Text>
            {s.bestMatch && <Badge size="xs" color="teal" variant="light">best match</Badge>}
            {s.warnings.length > 0 && (
              <Badge size="xs" color="orange" variant="light">{s.warnings.length} {s.warnings.length === 1 ? 'warning' : 'warnings'}</Badge>
            )}
            <Text size="xs" c="dimmed" truncate>remaining {money(s.remaining, cur)}</Text>
          </Group>
        </UnstyledButton>
        {canLink && (
          <Button size="xs" variant={s.bestMatch ? 'filled' : 'light'} loading={busy} onClick={() => onLink(s.poId)}>Link</Button>
        )}
      </Group>
      <Collapse in={open}>
        <Stack gap={2} mt={6} pl={22}>
          {s.description && <Text size="xs" c="dimmed">{s.description}</Text>}
          <Text size="xs">
            Issued {date(s.issueDate)} · {s.departmentId ?? '—'} · total {money(s.total, cur)} · billed {money(s.billed, cur)} ·{' '}
            <b>remaining {money(s.remaining, cur)}</b>
          </Text>
          {s.reasons.map((r) => <Text key={r} size="xs" c="teal">+ {r}</Text>)}
          {s.warnings.map((w) => <Text key={w} size="xs" c="orange">! {w}</Text>)}
        </Stack>
      </Collapse>
    </Paper>
  )
}
