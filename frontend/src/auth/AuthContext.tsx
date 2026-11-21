import { createContext, useContext, useEffect, useState, type ReactNode } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { api } from '../api/client'
import type { Me, Role } from '../api/types'

interface AuthState {
  me: Me | null
  loading: boolean
  login: (userId: string) => Promise<void>
  logout: () => Promise<void>
  is: (...roles: Role[]) => boolean
}

const Ctx = createContext<AuthState | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [me, setMe] = useState<Me | null>(null)
  const [loading, setLoading] = useState(true)
  const qc = useQueryClient()

  useEffect(() => {
    api.get<Me>('/auth/me').then(setMe).catch(() => setMe(null)).finally(() => setLoading(false))
  }, [])

  const login = async (userId: string) => {
    const m = await api.post<Me>('/auth/login', { userId })
    qc.clear() // every cached list is scoped to the previous user
    setMe(m)
  }
  const logout = async () => {
    await api.post('/auth/logout')
    qc.clear()
    setMe(null)
  }
  const is = (...roles: Role[]) => !!me && roles.includes(me.role)

  return <Ctx.Provider value={{ me, loading, login, logout, is }}>{children}</Ctx.Provider>
}

export function useAuth(): AuthState {
  const c = useContext(Ctx)
  if (!c) throw new Error('useAuth outside AuthProvider')
  return c
}
