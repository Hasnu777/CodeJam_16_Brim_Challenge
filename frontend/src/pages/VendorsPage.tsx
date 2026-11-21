import { Anchor, Badge, Button, Grid, Group, Modal, NumberInput, Select, Stack, Switch, Table, Text, TextInput, Title } from '@mantine/core'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useMemo, useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { api } from '../api/client'
import type { GlAccount, InvoiceDetail, Vendor, VendorRow } from '../api/types'
import { useAuth } from '../auth/AuthContext'
import { Loading } from '../components/bits'
import { notifyError, notifyOk } from '../components/notify'
import { money } from '../lib/format'

const STATUS_COLOR: Record<string, string> = { active: 'green', pending_review: 'orange', on_hold: 'red' }

export default function VendorsPage() {
  const { is } = useAuth()
  const [sp, setSp] = useSearchParams()
  const [q, setQ] = useState('')
  const [onlyAttention, setOnlyAttention] = useState(false)
  const list = useQuery({ queryKey: ['vendors'], queryFn: () => api.get<VendorRow[]>('/vendors') })
  const rows = useMemo(() => (list.data ?? []).filter((r) => {
    const v = r.vendor!
    if (onlyAttention && v.status === 'active' && !r.pendingBankChange) return false
    return !q || `${v.id} ${v.legalName} ${v.category}`.toLowerCase().includes(q.toLowerCase())
  }), [list.data, q, onlyAttention])
  if (list.isLoading) return <Loading />
  return (
    <Stack>
      <Group justify="space-between">
        <Title order={3}>Vendors</Title>
        {is('CLK', 'ADM') && <Button onClick={() => setSp({ onboard: 'new' })}>Onboard vendor</Button>}
      </Group>
      <Group>
        <TextInput placeholder="Search name, id, category" value={q} onChange={(e) => setQ(e.currentTarget.value)} w={280} />
        <Switch label="Needs attention (pending, on hold, bank change)" checked={onlyAttention} onChange={(e) => setOnlyAttention(e.currentTarget.checked)} />
      </Group>
      <Table striped highlightOnHover>
        <Table.Thead><Table.Tr><Table.Th>Vendor</Table.Th><Table.Th>Category</Table.Th><Table.Th>Status</Table.Th><Table.Th>Bank</Table.Th>
          <Table.Th ta="right">Spend YTD</Table.Th><Table.Th>Invoices</Table.Th><Table.Th>Open flags</Table.Th></Table.Tr></Table.Thead>
        <Table.Tbody>
          {rows.map((r) => {
            const v = r.vendor!
            return (
              <Table.Tr key={v.id}>
                <Table.Td><Anchor component={Link} to={`/vendors/${v.id}`}>{v.legalName}</Anchor><Text size="xs" c="dimmed">{v.id} · {v.provinceOrState} {v.country}</Text></Table.Td>
                <Table.Td><Text size="sm">{v.category}</Text>{v.requiresPo && <Badge size="xs" variant="outline">PO required</Badge>}</Table.Td>
                <Table.Td><Badge color={STATUS_COLOR[v.status!] ?? 'gray'} variant="light">{v.status?.replace('_', ' ')}</Badge></Table.Td>
                <Table.Td><Text size="sm">{r.bankAccount ?? v.preferredPaymentMethod}</Text>
                  {r.pendingBankChange ? <Badge size="xs" color="red">change pending</Badge> : r.bankAccount && !r.bankVerified ? <Badge size="xs" color="red">unverified</Badge> : null}</Table.Td>
                <Table.Td ta="right"><Text size="sm" ff="monospace">{money(r.spendYtdCad)}</Text></Table.Td>
                <Table.Td>{r.invoiceCount}</Table.Td>
                <Table.Td>{r.openFlags ? <Badge color="orange" variant="light">{r.openFlags}</Badge> : ''}</Table.Td>
              </Table.Tr>
            )
          })}
        </Table.Tbody>
      </Table>
      {sp.get('onboard') && <OnboardModal fromInvoiceId={sp.get('onboard') === 'new' ? null : sp.get('onboard')} onClose={() => setSp({})} />}
    </Stack>
  )
}

