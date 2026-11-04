<script setup lang="ts">
// 数据大屏：实验田状态、本年度亩产排名、历年平均亩产、最新录入。每分钟自动刷新。
// 游客登录后只能看这一页；其他人可以从左侧菜单打开，也可以切换实验田
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import type { EChartsCoreOption } from 'echarts/core'
import { CircleCheck, EditPen, Goods, Grid, Notebook, Sunrise, Warning } from '@element-plus/icons-vue'

import { fieldApi, statsApi, type Field, type PlotStatusCode, type Screen } from '@/shared/api'
import { PLOT_STATUS } from '@/shared/labels'
import { DARK, FONT, axisStyle, formatNumber, formatPercent, tooltipStyle } from '@/shared/charts'
import { useAuthStore } from '@/stores/auth'
import EChart from '@/desktop/components/EChart.vue'
import BlockMiniMap from '@/desktop/components/BlockMiniMap.vue'
import bgArt from '@/assets/art/screen-bg.webp'
import appIcon from '@/assets/art/app-icon.webp'

const router = useRouter()
const auth = useAuthStore()
const t = DARK

const screen = ref<Screen | null>(null)
const fields = ref<Field[]>([])
const fieldId = ref<number | null>(null)
const error = ref('')
const now = ref(new Date())

// 深色背景上的小区状态色：和平面图同一套含义，换成夜里看得清的祖母绿、麦金、陶土
const STATUS_DARK: Record<PlotStatusCode, string> = {
  NONE: '#26352c',
  GROWING: '#2c7550',
  ABNORMAL: '#a3472f',
  HARVESTED: '#b8892f',
  GUARD: '#2f3a2c',
  PATH: '#1b2620',
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
    { label: '试验小区', value: String(i.totalPlots), unit: '个', icon: Grid },
    { label: '已种植', value: String(i.planted), unit: '个', icon: CircleCheck },
    { label: '今天已录', value: String(i.enteredToday), unit: '个', icon: EditPen },
    { label: '本周录入', value: String(i.weekEntries), unit: '条', icon: Notebook },
    { label: '有异常', value: String(i.abnormal), unit: '个', alert: i.abnormal > 0, icon: Warning },
    { label: '已收获', value: String(i.harvested), unit: '个', icon: Goods },
    { label: '当前生育期', value: i.currentStage ?? '—', icon: Sunrise, wide: true },
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
        itemStyle: {
          color: r.control
            ? t.reference
            : { type: 'linear', x: 0, y: 0, x2: 1, y2: 0, colorStops: [{ offset: 0, color: 'rgba(226,182,90,0.25)' }, { offset: 1, color: '#e9c46a' }] },
          borderRadius: [0, 6, 6, 0],
        },
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
      lineStyle: { width: 2.5, color: t.series[0], shadowColor: 'rgba(226,182,90,0.6)', shadowBlur: 10 },
      itemStyle: { color: t.series[0], borderColor: '#0f1d16', borderWidth: 2 },
      areaStyle: {
        color: { type: 'linear', x: 0, y: 0, x2: 0, y2: 1, colorStops: [{ offset: 0, color: 'rgba(226,182,90,0.35)' }, { offset: 1, color: 'rgba(226,182,90,0)' }] },
      },
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

// ---------- 小区状态占比（环形图） ----------

const statusOption = computed<EChartsCoreOption>(() => {
  const data = (['GROWING', 'HARVESTED', 'ABNORMAL', 'NONE'] as PlotStatusCode[])
    .map((k) => ({ name: PLOT_STATUS[k].label, value: statusCounts.value.get(k) ?? 0, itemStyle: { color: STATUS_DARK[k] } }))
    .filter((d) => d.value > 0)
  const total = data.reduce((n, d) => n + d.value, 0)
  return {
    textStyle: { fontFamily: FONT },
    tooltip: { trigger: 'item', ...tooltipStyle(t), formatter: '{b}：{c} 个（{d}%）' },
    legend: { orient: 'vertical', right: 6, top: 'middle', icon: 'roundRect', textStyle: { color: t.textSecondary, fontFamily: FONT } },
    title: {
      text: String(total), subtext: '试验小区', left: '33%', top: '38%', textAlign: 'center',
      textStyle: { color: '#f3d58c', fontSize: 26, fontFamily: "'Noto Serif SC', serif", fontWeight: 700 },
      subtextStyle: { color: t.textMuted, fontSize: 12 },
    },
    series: [{
      type: 'pie', radius: ['56%', '78%'], center: ['34%', '50%'], data,
      label: { show: false }, itemStyle: { borderColor: '#0f1d16', borderWidth: 3 },
    }],
  }
})
</script>

<template>
  <div class="screen" :style="{ backgroundImage: `url(${bgArt})` }">
    <header class="head">
      <div class="head-side left">
        <img :src="appIcon" alt="" class="logo" />
        <div class="where">
          <div>{{ screen?.fieldName ?? '—' }}</div>
          <div v-if="screen?.trialName" class="muted">{{ screen.seasonYear }} 年 · {{ screen.trialName }}</div>
        </div>
      </div>
      <div class="head-center">
        <div class="name">小麦品种试验田数据大屏</div>
        <div class="slogan">良种为先 · 数据为证</div>
      </div>
      <div class="head-side right">
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
        <div v-for="tile in tiles" :key="tile.label" class="tile frame" :class="{ alert: tile.alert, wide: tile.wide }">
          <span class="tile-icon"><el-icon><component :is="tile.icon" /></el-icon></span>
          <div>
            <div class="tile-label">{{ tile.label }}</div>
            <div class="tile-value">{{ tile.value }}<span v-if="tile.unit" class="tile-unit">{{ tile.unit }}</span></div>
          </div>
        </div>
        <div v-if="tiles.length === 0" class="tile frame wide">
          <div class="tile-label">这块地还没有试验</div>
        </div>
      </section>

      <section class="body">
        <div class="frame map-panel">
          <div class="frame-title">试验田小区分布</div>
          <div class="map-wrap">
            <BlockMiniMap
              v-if="screen.layout"
              class="block-map"
              :layout="screen.layout"
              :plots="screen.plots"
              :colors="STATUS_DARK"
              :labels="showText ? 'full' : 'none'"
              theme="dark"
            />
            <div
              v-else
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
                :class="{ alert: p.status === 'ABNORMAL' }"
                :style="{ gridRow: p.rowNo, gridColumn: p.colNo, background: STATUS_DARK[p.status] }"
                :title="`${p.code ?? ''} ${p.varietyName ?? ''} · ${PLOT_STATUS[p.status].label}`"
              >
                <template v-if="showText">
                  <span class="cell-code">{{ p.code }}</span>
                  <span class="cell-variety">{{ p.varietyName }}</span>
                </template>
              </div>
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
          <div class="frame">
            <div class="frame-title">{{ screen.yieldYear ? `${screen.yieldYear} 年` : '' }}各品种折合亩产<span>kg</span></div>
            <div v-if="yieldRows.length === 0" class="empty">还没有产量数据</div>
            <EChart v-else :option="barOption" :height="`${Math.max(140, yieldRows.length * 28 + 16)}px`" />
          </div>
          <div class="side-row">
            <div class="frame">
              <div class="frame-title">历年平均折合亩产<span>kg</span></div>
              <div v-if="screen.trend.length === 0" class="empty">还没有产量数据</div>
              <EChart v-else :option="trendOption" height="180px" />
            </div>
            <div class="frame">
              <div class="frame-title">小区状态占比</div>
              <EChart :option="statusOption" height="180px" />
            </div>
          </div>
        </div>
      </section>

      <section class="frame recent">
        <div class="frame-title">实时录入</div>
        <div v-if="screen.recent.length === 0" class="empty">还没有录入</div>
        <div v-for="(r, i) in screen.recent" :key="i" class="recent-row">
          <span class="recent-time">{{ r.at.replace('T', ' ').slice(5, 16) }}</span>
          <span class="recent-user">{{ r.user }}</span>
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
  --gold: #e2b65a;
  --gold-soft: rgba(226, 182, 90, 0.22);
  --text: #f3ead2;
  --text-2: #c9bf9f;
  --text-3: #8d8670;
  min-height: 100%;
  box-sizing: border-box;
  padding: 0 26px 18px;
  color: var(--text);
  font-family: var(--font-sans);
  background-color: #0b1611;
  background-size: cover;
  background-position: center top;
  display: flex;
  flex-direction: column;
  gap: 14px;
}

