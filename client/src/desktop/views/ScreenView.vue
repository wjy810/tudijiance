<script setup lang="ts">
// 数据大屏：实验田状态、本年度亩产排名、历年平均亩产、最新录入。每分钟自动刷新。
// 游客登录后只能看这一页；其他人可以从左侧菜单打开，也可以切换实验田
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import type { EChartsCoreOption } from 'echarts/core'

import { fieldApi, statsApi, type Field, type PlotStatusCode, type Screen } from '@/shared/api'
import { PLOT_STATUS } from '@/shared/labels'
import { DARK, FONT, axisStyle, formatNumber, formatPercent, tooltipStyle } from '@/shared/charts'
import { useAuthStore } from '@/stores/auth'
import EChart from '@/desktop/components/EChart.vue'

const router = useRouter()
const auth = useAuthStore()
const t = DARK

const screen = ref<Screen | null>(null)
const fields = ref<Field[]>([])
const fieldId = ref<number | null>(null)
const error = ref('')
const now = ref(new Date())

// 深色背景上的小区状态色：和平面图同一套含义，只是调暗
const STATUS_DARK: Record<PlotStatusCode, string> = {
  NONE: '#2c2c2a',
  GROWING: '#1f5c3a',
  ABNORMAL: '#8a2b2b',
  HARVESTED: '#7a5d12',
  GUARD: '#353533',
  PATH: '#232321',
}

async function load() {
  try {
    screen.value = await statsApi.screen(fieldId.value ?? undefined)
    error.value = ''
  } catch (e) {
    error.value = (e as Error).message
  }
}

let refreshTimer: number | undefined
let clockTimer: number | undefined

onMounted(async () => {
  if (!auth.isGuest) {
    fields.value = await fieldApi.list().catch(() => [])
  }
  await load()
  fieldId.value = fields.value.find((f) => f.name === screen.value?.fieldName)?.id ?? null
  refreshTimer = window.setInterval(load, 60_000)
  clockTimer = window.setInterval(() => (now.value = new Date()), 1000)
})

onBeforeUnmount(() => {
  window.clearInterval(refreshTimer)
  window.clearInterval(clockTimer)
})

function changeField(id: number) {
  fieldId.value = id
  load()
}

function leave() {
  if (auth.isGuest) {
    auth.logout()
    router.replace({ name: 'login' })
  } else {
    router.push('/')
  }
}

const clock = computed(() => {
  const d = now.value
  const pad = (n: number) => String(n).padStart(2, '0')
  const week = '日一二三四五六'[d.getDay()]
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} 星期${week} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
})

const tiles = computed(() => {
  const i = screen.value?.indicators
  if (!i) {
    return []
  }
  return [
    { label: '试验小区', value: String(i.totalPlots) },
    { label: '已种植', value: String(i.planted) },
    { label: '今天已录', value: String(i.enteredToday) },
    { label: '本周录入', value: String(i.weekEntries), unit: '条' },
    { label: '有异常', value: String(i.abnormal), alert: i.abnormal > 0 },
    { label: '已收获', value: String(i.harvested) },
    { label: '当前生育期', value: i.currentStage ?? '—' },
  ]
})

const showText = computed(() => (screen.value?.colCount ?? 0) <= 20 && (screen.value?.rowCount ?? 0) <= 16)

const legend = (Object.keys(PLOT_STATUS) as PlotStatusCode[]).map((k) => ({
  code: k, label: PLOT_STATUS[k].label, color: STATUS_DARK[k],
}))

const statusCounts = computed(() => {
  const counts = new Map<PlotStatusCode, number>()
  for (const p of screen.value?.plots ?? []) {
    counts.set(p.status, (counts.get(p.status) ?? 0) + 1)
  }
  return counts
})

// ---------- 本年度折合亩产排名 ----------

const yieldRows = computed(() => (screen.value?.yieldRows ?? []).slice(0, 10))

