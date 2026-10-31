<script setup lang="ts">
// 首页：今天的情况。当前实验田和试验、今天录了多少、同步情况，以及今天在这台手机上录的
import { computed, onActivated, ref, watch } from 'vue'
import { useRouter } from 'vue-router'

import { useAuthStore } from '@/stores/auth'
import { useFieldStore } from '../stores/field'
import { useSyncStore } from '../stores/sync'
import { recent } from '../outbox'
import type { OutboxBatch } from '../db'
import { timeAgo } from '../display'
import wheatArt from '@/assets/art/sidebar-wheat.webp'

const auth = useAuthStore()
const field = useFieldStore()
const sync = useSyncStore()
const router = useRouter()

const now = new Date()
const greeting = computed(() => {
  const h = now.getHours()
  return h < 11 ? '早上好' : h < 13 ? '中午好' : h < 18 ? '下午好' : '晚上好'
})
const dateText = `${now.getMonth() + 1} 月 ${now.getDate()} 日 星期${'日一二三四五六'[now.getDay()]}`

const todays = ref<OutboxBatch[]>([])
async function loadToday() {
  const uid = auth.user?.id
  if (!uid) return
  const start = new Date().setHours(0, 0, 0, 0)
  todays.value = (await recent(uid, 100)).filter((b) => b.createdAt >= start)
}
onActivated(loadToday)
watch(() => [sync.pending, sync.failed, sync.syncedTick], loadToday)
void loadToday()

const ind = computed(() => field.status?.indicators ?? null)
const percent = computed(() => (ind.value && ind.value.totalPlots ? Math.round((ind.value.enteredToday / ind.value.totalPlots) * 100) : 0))
const todayPlots = computed(() => todays.value.reduce((n, b) => n + b.plots.length, 0))

const syncText = computed(() => {
  if (sync.syncing) return '正在上传…'
  if (sync.failed) return `${sync.failed} 条没传上去`
  if (sync.pending) return sync.online ? `${sync.pending} 条待上传` : `${sync.pending} 条存在手机上`
  return sync.online ? '全部已上传' : '没有网络'
})
</script>

<template>
  <div class="home">
    <header class="hero">
      <img :src="wheatArt" alt="" class="art" />
      <div class="date">{{ dateText }}</div>
      <div class="hello serif">{{ greeting }}，{{ auth.user?.displayName || auth.user?.username }}</div>
      <button type="button" class="sync" :class="{ warn: sync.failed > 0, off: !sync.online }" @click="router.push('/records')">
        <van-icon :name="sync.syncing ? 'replay' : sync.online ? 'passed' : 'warning-o'" />{{ syncText }}
      </button>
    </header>

    <div class="m-page body">
      <section class="m-card field-card" @click="router.push('/map')">
        <div class="field-top">
          <div>
            <div class="m-title field-name">{{ field.field?.name ?? '还没有实验田' }}</div>
            <div class="m-muted">{{ field.trial ? `${field.trial.seasonYear} 年 · ${field.trial.name}` : '还没有试验' }}</div>
          </div>
          <span v-if="ind?.currentStage" class="stage">{{ ind.currentStage }}</span>
        </div>
        <template v-if="ind">
          <div class="progress-row">
            <span class="m-muted">今天已录</span>
            <van-progress :percentage="percent" :show-pivot="false" stroke-width="8" color="#2e6b4f" track-color="#ebe3d1" class="bar" />
            <span class="num">{{ ind.enteredToday }} / {{ ind.totalPlots }}</span>
          </div>
          <div class="facts">
            <div><b class="num">{{ ind.planted }}</b><span>已种植</span></div>
            <div><b class="num">{{ ind.harvested }}</b><span>已收获</span></div>
            <div :class="{ danger: ind.abnormal > 0 }"><b class="num">{{ ind.abnormal }}</b><span>异常</span></div>
            <div><b class="num">{{ ind.weekEntries }}</b><span>本周录入</span></div>
          </div>
        </template>
        <van-loading v-else-if="field.loading" size="20" class="loading">加载中…</van-loading>
        <div v-else-if="field.error" class="error">{{ field.error }}</div>
      </section>

      <van-button block round size="large" class="btn-wheat go" icon="edit" @click="router.push('/map')">去平面图选小区录入</van-button>

      <section class="today">
        <div class="section-title m-title">今天在这台手机上录的<span class="m-muted">{{ todays.length }} 次 · {{ todayPlots }} 个小区</span></div>
        <div v-if="todays.length === 0" class="m-muted empty">还没录。到平面图上选中小区，点"录入"。</div>
        <div v-for="b in todays.slice(0, 8)" :key="b.id" class="entry m-card">
          <div class="entry-main">
            <b>{{ b.traitName }}</b>
            <span class="value">{{ b.displayValue }}</span>
          </div>
          <div class="entry-sub m-muted">
            {{ b.plots.length > 3 ? `${b.plots.length} 个小区` : b.plots.map((p) => p.code).join('、') }} · {{ timeAgo(b.createdAt) }}
            <van-tag :type="b.status === 'done' ? 'success' : b.status === 'failed' ? 'danger' : 'warning'" plain round>
              {{ b.status === 'done' ? '已上传' : b.status === 'failed' ? '没传上去' : '待上传' }}
            </van-tag>
          </div>
        </div>
        <van-button v-if="todays.length > 8" block plain round size="small" @click="router.push('/records')">查看全部</van-button>
      </section>
    </div>
  </div>