/* ---------- 页头：中间标题，两边带金线 ---------- */
.head {
  display: grid;
  grid-template-columns: 1fr auto 1fr;
  align-items: center;
  padding-top: 10px;
}

.head-side {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 13px;
  color: var(--text-2);
}

.head-side.right {
  justify-content: flex-end;
}

.logo {
  width: 34px;
  height: 34px;
  border-radius: 9px;
  box-shadow: 0 0 0 1px var(--gold-soft);
}

.where .muted {
  color: var(--text-3);
  font-size: 12px;
}

.head-center {
  position: relative;
  text-align: center;
  padding: 12px 70px 14px;
}

/* 标题两边的金色斜线，像大屏的"眉" */
.head-center::before,
.head-center::after {
  content: '';
  position: absolute;
  bottom: 6px;
  width: 220px;
  height: 22px;
  border-bottom: 1px solid var(--gold-soft);
}

.head-center::before {
  right: 100%;
  border-right: 1px solid var(--gold-soft);
  transform: skewX(40deg);
  transform-origin: bottom right;
  background: linear-gradient(90deg, transparent, rgba(226, 182, 90, 0.08));
}

.head-center::after {
  left: 100%;
  border-left: 1px solid var(--gold-soft);
  transform: skewX(-40deg);
  transform-origin: bottom left;
  background: linear-gradient(270deg, transparent, rgba(226, 182, 90, 0.08));
}

