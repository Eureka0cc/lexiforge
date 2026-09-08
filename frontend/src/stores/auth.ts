import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { api } from '../api'
import type { User } from '../types'

interface AuthResponse {
  accessToken: string
  user: User
}

export const useAuthStore = defineStore('auth', () => {
  const user = ref<User | null>(JSON.parse(localStorage.getItem('user') || 'null'))
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
      user.value = null
      localStorage.clear()
    }
  }

  function save(data: AuthResponse) {
    localStorage.setItem('access_token', data.accessToken)
    localStorage.setItem('user', JSON.stringify(data.user))
    user.value = data.user
  }

  return { user, loggedIn, login, register, logout }
})
