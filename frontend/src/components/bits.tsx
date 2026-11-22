import { Alert, Badge, Center, Group, Loader, Paper, Stack, Text, Tooltip, VisuallyHidden } from '@mantine/core'
import { IconAlertOctagon, IconAlertTriangle, IconInfoCircle } from '@tabler/icons-react'
import type { ReactNode } from 'react'
import { ApiError } from '../api/client'
import { money, SEVERITY_COLOR, SEVERITY_LABEL, STATUS_COLOR, statusLabel } from '../lib/format'
import { humanize } from '../lib/names'

export function StatusBadge({ status }: { status?: string | null }) {
  if (!status) return null
  return <Badge color={STATUS_COLOR[status] ?? 'gray'} variant="light">{statusLabel(status)}</Badge>
}

export function SeverityBadge({ severity }: { severity?: string | null }) {
  if (!severity) return null
  return <Badge color={SEVERITY_COLOR[severity] ?? 'gray'} variant={severity === 'BLOCK' ? 'filled' : 'light'}>{SEVERITY_LABEL[severity] ?? severity}</Badge>
}

export function FlagCounts({ block, warn, info }: { block?: number; warn?: number; info?: number }) {
  return (
    <Group gap={4} wrap="nowrap">
      {!!block && <Badge color="red" size="sm" leftSection={<IconAlertOctagon size={12} aria-hidden />}>{block}<VisuallyHidden> blocking</VisuallyHidden></Badge>}
      {!!warn && <Badge color="yellow" size="sm" variant="light" leftSection={<IconAlertTriangle size={12} aria-hidden />}>{warn}<VisuallyHidden> to review</VisuallyHidden></Badge>}
      {!!info && <Badge color="gray" size="sm" variant="light" leftSection={<IconInfoCircle size={12} aria-hidden />}>{info}<VisuallyHidden> info</VisuallyHidden></Badge>}
      {!block && !warn && !info && <Text size="xs" c="dimmed">None</Text>}
    </Group>
  )
}

/** Key for FlagCounts: the same icons and colours, with the words. */
export function FlagLegend() {
  return (
    <>
      <Badge color="red" size="sm" leftSection={<IconAlertOctagon size={12} aria-hidden />}>Blocks payment</Badge>
      <Badge color="yellow" size="sm" variant="light" leftSection={<IconAlertTriangle size={12} aria-hidden />}>Needs review</Badge>
      <Badge color="gray" size="sm" variant="light" leftSection={<IconInfoCircle size={12} aria-hidden />}>Info</Badge>
    </>
  )
}

/** CAD amount, plus the original currency when it differs ("USD 1,950.00 ≈ CAD 2,626.85"). */
export function MoneyCell({ cad, amount, currency }: { cad?: unknown; amount?: unknown; currency?: string | null }) {
  if (!currency || currency === 'CAD') return <Text size="sm" ff="monospace">{money(cad ?? amount)}</Text>
  return (
    <Stack gap={0}>
      <Text size="sm" ff="monospace">{money(cad)}</Text>
      <Text size="xs" c="dimmed" ff="monospace">{money(amount, currency)}</Text>
    </Stack>
  )
}

export function Loading() {
  return <Center p="xl"><Loader /></Center>
}

export function ErrorBox({ error }: { error: unknown }) {
  if (!error) return null
  const msg = error instanceof ApiError ? error.reason : String(error)
  return <Alert color="red" title={error instanceof ApiError && error.status === 403 ? 'Not allowed' : 'Something went wrong'}>{msg}</Alert>
}

export function Empty({ children }: { children: ReactNode }) {
  return (
    <Paper withBorder p="xl" radius="md">
      <Center><Text c="dimmed" ta="center">{children}</Text></Center>
    </Paper>
  )
}

/** Explain why an action is unavailable (finance roles see disabled buttons with a reason, per the PRD). */
export function Why({ reasons, children }: { reasons: string[]; children: ReactNode }) {
  if (!reasons.length) return <>{children}</>
  const text = reasons.map(humanize).join(' · ')
  // A disabled button cannot take focus, so the wrapper does: keyboard and touch users get the tooltip too,
  // and screen readers read the reason from the hidden text.
  return (
    <Tooltip multiline w={320} label={text} withArrow events={{ hover: true, focus: true, touch: true }}>
      <span tabIndex={0} style={{ display: 'inline-block' }}>{children}<VisuallyHidden>Unavailable: {text}</VisuallyHidden></span>
    </Tooltip>
  )
}

export function Hint({ children }: { children: ReactNode }) {
  return (
    <Group gap={6} wrap="nowrap" align="flex-start">
      <IconInfoCircle size={16} style={{ flexShrink: 0, marginTop: 2 }} />
      <Text size="sm" c="dimmed">{children}</Text>
    </Group>
  )
}
