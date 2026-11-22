import { Badge, Card, Container, Group, SimpleGrid, Stack, Text, Title, UnstyledButton } from '@mantine/core'
import { useQuery } from '@tanstack/react-query'
import { api } from '../api/client'
import type { UserSummary } from '../api/types'
import { useAuth } from '../auth/AuthContext'
import { notifyError } from '../components/notify'
import { ROLE_COLOR, ROLE_LABEL } from '../lib/format'
import { UserSwitcher } from '../components/AppLayout'

/** Demo login: pick any of the 40 Harborline employees. The server issues a real session for that user. */
const FEATURED = ['U004', 'U003', 'U002', 'U001', 'U006', 'U007', 'U012', 'U037']

export default function LoginPage() {
  const { login } = useAuth()
  const users = useQuery({ queryKey: ['switcher-users'], queryFn: () => api.get<UserSummary[]>('/auth/users') })
  const featured = (users.data ?? []).filter((u) => FEATURED.includes(u.id!)).sort((a, b) => FEATURED.indexOf(a.id!) - FEATURED.indexOf(b.id!))
  return (
    <div className="ap-workspace" style={{ minHeight: '100vh' }}>
    <Container size="md" py={60}>
      <Stack gap="lg">
        <div>
          <Title order={1} className="ap-brand" fz={30}><span>Harborline</span> AP</Title>
          <Text c="dimmed" mt={4}>Harborline Logistics accounts payable: inbox to payment with the right approvals and a full audit trail. Choose who you are.</Text>
        </div>
        <SimpleGrid cols={{ base: 1, sm: 2, md: 4 }}>
          {featured.map((u) => (
            <UnstyledButton key={u.id} onClick={() => login(u.id!).catch(notifyError)} disabled={u.status === 'terminated'}>
              <Card withBorder padding="md" h="100%" className="ap-stat">
                <Stack gap={4}>
                  <Badge color={ROLE_COLOR[u.role!]} variant="light" w="fit-content">{ROLE_LABEL[u.role!]}</Badge>
                  <Text fw={600}>{u.fullName}</Text>
                  <Text size="xs" c="dimmed">{u.title}{u.status !== 'active' ? ` · ${u.status?.replace('_', ' ')}` : ''}</Text>
                </Stack>
              </Card>
            </UnstyledButton>
          ))}
        </SimpleGrid>
        <Group>
          <Text size="sm">Or any of the 40 users:</Text>
          <UserSwitcher width={300} />
        </Group>
        <Text size="xs" c="dimmed">Demo only: there is no password. Every permission is still enforced on the server from the session.</Text>
      </Stack>
    </Container>
    </div>
  )
}
