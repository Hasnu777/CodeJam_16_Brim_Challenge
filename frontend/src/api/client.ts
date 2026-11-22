// Thin fetch wrapper. Session cookie auth (same origin via Vite/nginx proxy). Errors carry the server's reason.

export class ApiError extends Error {
  status: number
  reason: string
  constructor(status: number, reason: string) {
    super(reason)
    this.status = status
    this.reason = reason
  }
}

async function request<T>(method: string, path: string, body?: unknown, isForm = false): Promise<T> {
  const init: RequestInit = { method, credentials: 'same-origin', headers: {} }
  if (body !== undefined) {
    if (isForm) init.body = body as FormData
    else {
      init.body = JSON.stringify(body)
      ;(init.headers as Record<string, string>)['Content-Type'] = 'application/json'
    }
  }
  const res = await fetch('/api' + path, init)
  const text = await res.text()
  const data = text ? safeJson(text) : null
  if (!res.ok) {
    const reason = (data && typeof data === 'object' && 'reason' in data ? String((data as { reason: unknown }).reason) : null) ?? res.statusText
    throw new ApiError(res.status, reason || `HTTP ${res.status}`)
  }
  return data as T
}

function safeJson(t: string): unknown {
  try {
    return JSON.parse(t)
  } catch {
    return t
  }
}

export const api = {
  get: <T>(p: string) => request<T>('GET', p),
  post: <T>(p: string, body?: unknown) => request<T>('POST', p, body ?? {}),
  put: <T>(p: string, body?: unknown) => request<T>('PUT', p, body ?? {}),
  upload: <T>(p: string, form: FormData) => request<T>('POST', p, form, true),
  del: <T>(p: string, body?: unknown) => request<T>('DELETE', p, body ?? {}),
}

/** Build a query string, skipping empty values. */
export function qs(params: Record<string, string | number | boolean | undefined | null>): string {
  const u = new URLSearchParams()
  Object.entries(params).forEach(([k, v]) => {
    if (v !== undefined && v !== null && v !== '') u.set(k, String(v))
  })
  const s = u.toString()
  return s ? '?' + s : ''
}
