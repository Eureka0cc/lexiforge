import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { api } from '../api'
import type { User } from '../types'

interface AuthResponse {
  accessToken: string
  user: User
}

export const useAuthStore = defineStore('auth', () => {
  function storedUser(): User | null {
    try { return JSON.parse(localStorage.getItem('user') || 'null') as User | null }
    catch { localStorage.removeItem('user'); localStorage.removeItem('access_token'); return null }
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
    localStorage.removeItem('access_token')
    localStorage.removeItem('user')
    sessionStorage.removeItem('lexiforge:last-translation')
  }
  async function reloadUser() {
    user.value = (await api.get<User>('/api/account/v1')).data
    localStorage.setItem('user', JSON.stringify(user.value))
  }
  window.addEventListener('lexiforge:session-updated', () => { user.value = storedUser() })
  window.addEventListener('storage', event => { if (event.key === 'user') user.value = storedUser() })

  function save(data: AuthResponse) {
    localStorage.setItem('access_token', data.accessToken)
    localStorage.setItem('user', JSON.stringify(data.user))
    user.value = data.user
  }

  return { user, loggedIn, login, register, logout, clear, reloadUser }
})
