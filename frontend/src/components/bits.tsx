import { Alert, Badge, Center, Group, Loader, Paper, Stack, Text, Tooltip } from '@mantine/core'
import { IconInfoCircle } from '@tabler/icons-react'
import type { ReactNode } from 'react'
import { ApiError } from '../api/client'
import { money, SEVERITY_COLOR, STATUS_COLOR, statusLabel } from '../lib/format'

export function StatusBadge({ status }: { status?: string | null }) {
  if (!status) return null
  return <Badge color={STATUS_COLOR[status] ?? 'gray'} variant="light">{statusLabel(status)}</Badge>
}

export function SeverityBadge({ severity }: { severity?: string | null }) {
  if (!severity) return null
  return <Badge color={SEVERITY_COLOR[severity] ?? 'gray'} variant={severity === 'BLOCK' ? 'filled' : 'light'}>{severity}</Badge>
}

export function FlagCounts({ block, warn, info }: { block?: number; warn?: number; info?: number }) {
  return (
    <Group gap={4} wrap="nowrap">
      {!!block && <Badge color="red" size="sm">{block}</Badge>}
      {!!warn && <Badge color="yellow" size="sm" variant="light">{warn}</Badge>}
      {!!info && <Badge color="gray" size="sm" variant="light">{info}</Badge>}
      {!block && !warn && !info && <Text size="xs" c="dimmed">none</Text>}
    </Group>
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
  return (
    <Tooltip multiline w={320} label={reasons.join(' · ')} withArrow>
      <span style={{ display: 'inline-block' }}>{children}</span>
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
