import { Badge, Button, Group, Paper, Stack, Text } from '@mantine/core'
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
      {list.map((s) => (
        <Paper key={s.poId} withBorder p="xs" radius="sm"
          style={s.bestMatch ? { borderColor: 'var(--mantine-color-teal-5)' } : undefined}>
          <Group justify="space-between" wrap="nowrap" align="flex-start">
            <Stack gap={2} style={{ flex: 1, minWidth: 0 }}>
              <Group gap="xs">
                <Text size="sm" fw={600}>{s.poId}</Text>
                {s.bestMatch && <Badge size="xs" color="teal" variant="light">best match</Badge>}
                <Text size="xs" c="dimmed">{s.description}</Text>
              </Group>
              <Text size="xs">
                Issued {date(s.issueDate)} · {s.departmentId ?? '—'} · total {money(s.total, s.currency ?? 'CAD')} ·
                billed {money(s.billed, s.currency ?? 'CAD')} · <b>remaining {money(s.remaining, s.currency ?? 'CAD')}</b>
              </Text>
              {s.reasons.map((r) => <Text key={r} size="xs" c="teal">+ {r}</Text>)}
              {s.warnings.map((w) => <Text key={w} size="xs" c="orange">! {w}</Text>)}
            </Stack>
            {canLink && (
              <Button size="xs" variant={s.bestMatch ? 'filled' : 'light'} loading={busy} onClick={() => onLink(s.poId)}>Link</Button>
            )}
          </Group>
        </Paper>
      ))}
    </Stack>
  )
}
