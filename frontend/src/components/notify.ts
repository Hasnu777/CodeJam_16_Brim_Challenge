import { notifications } from '@mantine/notifications'
import { ApiError } from '../api/client'

export function notifyError(e: unknown) {
  const msg = e instanceof ApiError ? e.reason : e instanceof Error ? e.message : String(e)
  const title = e instanceof ApiError ? (e.status === 403 ? 'Not allowed' : e.status === 409 ? 'Not possible yet' : `Error ${e.status}`) : 'Error'
  notifications.show({ color: 'red', title, message: msg, autoClose: 8000 })
}

export function notifyOk(message: string) {
  notifications.show({ color: 'green', message, autoClose: 4000 })
}
