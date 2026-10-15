import type { User } from '../types'
import { continuationKey } from './translationContinuation'

export function clearStoredSession() {
  localStorage.removeItem('access_token')
  localStorage.removeItem('user')
  sessionStorage.removeItem(continuationKey)
}

export function saveStoredSession(accessToken: string, user: User) {
  let previousId: string | undefined
  try { previousId = JSON.parse(localStorage.getItem('user') || 'null')?.id }
  catch { /* Invalid account cache cannot own a continuation. */ }
  if (previousId !== user.id) sessionStorage.removeItem(continuationKey)
  localStorage.setItem('access_token', accessToken)
  localStorage.setItem('user', JSON.stringify(user))
}
