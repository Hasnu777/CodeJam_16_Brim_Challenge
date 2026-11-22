import { Anchor, Badge, Button, Card, Collapse, Group, Stack, Table, Text, Textarea } from '@mantine/core'
import { useDisclosure } from '@mantine/hooks'
import { useState, type ReactNode } from 'react'
import { Link } from 'react-router-dom'
import type { Flag } from '../api/types'
import { dateTime, money, statusLabel } from '../lib/format'
import { SeverityBadge } from './bits'
import { useNames } from '../lib/names'

const BORDER: Record<string, string> = { BLOCK: 'var(--mantine-color-red-6)', WARN: 'var(--mantine-color-yellow-6)', INFO: 'var(--mantine-color-gray-5)' }

/** One finding: title, one-sentence explanation with numbers, evidence, links to matched records, resolution. */
export function FlagCard({ flag, canResolve, onResolve, users, children, showDetails = false }: {
  flag: Flag
  canResolve: boolean
  onResolve?: (resolution: string, note: string) => Promise<void>
  users?: Record<string, string>
  /** Extra help shown under the message, e.g. PO suggestions for P13. */
  children?: ReactNode
  /** The facts the rule recorded, as a labelled list. For reviewers (FM, approver, admin, auditor), not the clerk. */
  showDetails?: boolean
}) {
  const n = useNames()
  const [open, { toggle }] = useDisclosure(false)
  const [note, setNote] = useState('')
  const [busy, setBusy] = useState(false)
  const evidence: Record<string, unknown> = safe(flag.evidenceJson)
  const links = ['matchedInvoiceId', 'resemblesVendorId', 'creditsInvoiceId', 'invoiceIds', 'otherVendorIds']
    .flatMap((k) => (Array.isArray(evidence[k]) ? (evidence[k] as string[]) : evidence[k] ? [String(evidence[k])] : []))
  const rows = detailRows(evidence)
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
            <Text fw={600} size="sm">{flag.title}</Text>
            {resolved && <Badge color="green" variant="light" size="sm">{flag.resolution === 'AUTO_CLEARED' ? 'Cleared' : statusLabel(flag.status ?? '')}</Badge>}
          </Group>
          <Text size="sm">{n.text(flag.message)}</Text>
          {!resolved && children}
          {links.length > 0 && (
            <Group gap="xs">
              <Text size="xs" c="dimmed">Related:</Text>
              {links.map((id) => (
                <Anchor key={id} component={Link} size="xs" to={id.startsWith('V') ? `/vendors/${id}` : `/invoices/${id}`}>{id.startsWith('V') ? n.vendor(id) : id}</Anchor>
              ))}
            </Group>
          )}
          {resolved && flag.resolvedBy && (
            <Text size="xs" c="dimmed">
              {flag.resolution === 'AUTO_CLEARED' ? 'Cleared automatically' : `${flag.status?.toLowerCase()} by ${users?.[flag.resolvedBy] ?? n.person(flag.resolvedBy)}`}
              {' '}{dateTime(flag.resolvedAt)}{flag.note ? ` — "${flag.note}"` : ''}
            </Text>
          )}
        </Stack>
        {showDetails && rows.length > 0 && <Anchor size="xs" onClick={toggle}>{open ? 'Hide Details' : 'Details'}</Anchor>}
      </Group>
      {showDetails && rows.length > 0 && (
        <Collapse in={open}>
          <Table mt="xs" withRowBorders={false} verticalSpacing={2} fz="xs">
            <Table.Tbody>
              {rows.map(([label, value]) => (
                <Table.Tr key={label}>
                  <Table.Td c="dimmed" w="40%" style={{ verticalAlign: 'top' }}>{label}</Table.Td>
                  <Table.Td style={{ wordBreak: 'break-word' }}>{value}</Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </Collapse>
      )}
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

/** Evidence keys that hold amounts of money (shown as $1,234.56). */
const MONEY_KEYS = new Set([
  'alreadyBilled', 'amountCad', 'cap', 'charged', 'difference', 'expected', 'expectedTotal', 'freightBase', 'goodsBase',
  'gstCharged', 'invoiceSubtotal', 'linesSum', 'matchedTotal', 'monthlyFee', 'monthTotal', 'overBy', 'poTotal', 'printedTotal',
  'remaining', 'subtotal', 'surcharge', 'tax', 'taxBase', 'taxCharged', 'total', 'totalCad', 'unitPrice',
])
const PERCENT_KEYS = new Set(['pct', 'capPct', 'similarity'])
/** Labels that plain camelCase splitting gets wrong or makes vague. */
const LABELS: Record<string, string> = {
  poId: 'PO', poTotal: 'PO total', poVendorId: 'PO vendor', alreadyBilled: 'Already billed on PO', overBy: 'Over by',
  amountCad: 'Amount (CAD)', totalCad: 'Total (CAD)', capPct: 'Cap', pct: 'Percent', gstOnFile: 'GST # on file',
  gstOnInvoice: 'GST # on invoice', qstOnFile: 'QST # on file', qstOnInvoice: 'QST # on invoice', gstNumber: 'GST #',
  invoiceVendorId: 'Invoice vendor', vendorId: 'Vendor', contractId: 'Contract', matchedInvoiceId: 'Matched invoice',
  creditsInvoiceId: 'Credits invoice', resemblesVendorId: 'Resembles vendor', otherVendorIds: 'Other vendors',
  invoiceIds: 'Invoices', lineNo: 'Line', daysBeforeInvoice: 'Days before invoice', ageDays: 'Age (days)',
  invoicePaysToNewAccount: 'Pays to new account', remitAccount: 'Remit-to account', shipToProvince: 'Ship-to province',
  cardTransactionId: 'Card transaction', bankChangeRequestId: 'Bank change request',
}

function label(key: string): string {
  if (LABELS[key]) return LABELS[key]
  const words = key.replace(/([a-z0-9])([A-Z])/g, '$1 $2').toLowerCase()
    .replace(/\b(gst|qst|hst|pst|po|cad|usd|fx)\b/g, (w) => w.toUpperCase())
    .replace(/\bid\b/g, 'ID')
  return words.charAt(0).toUpperCase() + words.slice(1)
}

function value(key: string, v: unknown): string {
  if (v === null || v === undefined || v === '') return '—'
  if (typeof v === 'boolean') return v ? 'Yes' : 'No'
  if (Array.isArray(v)) return v.length ? v.map((x) => (typeof x === 'object' ? JSON.stringify(x) : String(x))).join(', ') : '—'
  if (typeof v === 'object') return Object.entries(v as Record<string, unknown>).map(([k, x]) => `${label(k)}: ${value(k, x)}`).join(' · ')
  if (MONEY_KEYS.has(key) && !Number.isNaN(Number(v))) return money(v)
  if (PERCENT_KEYS.has(key) && !Number.isNaN(Number(v))) return `${Number(v)}%`
  return String(v)
}

function detailRows(evidence: Record<string, unknown>): [string, string][] {
  // 'rule' is the internal rule code; the UI never shows codes.
  return Object.entries(evidence).filter(([k]) => k !== 'rule').map(([k, v]) => [label(k), value(k, v)])
}

function safe(s?: string | null): Record<string, unknown> {
  if (!s) return {}
  try {
    return JSON.parse(s)
  } catch {
    return {}
  }
}
