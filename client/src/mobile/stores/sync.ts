// 同步状态：有没有网、有几条待上传、上次什么时候传完。
// 什么时候自动上传：打开 APP、网络恢复、APP 回到前台、每分钟一次、刚录完一条
import { ref } from 'vue'
import { defineStore } from 'pinia'
import { App } from '@capacitor/app'
import { Capacitor } from '@capacitor/core'

import { useAuthStore } from '@/stores/auth'
import { addBatch, counts, purgeDone, syncPending, type NewBatch, type SyncResult } from '../outbox'

const LAST_SYNC_KEY = 'tudijiance.mobile.lastSync'

export const useSyncStore = defineStore('mobile-sync', () => {
  const auth = useAuthStore()
  const online = ref(typeof navigator === 'undefined' ? true : navigator.onLine)
  const syncing = ref(false)
  const pending = ref(0)
  const failed = ref(0)
  const others = ref(0)
  const lastSyncAt = ref<number | null>(Number(localStorage.getItem(LAST_SYNC_KEY)) || null)
  /** 登录过期，重新登录后才能上传 */
  const needLogin = ref(false)
  /** 每传上去一批就加 1，页面据此刷新小区状态和列表 */
  const syncedTick = ref(0)

  async function refresh() {
    const uid = auth.user?.id
    if (!uid) {
      pending.value = failed.value = others.value = 0
      return
    }
    const c = await counts(uid)
    pending.value = c.pending
    failed.value = c.failed
    others.value = c.others
  }

  async function sync(): Promise<SyncResult | null> {
    const uid = auth.user?.id
    if (!uid || syncing.value) {
      return null
    }
    syncing.value = true
    try {
      const r = await syncPending(uid)
      if (r.sent > 0 || r.failed > 0) {
        syncedTick.value++
      }
      online.value = r.stoppedBy !== 'offline'
      needLogin.value = r.stoppedBy === 'auth'
      if (r.stoppedBy === null) {
        lastSyncAt.value = Date.now()
        localStorage.setItem(LAST_SYNC_KEY, String(lastSyncAt.value))
      }
      return r
    } finally {
      syncing.value = false
      await refresh()
    }
  }

  /** 录完一条：先存本机，再试着上传 */
  async function record(batch: NewBatch): Promise<void> {
    await addBatch(batch)
    await refresh()
    void sync()
  }

  let started = false
  function start() {
    if (started) {
      return
    }
    started = true
    window.addEventListener('online', () => {
      online.value = true
      void sync()
    })
    window.addEventListener('offline', () => {
      online.value = false
    })
    if (Capacitor.isNativePlatform()) {
      void App.addListener('resume', () => void sync())
    }
    window.setInterval(() => void sync(), 60_000)
    void purgeDone(30)
    void refresh().then(sync)
  }

  return { online, syncing, pending, failed, others, lastSyncAt, needLogin, syncedTick, refresh, sync, record, start }
})
