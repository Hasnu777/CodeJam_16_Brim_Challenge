import { Accordion, Badge, Button, Group, Stack, Text } from '@mantine/core'
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
  // Lovable's contained accordion (chevron right, shaded panel when open, teal border on the best match), plus:
  // the warning badge stays visible while folded, and Link sits beside the header so it works without expanding.
  return (
    <Stack gap={6} mt={4}>
      <Text size="xs" fw={600}>{list.length === 1 ? 'Open PO for this vendor' : `${list.length} open POs for this vendor`}</Text>
      <Accordion variant="contained" radius="sm" chevronPosition="right">
        {list.map((s) => {
          const cur = s.currency ?? 'CAD'
          return (
            <Accordion.Item key={s.poId} value={s.poId} style={s.bestMatch ? { borderColor: 'var(--mantine-color-teal-5)' } : undefined}>
              <Group wrap="nowrap" gap={0} pr={canLink ? 'sm' : 0}>
                <Accordion.Control style={{ flex: 1, minWidth: 0 }}>
                  <Group gap="xs" wrap="nowrap">
                    <Text size="sm" fw={600}>{s.poId}</Text>
                    {s.bestMatch && <Badge size="xs" color="teal" variant="light">best match</Badge>}
                    {s.warnings.length > 0 && (
                      <Badge size="xs" color="orange" variant="light">{s.warnings.length} {s.warnings.length === 1 ? 'warning' : 'warnings'}</Badge>
                    )}
                    <Text size="xs" c="dimmed" truncate>remaining {money(s.remaining, cur)}</Text>
                  </Group>
                </Accordion.Control>
                {canLink && (
                  <Button size="xs" variant={s.bestMatch ? 'filled' : 'light'} loading={busy} onClick={() => onLink(s.poId)}>Link</Button>
                )}
              </Group>
              <Accordion.Panel>
                <Stack gap={4}>
                  {s.description && <Text size="xs" c="dimmed">{s.description}</Text>}
                  <Text size="xs">
                    Issued {date(s.issueDate)} · {s.departmentId ?? '—'} · total {money(s.total, cur)} · billed {money(s.billed, cur)} ·{' '}
                    <b>remaining {money(s.remaining, cur)}</b>
                  </Text>
                  {s.reasons.map((r) => <Text key={r} size="xs" c="teal">+ {r}</Text>)}
                  {s.warnings.map((w) => <Text key={w} size="xs" c="orange">! {w}</Text>)}
                </Stack>
              </Accordion.Panel>
            </Accordion.Item>
          )
        })}
      </Accordion>
    </Stack>
  )
}