</template>

<style scoped>
.hero {
  position: relative;
  overflow: hidden;
  padding: calc(22px + env(safe-area-inset-top)) 20px 26px;
  color: #fbf3df;
  background:
    radial-gradient(120% 80% at 100% 0%, rgba(226, 182, 90, 0.22), transparent 60%),
    linear-gradient(160deg, #24513d 0%, #183a2b 70%);
}

.art {
  position: absolute;
  right: -30px;
  bottom: -60px;
  width: 180px;
  opacity: 0.25;
  pointer-events: none;
}

.date {
  position: relative;
  font-size: 13px;
  color: var(--wheat-bright);
  letter-spacing: 1px;
}

.hello {
  position: relative;
  margin-top: 4px;
  font-size: 24px;
  font-weight: 700;
  letter-spacing: 1px;
}

.sync {
  position: relative;
  margin-top: 12px;
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: 30px;
  padding: 0 12px;
  border-radius: 15px;
  border: 1px solid rgba(226, 182, 90, 0.4);
  background: rgba(255, 255, 255, 0.1);
  color: #fbf3df;
  font: inherit;
  font-size: 13px;
}

.sync.warn {
  background: rgba(176, 84, 58, 0.6);
}

.sync.off {
  background: rgba(185, 130, 39, 0.45);
}

.body {
  margin-top: -14px;
  position: relative;
}

.field-card {
  padding: 16px;
}

.field-top {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 10px;
}

.field-name {
  font-size: 19px;
}

.stage {
  flex-shrink: 0;
  padding: 3px 12px;
  border-radius: 12px;
  background: var(--wheat-soft);
  border: 1px solid #ead5a8;
  color: #7d5814;
  font-family: var(--font-serif);
  font-weight: 700;
  font-size: 14px;
}

.progress-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 14px;
  font-size: 13px;
}

.bar {
  flex: 1;
}

.facts {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 6px;
  margin-top: 12px;
}

.facts div {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 8px 4px;
  border-radius: 10px;
  background: var(--surface-2);
  border: 1px solid var(--line);
}

.facts b {
  font-family: var(--font-serif);
  font-size: 19px;
  color: var(--forest);
}

.facts span {
  font-size: 11.5px;
  color: var(--ink-3);
}

.facts .danger b {
  color: var(--clay);
}

.loading {
  margin-top: 14px;
}

.error {
  margin-top: 12px;
  font-size: 13px;
  color: var(--clay);
}

.go {
  margin: 16px 0 6px;
}

.section-title {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin: 18px 2px 10px;
  font-size: 16px;
}

.section-title .m-muted {
  font-family: var(--font-sans);
  font-weight: normal;
}

.empty {
  padding: 8px 2px;
}

.entry {
  padding: 12px 14px;
  margin-bottom: 8px;
}

.entry-main {
  display: flex;
  justify-content: space-between;
  gap: 8px;
}

.entry-main b {
  color: var(--forest);
}

.value {
  font-weight: 700;
  color: var(--green);
}

.entry-sub {
  margin-top: 4px;
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}
</style>
