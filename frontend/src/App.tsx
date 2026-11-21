import { useEffect, useState } from 'react'
import { Badge, Container, Table, Title, Text, Loader } from '@mantine/core'

type Row = { expected: number; actual: number; match: boolean }
type Report = { allMatch: boolean; tables: Record<string, Row> }

// Phase 1 placeholder: proves the frontend -> backend -> database path and shows the seed check.
export default function App() {
  const [report, setReport] = useState<Report | null>(null)
  const [error, setError] = useState<string | null>(null)
  useEffect(() => {
    fetch('/api/admin/seed-report')
      .then((r) => (r.ok ? r.json() : Promise.reject(r.statusText)))
      .then(setReport)
      .catch((e) => setError(String(e)))
  }, [])
  return (
    <Container size="sm" py="xl">
      <Title order={2}>Brim AP - Harborline Logistics</Title>
      <Text c="dimmed" mb="md">Dataset seed check (row counts vs dataset README)</Text>
      {error && <Text c="red">{error}</Text>}
      {!report && !error && <Loader />}
      {report && (
        <>
          <Badge color={report.allMatch ? 'green' : 'red'} mb="sm">
            {report.allMatch ? 'All counts match' : 'Mismatch'}
          </Badge>
          <Table striped>
            <Table.Thead>
              <Table.Tr><Table.Th>Dataset file</Table.Th><Table.Th>Expected</Table.Th><Table.Th>Loaded</Table.Th></Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {Object.entries(report.tables).map(([k, v]) => (
                <Table.Tr key={k} c={v.match ? undefined : 'red'}>
                  <Table.Td>{k}</Table.Td><Table.Td>{v.expected}</Table.Td><Table.Td>{v.actual}</Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </>
      )}
    </Container>
  )
}