const barOption = computed<EChartsCoreOption>(() => {
  const rows = [...yieldRows.value].reverse()
  return {
    animationDuration: 800,
    textStyle: { fontFamily: FONT },
    grid: { left: 8, right: 96, top: 4, bottom: 4, containLabel: true },
    tooltip: {
      trigger: 'item',
      ...tooltipStyle(t),
      formatter: (p: { dataIndex: number }) => {
        const r = rows[p.dataIndex]!
        return `<b>${r.varietyName}</b><br>折合亩产 ${formatNumber(r.value, 1)} kg<br>`
          + (r.control ? '对照品种' : r.vsCkPercent === null ? '' : `比对照 ${formatPercent(r.vsCkPercent)}`)
      },
    },
    xAxis: { type: 'value', show: false },
    yAxis: {
      type: 'category',
      data: rows.map((r) => (r.control ? `${r.varietyName}（CK）` : r.varietyName)),
      ...axisStyle(t),
      axisLine: { show: false },
      axisLabel: { color: t.textSecondary, fontFamily: FONT, fontSize: 13 },
      splitLine: { show: false },
    },
    series: [{
      type: 'bar',
      barMaxWidth: 16,
      data: rows.map((r) => ({
        value: r.value,
        itemStyle: { color: r.control ? t.reference : t.series[0], borderRadius: [0, 4, 4, 0] },
      })),
      label: {
        show: true,
        position: 'right',
        color: t.textSecondary,
        fontFamily: FONT,
        formatter: (p: { dataIndex: number }) => {
          const r = rows[p.dataIndex]!
          const pct = r.control ? '' : formatPercent(r.vsCkPercent)
          return `${formatNumber(r.value, 1)}${pct ? '  ' + pct : ''}`
        },
      },
    }],
  }
})

// ---------- 历年平均亩产 ----------

const trendOption = computed<EChartsCoreOption>(() => {
  const trend = screen.value?.trend ?? []
  return {
    animationDuration: 1200,
    textStyle: { fontFamily: FONT },
    grid: { left: 8, right: 24, top: 24, bottom: 8, containLabel: true },
    tooltip: {
      trigger: 'axis',
      ...tooltipStyle(t),
      axisPointer: { type: 'line', lineStyle: { color: t.axis } },
      valueFormatter: (v: number) => `${formatNumber(v, 1)} kg`,
    },
    xAxis: { type: 'category', data: trend.map((p) => String(p.year)), boundaryGap: false, ...axisStyle(t), splitLine: { show: false } },
    yAxis: { type: 'value', scale: true, name: 'kg', ...axisStyle(t) },
    series: [{
      type: 'line',
      name: '平均折合亩产',
      data: trend.map((p) => p.value),
      symbol: 'circle',
      symbolSize: 8,
      lineStyle: { width: 2, color: t.series[2] },
      itemStyle: { color: t.series[2], borderColor: t.surface, borderWidth: 2 },
      areaStyle: { color: t.series[2], opacity: 0.1 },
      // 只标最后一年
      label: {
        show: true,
        position: 'top',
        color: t.textSecondary,
        fontFamily: FONT,
        formatter: (p: { dataIndex: number; value: number }) => (p.dataIndex === trend.length - 1 ? formatNumber(p.value, 1) : ''),
      },
    }],
  }
})
</script>

