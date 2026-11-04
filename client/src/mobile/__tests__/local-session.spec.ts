import 'fake-indexeddb/auto'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/shared/edition', () => ({
  OFFLINE_EDITION: true,
  mobileStorageKey: (key: string) => `tudijiance.offline.${key}`,
}))

import { authApi, observationApi } from '@/shared/api'
import { http } from '@/shared/api/http'
import { useAuthStore } from '@/stores/auth'
import { db } from '../db'
import { useSyncStore } from '../stores/sync'
import { repo } from '../repo'

beforeEach(async () => {
  localStorage.clear()
  await db.delete()
  await db.open()
  setActivePinia(createPinia())
})
afterEach(async () => {
  await db.delete()
  vi.restoreAllMocks()
  vi.useRealTimers()
})

describe('offline edition login and sync', () => {
  it('enters with no credentials or server and restores the session without an expiry', async () => {
    const login = vi.spyOn(authApi, 'login').mockRejectedValue(new Error('no server'))
    const auth = useAuthStore()
    expect(auth.loggedIn).toBe(false)
    await auth.enterLocal()
    expect(auth.loggedIn).toBe(true)
    expect(auth.canRecord).toBe(true)
    expect(login).not.toHaveBeenCalled()
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date('2040-01-01T00:00:00Z'))
    setActivePinia(createPinia())
    expect(useAuthStore().loggedIn).toBe(true)
  })

  it('does not consume or overwrite the online account and keeps data after logout', async () => {
    localStorage.setItem('tudijiance.session', 'existing-online-session')
    const auth = useAuthStore()
    expect(auth.loggedIn).toBe(false)
    await auth.enterLocal()
    auth.logout()
    expect(auth.loggedIn).toBe(false)
    await auth.enterLocal()
    expect((await repo.fields()).data).toHaveLength(1)
    expect(localStorage.getItem('tudijiance.session')).toBe('existing-online-session')
    expect(db.name).toBe('tudijiance-offline')
  })

  it('does not start an upload timer or send anything even when a network is available', async () => {
    const send = vi.spyOn(observationApi, 'create')
    const timer = vi.spyOn(window, 'setInterval')
    await useAuthStore().enterLocal()
    const sync = useSyncStore()
    sync.start()
    expect(await sync.sync()).toBeNull()
    const trait = (await repo.traits()).data.find((t) => t.trait.code === 'plant_height')!
    await sync.record({
      userId: 1, fieldId: 1, trialId: 1, traitId: trait.trait.id, traitName: trait.trait.name,
      plots: [{ plotId: 1, code: '01', clientUuid: 'offline-record' }],
      value: { valueNum: 72, valueText: null, valueDate: null }, displayValue: '72 cm', observedOn: '2026-11-02', note: null,
    })
    expect((await db.outbox.toArray())[0]?.status).toBe('local')
    expect(sync.syncedTick).toBe(1)
    expect(send).not.toHaveBeenCalled()
    expect(timer).not.toHaveBeenCalled()
  })

  it('blocks accidental API calls before they reach the network', async () => {
    const adapter = vi.fn()
    await expect(http.get('/api/fields', { adapter })).rejects.toThrow('离线版仅使用本机数据')
    expect(adapter).not.toHaveBeenCalled()
  })
})