.name {
  font-family: var(--font-serif);
  font-weight: 700;
  font-size: 32px;
  letter-spacing: 6px;
  background: linear-gradient(180deg, #fff3cf 0%, #e9c46a 60%, #b8892f 100%);
  -webkit-background-clip: text;
  background-clip: text;
  color: transparent;
  filter: drop-shadow(0 2px 10px rgba(226, 182, 90, 0.25));
}

.slogan {
  margin-top: 2px;
  font-size: 12px;
  letter-spacing: 10px;
  color: var(--text-3);
}

.clock {
  font-variant-numeric: tabular-nums;
  letter-spacing: 1px;
  color: var(--text-2);
}

.leave {
  background: rgba(226, 182, 90, 0.08);
  color: var(--text);
  border: 1px solid var(--gold-soft);
  border-radius: 6px;
  padding: 4px 14px;
  font: inherit;
  font-size: 13px;
  cursor: pointer;
}

.leave:hover {
  background: rgba(226, 182, 90, 0.18);
}

.field-select {
  width: 170px;
}

/* ---------- 带四个金色角标的面板 ---------- */
.frame {
  --c: rgba(226, 182, 90, 0.75);
  position: relative;
  padding: 14px 16px;
  border-radius: 4px;
  border: 1px solid rgba(226, 182, 90, 0.14);
  background:
    linear-gradient(var(--c), var(--c)) top left / 16px 2px no-repeat,
    linear-gradient(var(--c), var(--c)) top left / 2px 16px no-repeat,
    linear-gradient(var(--c), var(--c)) top right / 16px 2px no-repeat,
    linear-gradient(var(--c), var(--c)) top right / 2px 16px no-repeat,
    linear-gradient(var(--c), var(--c)) bottom left / 16px 2px no-repeat,
    linear-gradient(var(--c), var(--c)) bottom left / 2px 16px no-repeat,
    linear-gradient(var(--c), var(--c)) bottom right / 16px 2px no-repeat,
    linear-gradient(var(--c), var(--c)) bottom right / 2px 16px no-repeat,
    linear-gradient(180deg, rgba(20, 42, 31, 0.78), rgba(10, 24, 17, 0.85));
  box-shadow: inset 0 0 40px rgba(226, 182, 90, 0.04), 0 10px 30px -18px rgba(0, 0, 0, 0.8);
  backdrop-filter: blur(2px);
}

.frame-title {
  display: flex;
  align-items: baseline;
  gap: 8px;
  margin-bottom: 10px;
  padding-left: 14px;
  position: relative;
  font-family: var(--font-serif);
  font-weight: 700;
  font-size: 16px;
  letter-spacing: 2px;
  color: #f6e7bf;
}

.frame-title::before {
  content: '';
  position: absolute;
  left: 0;
  top: 50%;
  width: 7px;
  height: 7px;
  background: var(--gold);
  transform: translateY(-50%) rotate(45deg);
  box-shadow: 0 0 8px rgba(226, 182, 90, 0.8);
}

.frame-title span {
  font-family: var(--font-sans);
  font-weight: normal;
  font-size: 12px;
  color: var(--text-3);
  letter-spacing: 0;
}

/* ---------- 指标 ---------- */
.tiles {
  display: grid;
  grid-template-columns: repeat(6, minmax(0, 1fr)) minmax(0, 1.3fr);
  gap: 12px;
}

.tile {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 16px;
}

.tile-icon {
  width: 40px;
  height: 40px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 18px;
  color: var(--gold);
  background: radial-gradient(circle at 50% 35%, rgba(226, 182, 90, 0.25), rgba(226, 182, 90, 0.05));
  box-shadow: inset 0 0 0 1px rgba(226, 182, 90, 0.35);
  flex-shrink: 0;
}

.tile-label {
  font-size: 12.5px;
  color: var(--text-2);
}

.tile-value {
  margin-top: 2px;
  font-family: var(--font-serif);
  font-weight: 700;
  font-size: 30px;
  line-height: 1.1;
  color: #f3d58c;
  text-shadow: 0 0 18px rgba(226, 182, 90, 0.35);
  font-variant-numeric: tabular-nums;
}

.tile.wide .tile-value {
  font-size: 28px;
}

.tile-unit {
  margin-left: 4px;
  font-family: var(--font-sans);
  font-weight: normal;
  font-size: 12px;
  color: var(--text-3);
  text-shadow: none;
}

.tile.alert .tile-value,
.tile.alert .tile-icon {
  color: #ff9b7a;
}

/* ---------- 主体 ---------- */
.body {
  flex: 1;
  display: grid;
  grid-template-columns: minmax(0, 1.15fr) minmax(0, 1fr);
  gap: 14px;
  min-height: 0;
}

.map-panel {
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.map-wrap {
  flex: 1;
  min-height: 300px;
  position: relative;
  padding: 12px;
  border-radius: 4px;
  background: rgba(5, 12, 9, 0.55);
  box-shadow: inset 0 0 0 1px rgba(226, 182, 90, 0.1), inset 0 0 30px rgba(0, 0, 0, 0.5);
}

.map {
  display: grid;
  gap: 5px;
  height: 100%;
  min-height: 300px;
}

/* 区块布局按实际比例放在框里，不撑大框 */
.block-map {
  position: absolute;
  inset: 12px;
  width: calc(100% - 24px);
  height: calc(100% - 24px);
}

.cell {
  border-radius: 4px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 1px;
  min-width: 0;
  overflow: hidden;
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.12), inset 0 0 0 1px rgba(0, 0, 0, 0.25);
}

.cell.alert {
  box-shadow: inset 0 0 0 2px #ff9b7a, 0 0 12px rgba(255, 120, 80, 0.5);
}

.cell-code {
  font-size: 12.5px;
  font-weight: 700;
  color: #fbf3df;
}

.cell-variety {
  font-size: 11.5px;
  color: rgba(251, 243, 223, 0.75);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 94%;
}

.legend {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 18px;
  margin-top: 10px;
  font-size: 12.5px;
  color: var(--text-2);
}

.legend-item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.legend-item i {
  width: 12px;
  height: 12px;
  border-radius: 3px;
  box-shadow: inset 0 0 0 1px rgba(255, 255, 255, 0.15);
}

.legend-item b {
  color: #f3d58c;
  font-variant-numeric: tabular-nums;
}

.side {
  display: flex;
  flex-direction: column;
  gap: 14px;
  min-width: 0;
}

.side-row {
  display: grid;
  grid-template-columns: minmax(0, 1.2fr) minmax(0, 1fr);
  gap: 14px;
}

.empty {
  color: var(--text-3);
  font-size: 13px;
  padding: 24px 0;
  text-align: center;
}

/* ---------- 最新录入 ---------- */
.recent-row {
  display: grid;
  grid-template-columns: 120px 90px 110px 140px 1fr;
  gap: 12px;
  padding: 7px 4px;
  font-size: 13.5px;
  border-top: 1px solid rgba(226, 182, 90, 0.08);
}

.recent-time {
  color: var(--text-3);
  font-variant-numeric: tabular-nums;
}

.recent-user {
  color: var(--text);
}

.recent-trait {
  color: var(--text-2);
}

.recent-value {
  color: #f3d58c;
  font-weight: 600;
}

.foot {
  text-align: center;
  font-size: 12px;
  color: var(--text-3);
  letter-spacing: 1px;
}

.error {
  margin: 80px auto;
  color: var(--text-2);
  font-size: 16px;
}
</style>

<style>
/* 大屏上的下拉菜单也用深色 */
.screen-popper.el-popper {
  --el-bg-color-overlay: #132219;
  --el-border-color-light: rgba(226, 182, 90, 0.3);
  --el-fill-color-light: rgba(226, 182, 90, 0.12);
  --el-text-color-regular: #f3ead2;
}
</style>
