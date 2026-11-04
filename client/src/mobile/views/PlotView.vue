<script setup lang="ts">
// 小区详情：品种、重复、各项目最近一次的值，以及这台手机上还没传上去的录入；可以直接给这个小区录入
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import type { ObservationView, PlotDetail } from '@/shared/api'
import { OFFLINE_EDITION } from '@/shared/edition'
import { useAuthStore } from '@/stores/auth'
import { PLOT_STATUS, TRAIT_CATEGORY, replicateLabel } from '@/shared/labels'
import { repo } from '../repo'
import { db, type OutboxBatch } from '../db'
import { useFieldStore } from '../stores/field'
import { plotPlace } from '@/shared/fieldLayout'
import { plotCode, timeAgo } from '../display'
import EntrySheet from '../components/EntrySheet.vue'
import CorrectionSheet from '../components/CorrectionSheet.vue'

const route = useRoute()
const router = useRouter()
const field = useFieldStore()
const trialId = Number(route.params.trialId)
const plotId = Number(route.params.plotId)

const detail = ref<PlotDetail | null>(null)
const offlineAt = ref<number | null>(null)
const error = ref<string | null>(null)
const local = ref<OutboxBatch[]>([])
const entryOpen = ref(false)

async function load() {
  try {
    const r = await repo.plotDetail(trialId, plotId)
    detail.value = r.data
    offlineAt.value = r.offline ? r.savedAt : null
    error.value = null
  } catch (e) {
    error.value = (e as Error).message
  }
  local.value = (await db.outbox.where('trialId').equals(trialId).toArray())
    .filter((b) => (b.status === 'pending' || b.status === 'failed') && b.plots.some((p) => p.plotId === plotId))
    .sort((a, b) => b.createdAt - a.createdAt)
}
onMounted(load)

const plot = computed(() => detail.value?.plot ?? field.plots.find((p) => p.id === plotId) ?? null)
const status = computed(() => field.statusByPlot.get(plotId)?.status ?? 'NONE')
const groups = computed(() => {
  const map = new Map<string, PlotDetail['latest']>()
  for (const v of detail.value?.latest ?? []) {
    const k = TRAIT_CATEGORY[v.category]
    map.set(k, [...(map.get(k) ?? []), v])
  }
  return [...map.entries()]
})

async function onSaved() {
  await load()
}

// ---------- 学生申请更正（要联网；老师在电脑上审核） ----------
const auth = useAuthStore()
const canCorrect = computed(() => !OFFLINE_EDITION && auth.role === 'STUDENT' && !offlineAt.value && !!detail.value)
const correctOpen = ref(false)
const correcting = ref<ObservationView[]>([])

function recordsOf(traitId: number): ObservationView[] {
  return (detail.value?.records ?? [])
    .filter((r) => r.traitId === traitId)
    .sort((a, b) => b.observedOn.localeCompare(a.observedOn) || b.recordedAt.localeCompare(a.recordedAt))
}

function requestCorrection(traitId: number) {
  correcting.value = recordsOf(traitId)
  if (correcting.value.length) correctOpen.value = true
}
</script>

