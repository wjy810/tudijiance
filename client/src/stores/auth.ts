import { computed, ref } from 'vue'
import { defineStore } from 'pinia'

import { authApi, setToken, type Role, type UserView } from '@/shared/api'
import { useCatalogStore } from '@/stores/catalog'

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
  const role = computed<Role | null>(() => session.value?.user.role ?? null)
  // 四级权限：系统管理员管系统和用户；老师录入、查看、修改；学生录入、查看；游客只看大屏
  const isSysAdmin = computed(() => role.value === 'SYS_ADMIN')
  const isTeacher = computed(() => role.value === 'TEACHER')
  const isGuest = computed(() => role.value === 'GUEST')
  const canRecord = computed(() => role.value === 'TEACHER' || role.value === 'STUDENT')

  /** 登录后的首页 */
  const homePath = computed(() => {
    if (role.value === 'GUEST') {
      return '/screen'
    }
    return role.value === 'SYS_ADMIN' ? '/users' : '/fields'
  })

  async function login(username: string, password: string): Promise<void> {
    const res = await authApi.login(username, password)
    session.value = { token: res.token, expiresAt: res.expiresAt, user: res.user }
    setToken(res.token)
    saveSession(session.value)
  }

  function logout(): void {
    // 换账号登录后要重新取品种和项目
    useCatalogStore().reset()
    session.value = null
    setToken(null)
    saveSession(null)
  }

  return { user, loggedIn, role, isSysAdmin, isTeacher, isGuest, canRecord, homePath, login, logout }
})
