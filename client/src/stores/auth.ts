import { computed, ref } from 'vue'
import { defineStore } from 'pinia'

import { authApi, setToken, type UserView } from '@/shared/api'

const STORAGE_KEY = 'tudijiance.session'

interface Session {
  token: string
  expiresAt: string
  user: UserView
}

function loadSession(): Session | null {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) {
      return null
    }
    const session = JSON.parse(raw) as Session
    return new Date(session.expiresAt) > new Date() ? session : null
  } catch {
    return null
  }
}

function saveSession(session: Session | null): void {
  try {
    if (session) {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(session))
    } else {
      localStorage.removeItem(STORAGE_KEY)
    }
  } catch {
    // 本机存储不可用时，关掉程序后需要重新登录
  }
}

export const useAuthStore = defineStore('auth', () => {
  const session = ref<Session | null>(loadSession())
  setToken(session.value?.token ?? null)

  const user = computed(() => session.value?.user ?? null)
  const loggedIn = computed(() => session.value !== null)
  const isAdmin = computed(() => session.value?.user.role === 'ADMIN')

  async function login(username: string, password: string): Promise<void> {
    const res = await authApi.login(username, password)
    session.value = { token: res.token, expiresAt: res.expiresAt, user: res.user }
    setToken(res.token)
    saveSession(session.value)
  }

  function logout(): void {
    session.value = null
    setToken(null)
    saveSession(null)
  }

  return { user, loggedIn, isAdmin, login, logout }
})