<template>
  <div class="screen">
    <header class="head">
      <div class="title">
        <div class="name">小麦品种试验田数据大屏</div>
        <div class="sub">
          {{ screen?.fieldName ?? '—' }}
          <template v-if="screen?.trialName"> · {{ screen.seasonYear }} 年 · {{ screen.trialName }}</template>
        </div>
      </div>
      <div class="head-right">
        <el-select
          v-if="!auth.isGuest && fields.length > 1"
          :model-value="fieldId"
          size="small"
          class="field-select"
          popper-class="screen-popper"
          @update:model-value="changeField"
        >
          <el-option v-for="f in fields" :key="f.id" :value="f.id" :label="f.name" />
        </el-select>
        <span class="clock">{{ clock }}</span>
        <button class="leave" type="button" @click="leave">{{ auth.isGuest ? '退出' : '返回' }}</button>
      </div>
    </header>

    <div v-if="error" class="error">{{ error }}</div>
    <div v-else-if="screen && !screen.fieldName" class="error">还没有实验田</div>

    <template v-if="screen && screen.fieldName">
      <section class="tiles">
        <div v-for="tile in tiles" :key="tile.label" class="tile">
          <div class="tile-label">{{ tile.label }}</div>
          <div class="tile-value">
            <span v-if="tile.alert" class="alert-icon" aria-hidden="true">!</span>{{ tile.value }}<span v-if="tile.unit" class="tile-unit">{{ tile.unit }}</span>
          </div>
        </div>
        <div v-if="tiles.length === 0" class="tile wide">
          <div class="tile-label">这块地还没有试验</div>
        </div>
      </section>

      <section class="body">
        <div class="panel map-panel">
          <div class="panel-title">小区状态</div>
          <div
            class="map"
            :style="{
              gridTemplateColumns: `repeat(${screen.colCount}, minmax(0, 1fr))`,
              gridTemplateRows: `repeat(${screen.rowCount}, minmax(0, 1fr))`,
            }"
          >
            <div
              v-for="p in screen.plots"
              :key="p.plotId"
              class="cell"
              :style="{ gridRow: p.rowNo, gridColumn: p.colNo, background: STATUS_DARK[p.status] }"
              :title="`${p.code ?? ''} ${p.varietyName ?? ''} · ${PLOT_STATUS[p.status].label}`"
            >
              <template v-if="showText">
                <span class="cell-code">{{ p.code }}</span>
                <span class="cell-variety">{{ p.varietyName }}</span>
              </template>
            </div>
          </div>
          <div class="legend">
            <span v-for="l in legend" :key="l.code" class="legend-item">
              <i :style="{ background: l.color }" />{{ l.label }}
              <b>{{ statusCounts.get(l.code) ?? 0 }}</b>
            </span>
          </div>
        </div>

        <div class="side">
          <div class="panel">
            <div class="panel-title">{{ screen.yieldYear ? `${screen.yieldYear} 年` : '' }}各品种折合亩产（kg）</div>
            <div v-if="yieldRows.length === 0" class="empty">还没有产量数据</div>
            <EChart v-else :option="barOption" :height="`${Math.max(140, yieldRows.length * 30 + 16)}px`" />
          </div>
          <div class="panel">
            <div class="panel-title">历年平均折合亩产（kg）</div>
            <div v-if="screen.trend.length === 0" class="empty">还没有产量数据</div>
            <EChart v-else :option="trendOption" height="200px" />
          </div>
        </div>
      </section>

      <section class="panel recent">
        <div class="panel-title">最新录入</div>
        <div v-if="screen.recent.length === 0" class="empty">还没有录入</div>
        <div v-for="(r, i) in screen.recent" :key="i" class="recent-row">
          <span class="recent-time">{{ r.at.replace('T', ' ').slice(5, 16) }}</span>
          <span>{{ r.user }}</span>
          <span>{{ r.plotCount > 1 ? `${r.plotCount} 个小区` : `小区 ${r.plotCode}` }}</span>
          <span class="recent-trait">{{ r.traitName }}</span>
          <span class="recent-value">{{ r.value }}</span>
        </div>
      </section>

      <footer class="foot">数据每分钟自动刷新 · 上次更新 {{ screen.generatedAt.replace('T', ' ').slice(11, 19) }}</footer>
    </template>
  </div>
</template>

<style scoped>
.screen {
  min-height: 100%;
  box-sizing: border-box;
  background: #0d0d0d;
  color: #f0efec;
  padding: 16px 24px 12px;
  display: flex;
  flex-direction: column;
  gap: 14px;
  font-family: system-ui, -apple-system, 'Segoe UI', 'Microsoft YaHei', 'PingFang SC', sans-serif;
}

