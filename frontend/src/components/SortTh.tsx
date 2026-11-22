import { Group, Table, UnstyledButton } from '@mantine/core'
import { IconArrowDown, IconArrowUp, IconSelector } from '@tabler/icons-react'

/** A table header that sorts its column: arrow on the active column, a faint selector on the others. */
export function SortTh({ label, asc, active = true, onClick, ta }: {
  label: string
  asc: boolean
  active?: boolean
  onClick: () => void
  ta?: 'left' | 'right'
}) {
  const icon = !active ? <IconSelector size={14} opacity={0.4} /> : asc ? <IconArrowUp size={14} /> : <IconArrowDown size={14} />
  return (
    <Table.Th ta={ta}>
      <UnstyledButton onClick={onClick} fw={700} fz="sm" aria-label={`Sort by ${label}`}>
        <Group gap={4} wrap="nowrap" justify={ta === 'right' ? 'flex-end' : undefined}>{label}{icon}</Group>
      </UnstyledButton>
    </Table.Th>
  )
}