<template>
  <div class="plot-page">
    <van-nav-bar :title="plot ? `小区 ${plotCode(plot)}` : '小区'" left-arrow fixed placeholder safe-area-inset-top
      @click-left="router.back()" />

    <div class="m-page body">
      <section v-if="plot" class="m-card head">
        <div class="code serif">{{ plotCode(plot) }}</div>
        <div class="info">
          <div class="variety">{{ detail?.variety?.name ?? '未分配品种' }}<span v-if="detail?.variety?.controlType" class="ck">CK</span></div>
          <div class="m-muted">
            {{ plotPlace(field.blockLayout, plot.id) ?? `第 ${plot.rowNo} 行第 ${plot.colNo} 列` }}
            <template v-if="detail?.replicateNo"> · 重复 {{ replicateLabel(detail.replicateNo) }}</template>
          </div>
          <span class="status" :style="{ background: PLOT_STATUS[status].color }">{{ PLOT_STATUS[status].label }}</span>
          <div v-if="detail?.abnormal" class="abnormal">异常：{{ detail.abnormalNote || '未写原因' }}</div>
        </div>
      </section>

      <van-notice-bar v-if="offlineAt" :text="`没有网络，显示的是${timeAgo(offlineAt)}的数据`" wrapable :scrollable="false" class="offline" />
      <div v-if="error" class="error">{{ error }}</div>

      <section v-if="local.length" class="block">
        <div class="block-title m-title">还没传上去的</div>
        <div v-for="b in local" :key="b.id" class="row m-card">
          <span>{{ b.traitName }}</span><b>{{ b.displayValue }}</b>
          <van-tag :type="b.status === 'failed' ? 'danger' : 'warning'" plain round>{{ b.status === 'failed' ? '没传上去' : '待上传' }}</van-tag>
        </div>
      </section>

      <section v-for="[cat, items] in groups" :key="cat" class="block">
        <div class="block-title m-title">{{ cat }}</div>
        <div class="m-card list">
          <div v-for="v in items" :key="v.traitId" class="line">
            <span class="name">{{ v.traitName }}<i v-if="v.computed" class="calc">算</i></span>
            <b class="num">{{ v.displayValue }}</b>
            <span class="m-muted date">{{ v.observedOn?.slice(5) ?? '' }}</span>
            <button v-if="canCorrect && !v.computed && recordsOf(v.traitId).length" type="button" class="fix"
              :aria-label="`申请更正${v.traitName}`" @click="requestCorrection(v.traitId)">更正</button>
          </div>
        </div>
      </section>
      <div v-if="detail && detail.latest.length === 0 && !local.length" class="m-muted none">这个小区还没有录过数据</div>
    </div>

    <div class="foot">
      <van-button block round size="large" class="btn-wheat" icon="edit" :disabled="!plot || plot.kind !== 'PLOT'" @click="entryOpen = true">给这个小区录入</van-button>
    </div>
    <EntrySheet v-if="plot" v-model:show="entryOpen" :plots="[plot]" @saved="onSaved" />
    <CorrectionSheet v-model:show="correctOpen" :observations="correcting" @submitted="load" />
  </div>
</template>

<style scoped>
.plot-page {
  min-height: 100vh;
}

.body {
  padding-bottom: calc(96px + env(safe-area-inset-bottom));
}

.head {
  display: flex;
  gap: 16px;
  padding: 16px;
}

.code {
  min-width: 72px;
  height: 72px;
  padding: 0 8px;
  border-radius: 16px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 26px;
  font-weight: 700;
  color: var(--forest);
  background: var(--green-soft);
  border: 1px solid #c9dccf;
}

.info {
  display: flex;
  flex-direction: column;
  gap: 4px;
  align-items: flex-start;
}

.variety {
  font-size: 18px;
  font-weight: 700;
  color: var(--forest);
  display: flex;
  align-items: center;
  gap: 6px;
}

.ck {
  padding: 0 5px;
  border-radius: 5px;
  background: #d9a748;
  color: #3b2a0c;
  font-size: 11px;
  line-height: 18px;
}

.status {
  margin-top: 2px;
  padding: 1px 10px;
  border-radius: 10px;
  font-size: 12.5px;
  border: 1px solid rgba(90, 70, 30, 0.15);
}

.abnormal {
  font-size: 13px;
  color: var(--clay);
}

.offline {
  margin-top: 10px;
  border-radius: 10px;
}

.error {
  margin-top: 12px;
  color: var(--clay);
  font-size: 13px;
}

.block {
  margin-top: 16px;
}

.block-title {
  font-size: 15px;
  margin: 0 2px 8px;
}

.list {
  padding: 4px 14px;
}

.line {
  display: flex;
  align-items: baseline;
  gap: 8px;
  padding: 10px 0;
  border-bottom: 1px dashed var(--line);
}

.line:last-child {
  border-bottom: none;
}

.name {
  flex: 1;
  color: var(--ink-2);
}

.calc {
  margin-left: 4px;
  font-style: normal;
  font-size: 10px;
  padding: 0 4px;
  border-radius: 4px;
  background: var(--sky-soft);
  color: var(--sky);
}

.line b {
  color: var(--forest);
}

.date {
  width: 40px;
  text-align: right;
  font-size: 12px;
}

.fix {
  align-self: center;
  padding: 3px 9px;
  border-radius: 12px;
  border: 1px solid var(--line-strong);
  background: var(--surface);
  font: inherit;
  font-size: 12px;
  color: var(--ink-2);
}

.row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 14px;
  margin-bottom: 6px;
}

.row span:first-child {
  flex: 1;
}

.row b {
  color: var(--green);
}

.none {
  text-align: center;
  padding: 30px;
}

.foot {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  padding: 10px 14px calc(10px + env(safe-area-inset-bottom));
  background: rgba(255, 253, 248, 0.96);
  border-top: 1px solid var(--line);
}
</style>