function OnboardModal({ fromInvoiceId, onClose }: { fromInvoiceId: string | null; onClose: () => void }) {
  const qc = useQueryClient()
  const nav = useNavigate()
  const gl = useQuery({ queryKey: ['gl'], queryFn: () => api.get<GlAccount[]>('/ref/gl-accounts') })
  const src = useQuery({ queryKey: ['invoice', fromInvoiceId], queryFn: () => api.get<InvoiceDetail>(`/invoices/${fromInvoiceId}`), enabled: !!fromInvoiceId })
  const [f, setF] = useState({ legalName: '', category: '', defaultGlAccount: '', gstHstNumber: '', provinceOrState: '', country: 'CA', apContactEmail: '',
    paymentTermsDays: 30, preferredPaymentMethod: 'EFT', bankInstitution: '', bankTransit: '', bankAccount: '', requiresPo: false })
  useEffect(() => {
    const i = src.data?.invoice
    if (i) setF((x) => ({ ...x, legalName: i.vendorNameRaw ?? '', gstHstNumber: i.gstNumberOnInvoice ?? '', apContactEmail: i.senderEmail ?? '',
      bankInstitution: i.remitInstitution ?? '', bankTransit: i.remitTransit ?? '', bankAccount: i.remitAccount ?? '' }))
  }, [src.data])
  const set = (k: string) => (e: { currentTarget: { value: string } }) => setF({ ...f, [k]: e.currentTarget.value })
  const save = async () => {
    try {
      const v = await api.post<Vendor>('/vendors', { ...f, fromInvoiceId })
      notifyOk(`${v.id} created as pending review; a Finance Manager must approve it`)
      await qc.invalidateQueries()
      onClose()
      nav(fromInvoiceId ? `/invoices/${fromInvoiceId}` : `/vendors/${v.id}`)
    } catch (e) {
      notifyError(e)
    }
  }
  return (
    <Modal opened onClose={onClose} title={fromInvoiceId ? `Onboard the vendor from ${fromInvoiceId}` : 'Onboard a vendor'} size="lg">
      <Stack>
        <Text size="sm" c="dimmed">The vendor starts as pending review. Its bills are held until a Finance Manager (not you) approves it, which also verifies the bank account.</Text>
        <Grid gutter="xs">
          <Grid.Col span={8}><TextInput label="Legal name" required value={f.legalName} onChange={set('legalName')} /></Grid.Col>
          <Grid.Col span={4}><TextInput label="GST/HST #" value={f.gstHstNumber} onChange={set('gstHstNumber')} placeholder="123456789RT0001" /></Grid.Col>
          <Grid.Col span={6}><TextInput label="Category" value={f.category} onChange={set('category')} /></Grid.Col>
          <Grid.Col span={6}><Select label="Default GL account" searchable value={f.defaultGlAccount || null} onChange={(v) => setF({ ...f, defaultGlAccount: v ?? '' })}
            data={(gl.data ?? []).map((g) => ({ value: g.accountNumber!, label: `${g.accountNumber} ${g.name}` }))} /></Grid.Col>
          <Grid.Col span={6}><TextInput label="AP contact email" value={f.apContactEmail} onChange={set('apContactEmail')} /></Grid.Col>
          <Grid.Col span={3}><TextInput label="Province/state" value={f.provinceOrState} onChange={set('provinceOrState')} /></Grid.Col>
          <Grid.Col span={3}><TextInput label="Country" value={f.country} onChange={set('country')} /></Grid.Col>
          <Grid.Col span={4}><NumberInput label="Terms (days)" value={f.paymentTermsDays} onChange={(v) => setF({ ...f, paymentTermsDays: Number(v) || 30 })} /></Grid.Col>
          <Grid.Col span={4}><Select label="Payment method" value={f.preferredPaymentMethod} data={['EFT', 'CHEQUE', 'CARD']} onChange={(v) => setF({ ...f, preferredPaymentMethod: v ?? 'EFT' })} /></Grid.Col>
          <Grid.Col span={4}><Switch mt={28} label="Requires PO" checked={f.requiresPo} onChange={(e) => setF({ ...f, requiresPo: e.currentTarget.checked })} /></Grid.Col>
          <Grid.Col span={3}><TextInput label="Institution" value={f.bankInstitution} onChange={set('bankInstitution')} /></Grid.Col>
          <Grid.Col span={3}><TextInput label="Transit" value={f.bankTransit} onChange={set('bankTransit')} /></Grid.Col>
          <Grid.Col span={6}><TextInput label="Account" value={f.bankAccount} onChange={set('bankAccount')} /></Grid.Col>
        </Grid>
        <Button onClick={save} disabled={!f.legalName.trim()}>Create (pending review)</Button>
      </Stack>
    </Modal>
  )
}
