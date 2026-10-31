<script setup lang="ts">
// 记录：这台手机上录的数据和上传情况。没传上去的说明原因，可以重试或删掉
import { computed, onActivated, ref, watch } from 'vue'
import { showConfirmDialog, showToast } from 'vant'

import { useAuthStore } from '@/stores/auth'
import { useSyncStore } from '../stores/sync'
import { useFieldStore } from '../stores/field'
import { discard, recent, retry } from '../outbox'
import type { OutboxBatch } from '../db'
import { timeAgo } from '../display'
import EmptyState from '@/desktop/components/EmptyState.vue'

const auth = useAuthStore()
const sync = useSyncStore()
const field = useFieldStore()

const list = ref<OutboxBatch[]>([])
async function load() {
  if (auth.user) list.value = await recent(auth.user.id, 300)
}
onActivated(load)
void load()
// 上传完一轮、数量变了就刷新列表
watch(() => [sync.pending, sync.failed, sync.syncing], load)

const groups = computed(() => {
  const map = new Map<string, OutboxBatch[]>()
  const today = new Date().toDateString()
  const yesterday = new Date(Date.now() - 86_400_000).toDateString()
  for (const b of list.value) {
    const d = new Date(b.createdAt)
    const key = d.toDateString() === today ? '今天' : d.toDateString() === yesterday ? '昨天' : `${d.getMonth() + 1} 月 ${d.getDate()} 日`
    map.set(key, [...(map.get(key) ?? []), b])
  }
  return [...map.entries()]
})

async function syncNow() {
  const r = await sync.sync()
  if (!r) return
  if (r.stoppedBy === 'offline') showToast('没有网络，联网后会自动上传')
  else if (r.stoppedBy === 'auth') showToast('登录已过期，请重新登录')
  else showToast({ type: 'success', message: r.sent ? `上传了 ${r.sent} 条` : '没有要上传的' })
}

async function retryOne(b: OutboxBatch) {
  await retry(b.id!)
  await sync.refresh()
  await syncNow()
}

async function remove(b: OutboxBatch) {
  try {
    await showConfirmDialog({ title: '删掉这条录入？', message: `${b.traitName}：${b.displayValue}，${b.plots.length} 个小区。删掉后不会上传。` })
  } catch {
    return
  }
  await discard(b.id!)
  await sync.refresh()
  await field.refreshUnsynced()
  await load()
}

const statusText = computed(() => {
  if (sync.syncing) return { icon: 'replay', text: '正在上传…', tone: 'busy' }
  if (sync.needLogin) return { icon: 'warning-o', text: '登录已过期，重新登录后接着上传', tone: 'bad' }
  if (sync.failed) return { icon: 'warning-o', text: `${sync.failed} 条没传上去，看看下面的原因`, tone: 'bad' }
  if (sync.pending) return { icon: 'clock-o', text: sync.online ? `${sync.pending} 条待上传` : `${sync.pending} 条存在手机上，联网后自动上传`, tone: 'wait' }
  return { icon: 'passed', text: '全部已上传', tone: 'good' }
})
</script>

<template>
  <div class="m-page records">
    <section class="m-card status" :class="statusText.tone">
      <van-icon :name="statusText.icon" class="status-icon" />
      <div class="status-text">
        <b>{{ statusText.text }}</b>
        <span class="m-muted">上次全部传完：{{ timeAgo(sync.lastSyncAt) }}<template v-if="sync.others"> · 另有 {{ sync.others }} 条是别的账号录的</template></span>
      </div>
      <van-button size="small" round type="primary" :loading="sync.syncing" @click="syncNow">立即上传</van-button>
    </section>

    <EmptyState v-if="list.length === 0" art="data" title="还没有录过" desc="到平面图上选中小区，点录入。录的数据会先存在手机上，有网就自动上传。" :size="120" />

    <div v-for="[day, items] in groups" :key="day" class="group">
      <div class="day m-title">{{ day }}<span class="m-muted">{{ items.length }} 条</span></div>
      <van-swipe-cell v-for="b in items" :key="b.id" :disabled="b.status === 'done'">
        <div class="item m-card" :class="b.status">
          <div class="item-top">
            <b>{{ b.traitName }}</b>
            <span class="value">{{ b.displayValue }}</span>
          </div>
          <div class="item-sub m-muted">
            {{ b.plots.length > 4 ? `${b.plots.length} 个小区：${b.plots.slice(0, 4).map((p) => p.code).join('、')}…` : b.plots.map((p) => p.code).join('、') }}
          </div>
          <div class="item-foot">
            <span class="m-muted">调查 {{ b.observedOn }} · {{ timeAgo(b.createdAt) }}</span>
            <van-tag :type="b.status === 'done' ? 'success' : b.status === 'failed' ? 'danger' : 'warning'" plain round>
              {{ b.status === 'done' ? '已上传' : b.status === 'failed' ? '没传上去' : '待上传' }}
            </van-tag>
          </div>
          <div v-if="b.status === 'failed'" class="failed">
            <span>{{ b.error }}</span>
            <van-button size="mini" round type="primary" @click="retryOne(b)">重试</van-button>
            <van-button size="mini" round plain type="danger" @click="remove(b)">删掉</van-button>
          </div>
        </div>
        <template #right>
          <van-button square type="danger" text="删掉" class="swipe-del" @click="remove(b)" />
        </template>
      </van-swipe-cell>
    </div>
  </div>
</template>

<style scoped>
.status {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px;
  margin-bottom: 8px;
}

.status-icon {
  font-size: 28px;
}

.status.good .status-icon { color: var(--green); }
.status.wait .status-icon,
.status.busy .status-icon { color: var(--wheat); }
.status.bad .status-icon { color: var(--clay); }

.status-text {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 3px;
  min-width: 0;
}

.status-text b {
  color: var(--forest);
}

.day {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  margin: 18px 2px 8px;
  font-size: 15px;
}

.day .m-muted {
  font-family: var(--font-sans);
  font-weight: normal;
}

.item {
  padding: 12px 14px;
  margin-bottom: 8px;
}

.item.failed {
  border-color: #ecc6b8;
}

.item-top {
  display: flex;
  justify-content: space-between;
  gap: 8px;
}

.item-top b {
  color: var(--forest);
}

.value {
  font-weight: 700;
  color: var(--green);
}

.item-sub {
  margin-top: 3px;
}

.item-foot {
  margin-top: 6px;
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.failed {
  margin-top: 8px;
  padding: 8px 10px;
  border-radius: 10px;
  background: var(--clay-soft);
  color: var(--clay);
  font-size: 13px;
  display: flex;
  align-items: center;
  gap: 6px;
}

.failed span {
  flex: 1;
}

.swipe-del {
  height: calc(100% - 8px);
  border-radius: 0 14px 14px 0;
}
</style>
