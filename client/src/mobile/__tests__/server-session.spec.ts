import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useAuthStore } from '@/stores/auth'
import { authApi } from '@/shared/api'

const user = { id: 12, username: 'teacher', displayName: '老师', role: 'TEACHER' as const, enabled: true }

beforeEach(() => {
  localStorage.clear()
  setActivePinia(createPinia())
})
afterEach(() => vi.restoreAllMocks())

describe('server edition authentication stays separate', () => {
  it('rejects a local session placed in the server session key', async () => {
    localStorage.setItem('tudijiance.session', JSON.stringify({ mode: 'local', token: null, expiresAt: null, user }))
    const auth = useAuthStore()
    expect(auth.loggedIn).toBe(false)
    await expect(auth.enterLocal()).rejects.toThrow('独立离线版')
  })

  it('still authenticates the online edition through its server', async () => {
    const login = vi.spyOn(authApi, 'login').mockResolvedValue({ token: 'test-server-token', expiresAt: '2099-01-01T00:00:00Z', user })
    const auth = useAuthStore()
    await auth.login('teacher', 'test-password', 'MOBILE')
    expect(login).toHaveBeenCalledWith('teacher', 'test-password', 'MOBILE')
    expect(auth.user?.id).toBe(12)
    expect(localStorage.getItem('tudijiance.offline.session')).toBeNull()
  })
})
