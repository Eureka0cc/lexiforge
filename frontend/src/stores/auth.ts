import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { api } from '../api'
import type { User } from '../types'
import { clearStoredSession, saveStoredSession } from '../lib/authStorage'
import { continuationKey } from '../lib/translationContinuation'

interface AuthResponse {
  accessToken: string
  user: User
}

export const useAuthStore = defineStore('auth', () => {
  function storedUser(): User | null {
    try { return JSON.parse(localStorage.getItem('user') || 'null') as User | null }
    catch { clearStoredSession(); return null }
  }
  const user = ref<User | null>(storedUser())
  const loggedIn = computed(() => Boolean(user.value))

  async function login(payload: { email: string; password: string }) {
    const { data } = await api.post<AuthResponse>('/api/auth/login/v1', payload)
    save(data)
  }

  async function register(payload: { email: string; password: string; displayName: string }) {
    const { data } = await api.post<AuthResponse>('/api/auth/register/v1', payload)
    save(data)
  }

  async function logout() {
    try {
      await api.post('/api/auth/logout/v1')
    } finally {
      clear()
    }
  }
  function clear() {
    user.value = null
    clearStoredSession()
  }
  async function reloadUser() {
    user.value = (await api.get<User>('/api/account/v1')).data
    localStorage.setItem('user', JSON.stringify(user.value))
  }
  function syncUser() {
    const previousId = user.value?.id
    user.value = storedUser()
    if (previousId !== user.value?.id) {
      sessionStorage.removeItem(continuationKey)
      // Discard mounted pages and their in-memory results on another tab's account switch.
      location.reload()
    }
  }
  window.addEventListener('lexiforge:session-updated', syncUser)
  window.addEventListener('storage', event => { if (event.key === 'user') syncUser() })

  function save(data: AuthResponse) {
    saveStoredSession(data.accessToken, data.user)
    user.value = data.user
  }

  return { user, loggedIn, login, register, logout, clear, reloadUser }
})