.head {
  display: flex;
  justify-content: space-between;
  align-items: flex-end;
  border-bottom: 1px solid #2c2c2a;
  padding-bottom: 12px;
}

.name {
  font-size: 26px;
  font-weight: 600;
  letter-spacing: 1px;
}

.sub {
  color: #c3c2b7;
  margin-top: 4px;
}

.head-right {
  display: flex;
  align-items: center;
  gap: 16px;
}

.field-select {
  width: 200px;
}

.field-select :deep(.el-select__wrapper) {
  background: #1a1a19;
  box-shadow: 0 0 0 1px #383835 inset;
}

.field-select :deep(.el-select__selected-item) {
  color: #f0efec;
}

.clock {
  color: #c3c2b7;
  font-variant-numeric: tabular-nums;
}

.leave {
  background: transparent;
  color: #c3c2b7;
  border: 1px solid #383835;
  border-radius: 6px;
  padding: 4px 14px;
  cursor: pointer;
}

.leave:hover {
  color: #f0efec;
  border-color: #898781;
}

.error,
.empty {
  color: #898781;
  padding: 24px 0;
  text-align: center;
}

.tiles {
  display: grid;
  grid-template-columns: repeat(7, minmax(0, 1fr));
  gap: 12px;
}

.tile {
  background: #1a1a19;
  border: 1px solid rgba(255, 255, 255, 0.1);
  border-radius: 8px;
  padding: 12px 16px;
}

.tile.wide {
  grid-column: 1 / -1;
}

.tile-label {
  color: #c3c2b7;
  font-size: 13px;
}

.tile-value {
  font-size: 30px;
  font-weight: 600;
  margin-top: 4px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.tile-unit {
  font-size: 14px;
  color: #c3c2b7;
  margin-left: 4px;
  font-weight: normal;
}

.alert-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  border-radius: 50%;
  background: #d03b3b;
  color: #fff;
  font-size: 15px;
  margin-right: 8px;
  vertical-align: middle;
}

.body {
  display: grid;
  grid-template-columns: minmax(0, 3fr) minmax(360px, 2fr);
  gap: 14px;
  flex: 1;
  min-height: 0;
}

.panel {
  background: #1a1a19;
  border: 1px solid rgba(255, 255, 255, 0.1);
  border-radius: 8px;
  padding: 12px 16px;
}

.panel-title {
  font-weight: 600;
  margin-bottom: 10px;
}

.map-panel {
  display: flex;
  flex-direction: column;
}

.map {
  display: grid;
  gap: 3px;
  flex: 1;
  min-height: 320px;
}

.cell {
  border-radius: 3px;
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: center;
  overflow: hidden;
  padding: 2px;
  min-height: 0;
}

.cell-code {
  font-size: 12px;
  color: #f0efec;
}

.cell-variety {
  font-size: 11px;
  color: #c3c2b7;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 100%;
}

.legend {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
  margin-top: 10px;
  color: #c3c2b7;
  font-size: 13px;
}

.legend-item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.legend-item i {
  width: 14px;
  height: 10px;
  border-radius: 2px;
  border: 1px solid rgba(255, 255, 255, 0.15);
}

.legend-item b {
  color: #f0efec;
  font-weight: 600;
}

.side {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.recent-row {
  display: grid;
  grid-template-columns: 100px 90px 110px 160px 1fr;
  gap: 12px;
  padding: 6px 0;
  border-top: 1px solid #2c2c2a;
  color: #c3c2b7;
}

.recent-time {
  font-variant-numeric: tabular-nums;
}

.recent-trait {
  color: #f0efec;
}

.recent-value {
  color: #f0efec;
  font-weight: 600;
}

.foot {
  color: #898781;
  font-size: 12px;
  text-align: right;
}
</style>
