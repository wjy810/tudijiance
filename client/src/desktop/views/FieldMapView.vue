<script setup lang="ts">
// 实验田平面图（第一期的主界面）：上面是操作栏和状态指标，中间是小区网格。
// 小区颜色表示状态；右键小区弹出菜单，双击打开详情；选中多个小区可以一起录入数据
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  ArrowLeft, Download, EditPen, Plus, Rank, Setting, Warning, ZoomIn, ZoomOut,
} from '@element-plus/icons-vue'

import {
  deviceApi, fieldApi, observationApi, trialApi,
  type DeviceView, type FieldDetail, type Layout, type Plot, type PlotStatusCode, type Trial, type TrialStatus,
} from '@/shared/api'
import { PLOT_KIND, PLOT_STATUS, replicateLabel } from '@/shared/labels'
import { rememberTrial } from '@/shared/composables/useTrialPicker'
import { useAuthStore } from '@/stores/auth'
import { useCatalogStore } from '@/stores/catalog'
import PlotGrid from '@/desktop/components/PlotGrid.vue'
import type { GridCell, GridDevice, GridLink } from '@/desktop/components/plotGrid'
import FieldFormDialog from '@/desktop/components/FieldFormDialog.vue'
import TrialFormDialog from '@/desktop/components/TrialFormDialog.vue'
import EntryDialog from '@/desktop/components/EntryDialog.vue'
import PlotDetailDrawer from '@/desktop/components/PlotDetailDrawer.vue'
import AssignVarietyDialog from '@/desktop/components/AssignVarietyDialog.vue'
import ControlLinkDialog from '@/desktop/components/ControlLinkDialog.vue'
import NumberingDialog from '@/desktop/components/NumberingDialog.vue'
import PlotSettingsDialog from '@/desktop/components/PlotSettingsDialog.vue'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const catalog = useCatalogStore()

const fieldId = computed(() => Number(route.params.id))
const detail = ref<FieldDetail | null>(null)
const trials = ref<Trial[]>([])
const trialId = ref<number | null>(null)
const layout = ref<Layout | null>(null)
const status = ref<TrialStatus | null>(null)
const devices = ref<DeviceView[]>([])
const selected = ref<number[]>([])
const loading = ref(false)

const trial = computed(() => trials.value.find((t) => t.id === trialId.value) ?? null)
const plots = computed(() => detail.value?.plots ?? [])
const plotById = computed(() => new Map(plots.value.map((p) => [p.id, p])))
const plantingByPlot = computed(() => new Map((layout.value?.plantings ?? []).map((p) => [p.plotId, p])))
const statusByPlot = computed(() => new Map((status.value?.plots ?? []).map((p) => [p.plotId, p])))
const selectedPlots = computed(() => selected.value.map((id) => plotById.value.get(id)).filter((p): p is Plot => !!p))
const selectedTrialPlots = computed(() => selectedPlots.value.filter((p) => p.kind === 'PLOT'))

// ---------- 读取数据 ----------

async function loadField() {
  detail.value = await fieldApi.detail(fieldId.value)
}

async function loadTrials(preferred: number | null) {
  const list = await trialApi.list(fieldId.value)
  trials.value = [...list].sort((a, b) => b.seasonYear - a.seasonYear || b.id - a.id)
  trialId.value = trials.value.find((t) => t.id === preferred)?.id ?? trials.value[0]?.id ?? null
}

async function loadTrialData() {
  if (!trialId.value) {
    layout.value = null
    status.value = null
    return
  }
  const id = trialId.value
  const [l, s] = await Promise.all([trialApi.layout(id), observationApi.status(id)])
  if (id === trialId.value) {
    layout.value = l
    status.value = s
  }
}

async function refreshStatus() {
  if (trialId.value) {
    status.value = await observationApi.status(trialId.value)
  }
}

// 初次加载时 trialId 由 load() 设置，不需要 watch 再加载一遍
let initializing = false

async function load() {
  loading.value = true
  initializing = true
  try {
    await Promise.all([
      loadField(),
      loadTrials(Number(route.query.trial) || null),
      catalog.loadVarieties(),
    ])
    await loadTrialData()
    // 传感器是第二期的内容，读不到不影响平面图
    devices.value = await deviceApi.list(fieldId.value).catch(() => [])
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    initializing = false
    loading.value = false
  }
}

watch(trialId, async (id) => {
  if (initializing) {
    return
  }
  router.replace({ query: { ...route.query, trial: id ?? undefined } })
  rememberTrial(fieldId.value, id)
  try {
    await loadTrialData()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
})

onMounted(async () => {
  await load()
  rememberTrial(fieldId.value, trialId.value)
})

// ---------- 显示方式 ----------

type ViewMode = 'status' | 'variety' | 'replicate'
interface Display {
  mode: ViewMode
  cellW: number
  cellH: number
  /** 按小区实际的长、宽显示大小差别 */
  actualSize: boolean
  /** 一直显示对照虚线；不勾时只在选中相关小区时显示 */
  showAllLinks: boolean
}

const DISPLAY_KEY = 'tudijiance.mapDisplay'

function loadDisplay(): Display {
  const fallback: Display = { mode: 'status', cellW: 104, cellH: 70, actualSize: false, showAllLinks: false }
  try {
    return { ...fallback, ...JSON.parse(localStorage.getItem(DISPLAY_KEY) || '{}') }
  } catch {
    return fallback
  }
}

const display = ref<Display>(loadDisplay())
watch(display, (d) => {
  try {
    localStorage.setItem(DISPLAY_KEY, JSON.stringify(d))
  } catch {
    // 不记忆
  }
}, { deep: true })

const zoom = ref(1)
const panning = ref(false)

function zoomBy(step: number) {
  zoom.value = Math.min(2.5, Math.max(0.3, Math.round((zoom.value + step) * 10) / 10))
}

const VARIETY_COLORS = ['#cfe8d5', '#d6e4f5', '#f5e3c8', '#e6d9f2', '#f6d5d5', '#d3eeec', '#ecebc5', '#f2dcea',
  '#dbe7c9', '#d9dff0', '#f3dfcf', '#e0e0e0']
const REPLICATE_COLORS = ['#d6e4f5', '#cfe8d5', '#f5e3c8', '#e6d9f2', '#d3eeec', '#f6d5d5']

const varietyColor = computed(() => {
  const ids = [...new Set((layout.value?.plantings ?? []).map((p) => p.varietyId).filter((v): v is number => !!v))]
  ids.sort((a, b) => a - b)
  return new Map(ids.map((id, i) => [id, VARIETY_COLORS[i % VARIETY_COLORS.length]!]))
})

function plotStatus(p: Plot): PlotStatusCode {
  if (p.kind !== 'PLOT') {
    return p.kind
  }
  return statusByPlot.value.get(p.id)?.status ?? 'NONE'
}

function fillOf(p: Plot): string {
  const planting = plantingByPlot.value.get(p.id)
  if (p.kind !== 'PLOT' || display.value.mode === 'status') {
    return PLOT_STATUS[plotStatus(p)].color
  }
  if (display.value.mode === 'variety') {
    return planting?.varietyId ? varietyColor.value.get(planting.varietyId)! : '#f8f9fa'
  }
  return planting?.replicateNo
    ? REPLICATE_COLORS[(planting.replicateNo - 1) % REPLICATE_COLORS.length]!
    : '#f8f9fa'
}

const cells = computed<GridCell[]>(() =>
  plots.value.map((p) => {
    const planting = plantingByPlot.value.get(p.id)
    const variety = planting?.varietyId ? catalog.varietyById.get(planting.varietyId) : undefined
    const st = statusByPlot.value.get(p.id)
    const code = p.code || `${p.rowNo}-${p.colNo}`
    const lines = [code]
    if (p.kind !== 'PLOT') {
      lines.push(PLOT_KIND[p.kind])
    } else {
      lines.push(variety?.name ?? '—')
      if (planting?.replicateNo) {
        lines.push(`重复 ${replicateLabel(planting.replicateNo)}`)
      }
    }
    const badge = [variety?.controlType ? 'CK' : '', st?.status === 'ABNORMAL' ? '!' : ''].filter(Boolean).join(' ')
    const tip = [
      `${code}（第 ${p.rowNo} 行第 ${p.colNo} 列）`,
      p.kind === 'PLOT' ? `品种：${variety?.name ?? '未分配'}` : PLOT_KIND[p.kind],
      planting?.replicateNo ? `重复：${replicateLabel(planting.replicateNo)}` : '',
      p.kind === 'PLOT' && trial.value ? `状态：${PLOT_STATUS[plotStatus(p)].label}${st?.abnormalNote ? '（' + st.abnormalNote + '）' : ''}` : '',
      st?.enteredToday ? '今天录过数据' : '',
      p.lengthM && p.widthM ? `尺寸：${p.lengthM} × ${p.widthM} m` : '',
    ].filter(Boolean).join('\n')
    return {
      plotId: p.id, rowNo: p.rowNo, colNo: p.colNo, lines, fill: fillOf(p),
      badge: badge || undefined, marker: st?.enteredToday, tooltip: tip,
    }
  }),
)

const highlightedLink = ref<number | null>(null)

// 点到有对照关系的小区时才画虚线，免得虚线和文字挤在一起
const visibleLinks = computed(() => {
  const sel = new Set(selected.value)
  return (layout.value?.links ?? []).filter((link) => display.value.showAllLinks
    || link.id === highlightedLink.value || link.plots.some((p) => sel.has(p.plotId)))
})

const gridLinks = computed<GridLink[]>(() =>
  visibleLinks.value.map((link) => {
    const controls = link.plots.filter((p) => p.control).map((p) => plotById.value.get(p.plotId)).filter((p): p is Plot => !!p)
    // 每个比较的小区连到离它最近的对照小区
    const segments = link.plots.filter((p) => !p.control).flatMap((lp) => {
      const p = plotById.value.get(lp.plotId)
      if (!p || controls.length === 0) {
        return []
      }
      const nearest = controls.reduce((best, c) =>
        Math.hypot(c.rowNo - p.rowNo, c.colNo - p.colNo) < Math.hypot(best.rowNo - p.rowNo, best.colNo - p.colNo) ? c : best)
      return [{ from: nearest.id, to: p.id }]
    })
    return { id: link.id, label: link.label, segments, highlighted: link.id === highlightedLink.value }
  }),
)

const gridDevices = computed<GridDevice[]>(() =>
  devices.value
    .filter((d) => d.posRow && d.posCol)
    .map((d) => ({ id: d.id, row: d.posRow!, col: d.posCol!, label: d.name, status: d.status })),
)

const field = computed(() => detail.value?.field ?? null)

/** 按实际尺寸：每列宽取这一列最长的小区，每行高取这一行最宽的小区，再按中位数缩放到设定的格子大小 */
function actualSizes(count: number, pick: (p: Plot) => number | null, index: (p: Plot) => number, base: number) {
  const values = plots.value.map(pick).filter((v): v is number => !!v).sort((a, b) => a - b)
  if (values.length === 0) {
    return undefined
  }
  const median = values[Math.floor(values.length / 2)]!
  return Array.from({ length: count }, (_, i) => {
    const inLine = plots.value.filter((p) => index(p) === i + 1).map(pick).filter((v): v is number => !!v)
    const size = inLine.length ? Math.max(...inLine) : median
    return Math.round(Math.min(Math.max((size / median) * base, 48), base * 3))
  })
}

const colWidths = computed(() => {
  if (!field.value) {
    return undefined
  }
  if (!display.value.actualSize) {
    return Array(field.value.colCount).fill(display.value.cellW)
  }
  return actualSizes(field.value.colCount, (p) => p.lengthM, (p) => p.colNo, display.value.cellW)
})

const rowHeights = computed(() => {
  if (!field.value) {
    return undefined
  }
  if (!display.value.actualSize) {
    return Array(field.value.rowCount).fill(display.value.cellH)
  }
  return actualSizes(field.value.rowCount, (p) => p.widthM, (p) => p.rowNo, display.value.cellH)
})

const legend = computed(() => {
  if (display.value.mode === 'status') {
    return (Object.keys(PLOT_STATUS) as PlotStatusCode[]).map((k) => ({ label: PLOT_STATUS[k].label, color: PLOT_STATUS[k].color }))
  }
  if (display.value.mode === 'variety') {
    return [...varietyColor.value.entries()].map(([id, color]) => ({
      label: catalog.varietyById.get(id)?.name ?? `品种 ${id}`, color,
    }))
  }
  const reps = [...new Set((layout.value?.plantings ?? []).map((p) => p.replicateNo).filter((r): r is number => !!r))]
  return reps.sort((a, b) => a - b).map((r) => ({
    label: `重复 ${replicateLabel(r)}`, color: REPLICATE_COLORS[(r - 1) % REPLICATE_COLORS.length]!,
  }))
})

// ---------- 拖动画面、Ctrl+滚轮缩放 ----------

const scroller = ref<HTMLElement | null>(null)
let panStart: { x: number; y: number; left: number; top: number } | null = null

function onPanDown(e: MouseEvent) {
  // 拖动模式下左键拖动，任何时候都可以按住中键拖动
  if (!scroller.value || !(e.button === 1 || (panning.value && e.button === 0))) {
    return
  }
  e.preventDefault()
  panStart = { x: e.clientX, y: e.clientY, left: scroller.value.scrollLeft, top: scroller.value.scrollTop }
}

function onPanMove(e: MouseEvent) {
  if (panStart && scroller.value) {
    scroller.value.scrollLeft = panStart.left - (e.clientX - panStart.x)
    scroller.value.scrollTop = panStart.top - (e.clientY - panStart.y)
  }
}

function onPanUp() {
  panStart = null
}

function onWheel(e: WheelEvent) {
  if (e.ctrlKey || e.metaKey) {
    e.preventDefault()
    zoomBy(e.deltaY < 0 ? 0.1 : -0.1)
  }
}

// ---------- 右键菜单 ----------

const menu = ref<{ x: number; y: number; plotId: number } | null>(null)

function onContextMenu(plotId: number, x: number, y: number) {
  // 菜单不要超出窗口
  menu.value = { plotId, x: Math.min(x, window.innerWidth - 200), y: Math.min(y, window.innerHeight - 320) }
}

function closeMenu() {
  menu.value = null
}

function onKey(e: KeyboardEvent) {
  if (e.key === 'Escape') {
    closeMenu()
  }
}

onMounted(() => {
  window.addEventListener('click', closeMenu)
  window.addEventListener('keydown', onKey)
  window.addEventListener('mouseup', onPanUp)
})
onBeforeUnmount(() => {
  window.removeEventListener('click', closeMenu)
  window.removeEventListener('keydown', onKey)
  window.removeEventListener('mouseup', onPanUp)
})

const menuPlot = computed(() => (menu.value ? plotById.value.get(menu.value.plotId) ?? null : null))

/** 先记下右键的小区再关菜单，关了菜单 menuPlot 就没了 */
function runMenu(action: (plot: Plot) => void) {
  const plot = menuPlot.value
  closeMenu()
  if (plot) {
    action(plot)
  }
}
const menuAbnormal = computed(() => !!menuPlot.value && statusByPlot.value.get(menuPlot.value.id)?.status === 'ABNORMAL')

// ---------- 详情、录入 ----------

const drawerOpen = ref(false)
const drawerPlotId = ref<number | null>(null)
const entryOpen = ref(false)
const entryPlots = ref<Plot[]>([])
const entryTraitId = ref<number | null>(null)

function openDetail(plotId: number) {
  if (!trialId.value) {
    ElMessage.info('还没有试验，先新建试验')
    return
  }
  drawerPlotId.value = plotId
  drawerOpen.value = true
}

function openEntry(list: Plot[], traitId: number | null = null) {
  if (!trialId.value) {
    ElMessage.warning('还没有试验，不能录入')
    return
  }
  const targets = list.filter((p) => p.kind === 'PLOT')
  if (targets.length === 0) {
    ElMessage.warning('保护区和走道不录入数据，请选试验小区')
    return
  }
  entryPlots.value = targets
  entryTraitId.value = traitId
  entryOpen.value = true
}

function entryFromDrawer(plotId: number, traitId: number | null) {
  const p = plotById.value.get(plotId)
  if (p) {
    openEntry([p], traitId)
  }
}

async function onEntrySaved() {
  await refreshStatus().catch(() => undefined)
  if (drawerOpen.value) {
    drawerRef.value?.reload()
  }
}

const drawerRef = ref<InstanceType<typeof PlotDetailDrawer> | null>(null)

async function markAbnormal(list: Plot[], abnormal: boolean) {
  if (!trialId.value) {
    return
  }
  const targets = list.filter((p) => p.kind === 'PLOT')
  if (targets.length === 0) {
    ElMessage.warning('只能给试验小区标记异常')
    return
  }
  let note: string | null = null
  if (abnormal) {
    try {
      const res = await ElMessageBox.prompt(`给 ${targets.length} 个小区标记异常。写一下是什么情况（可不填），例如：倒伏、缺苗、积水`,
        '标记异常', { inputPlaceholder: '异常情况', confirmButtonText: '标记', cancelButtonText: '取消',
          inputValidator: (v) => !v || v.length <= 200 || '不能超过 200 个字' })
      note = res.value?.trim() || null
    } catch {
      return
    }
  }
  try {
    status.value = await observationApi.setAbnormal(trialId.value, targets.map((p) => p.id), abnormal, note)
    ElMessage.success(abnormal ? '已标记异常' : '已取消异常')
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

// ---------- 老师：布置试验 ----------

const fieldDialog = ref(false)
const trialDialog = ref(false)
const editingTrial = ref<Trial | null>(null)
const varietyDialog = ref(false)
const linkDialog = ref(false)
const numberingDialog = ref(false)
const plotDialog = ref(false)

function newTrial() {
  editingTrial.value = null
  trialDialog.value = true
}

function editTrial() {
  editingTrial.value = trial.value
  trialDialog.value = true
}

async function onTrialSaved(t: Trial) {
  const before = trialId.value
  await loadTrials(t.id)
  // 改的是当前试验时 trialId 不变，watch 不会触发，自己刷新
  if (before === trialId.value) {
    await loadTrialData()
  }
}

async function onTrialRemoved() {
  await loadTrials(null)
}

async function onFieldSaved(d: FieldDetail) {
  detail.value = d
  selected.value = selected.value.filter((id) => d.plots.some((p) => p.id === id))
  await loadTrialData().catch(() => undefined)
}

function requireTrial(): boolean {
  if (!trialId.value) {
    ElMessage.warning('先新建试验')
    return false
  }
  return true
}

function requireSelection(min = 1): boolean {
  if (selected.value.length < min) {
    ElMessage.warning(min > 1 ? `至少选 ${min} 个小区` : '先在平面图上选择小区')
    return false
  }
  return true
}

function onLayoutSaved(l: Layout) {
  layout.value = l
  refreshStatus().catch(() => undefined)
}

function onPlotsSaved(changed: Plot[]) {
  if (!detail.value) {
    return
  }
  const byId = new Map(changed.map((p) => [p.id, p]))
  detail.value = { ...detail.value, plots: detail.value.plots.map((p) => byId.get(p.id) ?? p) }
  refreshStatus().catch(() => undefined)
}

async function assignReplicate() {
  if (!requireTrial() || !requireSelection()) {
    return
  }
  const targets = selectedTrialPlots.value
  if (targets.length === 0) {
    ElMessage.warning('保护区和走道不设重复')
    return
  }
  let value: number | null
  try {
    const res = await ElMessageBox.prompt(`给 ${targets.length} 个小区设重复号（1 到 20，清空表示取消）`, '设重复号', {
      inputPattern: /^(|[1-9]|1\d|20)$/, inputErrorMessage: '请输入 1 到 20 的整数',
      inputValue: trial.value?.replicateCount ? '1' : '', confirmButtonText: '保存', cancelButtonText: '取消',
    })
    value = res.value ? Number(res.value) : null
  } catch {
    return
  }
  try {
    onLayoutSaved(await trialApi.assign(trialId.value!, targets.map((p) => p.id), 'REPLICATE', value))
    ElMessage.success('已设重复号')
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

async function removeLink(id: number, label: string) {
  try {
    await ElMessageBox.confirm(`删除对照关系"${label}"？`, '删除', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' })
  } catch {
    return
  }
  try {
    onLayoutSaved(await trialApi.deleteLink(trialId.value!, id))
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

function linkPlotsText(plotIds: number[]): string {
  return plotIds.map((id) => {
    const p = plotById.value.get(id)
    return p ? p.code || `${p.rowNo}-${p.colNo}` : '?'
  }).join('、')
}

// ---------- 其它 ----------

function selectAllPlots() {
  selected.value = plots.value.filter((p) => p.kind === 'PLOT').map((p) => p.id)
}

const gridRef = ref<InstanceType<typeof PlotGrid> | null>(null)

function exportMap() {
  const name = [field.value?.name, trial.value?.seasonYear, '种植图'].filter(Boolean).join('-')
  gridRef.value?.exportJpeg(`${name}.jpg`)
}

const indicators = computed(() => {
  const i = status.value?.indicators
  if (!i) {
    return []
  }
  return [
    { label: '试验小区', value: i.totalPlots },
    { label: '已种植', value: i.planted },
    { label: '今天已录', value: i.enteredToday, accent: '#1c7ed6' },
    { label: '有异常', value: i.abnormal, accent: i.abnormal > 0 ? '#c92a2a' : undefined },
    { label: '已收获', value: i.harvested },
    { label: '本周录入', value: i.weekEntries, unit: '条' },
    { label: '当前生育期', value: i.currentStage ?? '—' },
  ]
})
</script>

<template>
  <div class="map-page" v-loading="loading">
    <!-- 第一行：实验田、试验 -->
    <div class="topbar">
      <el-button :icon="ArrowLeft" text @click="router.push('/fields')">实验田</el-button>
      <h2>{{ field?.name }}</h2>
      <span v-if="field" class="muted">{{ field.rowCount }} 行 × {{ field.colCount }} 列</span>
      <el-divider direction="vertical" />
      <span class="muted">试验</span>
      <el-select v-model="trialId" placeholder="还没有试验" class="trial-select" :disabled="trials.length === 0">
        <el-option v-for="t in trials" :key="t.id" :value="t.id" :label="`${t.seasonYear} 年 · ${t.name}`" />
      </el-select>
      <template v-if="auth.isTeacher">
        <el-button :icon="Plus" @click="newTrial">新建试验</el-button>
        <el-button v-if="trial" text @click="editTrial">修改试验</el-button>
      </template>
      <div class="spacer" />
      <el-button v-if="auth.isTeacher" :icon="Setting" @click="fieldDialog = true">修改实验田</el-button>
      <el-button :icon="Download" @click="exportMap">导出种植图</el-button>
    </div>

    <!-- 状态指标 -->
    <div v-if="indicators.length" class="indicators">
      <div v-for="i in indicators" :key="i.label" class="indicator">
        <div class="ind-value" :style="i.accent ? { color: i.accent } : undefined">
          {{ i.value }}<span v-if="i.unit" class="ind-unit">{{ i.unit }}</span>
        </div>
        <div class="ind-label">{{ i.label }}</div>
      </div>
    </div>
    <el-alert
      v-else-if="!loading && field && trials.length === 0"
      type="info"
      show-icon
      :closable="false"
      class="no-trial"
      :title="auth.isTeacher ? '这块地还没有试验。先新建一个试验，再分配品种、录入数据。' : '老师还没有给这块地建试验，暂时不能录入。'"
    />

    <!-- 第二行：选择和操作 -->
    <div class="actionbar">
      <span class="sel">已选 <b>{{ selected.length }}</b> 个小区</span>
      <el-button size="small" text @click="selectAllPlots">全选试验小区</el-button>
      <el-button size="small" text :disabled="selected.length === 0" @click="selected = []">清除选择</el-button>
      <el-divider direction="vertical" />
      <template v-if="auth.canRecord">
        <el-button type="primary" :icon="EditPen" :disabled="!trial || selected.length === 0" @click="openEntry(selectedPlots)">
          录入数据
        </el-button>
        <el-button :icon="Warning" :disabled="!trial || selected.length === 0" @click="markAbnormal(selectedPlots, true)">标记异常</el-button>
        <el-button :disabled="!trial || selected.length === 0" @click="markAbnormal(selectedPlots, false)">取消异常</el-button>
      </template>
      <template v-if="auth.isTeacher">
        <el-divider direction="vertical" />
        <el-button :disabled="!trial || selected.length === 0" @click="requireTrial() && requireSelection() && (varietyDialog = true)">分配品种</el-button>
        <el-button :disabled="!trial || selected.length === 0" @click="assignReplicate">设重复</el-button>
        <el-button :disabled="!trial || selected.length < 2" @click="requireTrial() && requireSelection(2) && (linkDialog = true)">设对照</el-button>
        <el-button :disabled="selected.length === 0" @click="numberingDialog = true">编号</el-button>
        <el-button :disabled="selected.length === 0" @click="plotDialog = true">小区设置</el-button>
      </template>
    </div>

    <div class="workspace">
      <div class="grid-panel">
        <div class="viewbar">
          <el-radio-group v-model="display.mode" size="small">
            <el-radio-button value="status">按状态</el-radio-button>
            <el-radio-button value="variety">按品种</el-radio-button>
            <el-radio-button value="replicate">按重复</el-radio-button>
          </el-radio-group>
          <div class="spacer" />
          <el-button-group size="small">
            <el-button :icon="ZoomOut" @click="zoomBy(-0.1)" />
            <el-button class="zoom-text" @click="zoom = 1">{{ Math.round(zoom * 100) }}%</el-button>
            <el-button :icon="ZoomIn" @click="zoomBy(0.1)" />
          </el-button-group>
          <el-tooltip content="打开后按住左键拖动画面；不打开时也可以按住滚轮拖动" placement="top">
            <el-button size="small" :type="panning ? 'primary' : 'default'" :icon="Rank" @click="panning = !panning">拖动</el-button>
          </el-tooltip>
          <el-popover placement="bottom-end" :width="300" trigger="click">
            <template #reference>
              <el-button size="small">小区大小</el-button>
            </template>
            <div class="size-form">
              <div class="size-row">
                <span>宽</span>
                <el-slider v-model="display.cellW" :min="60" :max="220" :step="2" />
              </div>
              <div class="size-row">
                <span>高</span>
                <el-slider v-model="display.cellH" :min="44" :max="160" :step="2" />
              </div>
              <el-checkbox v-model="display.actualSize">按小区实际长宽显示大小差别</el-checkbox>
              <div class="hint">横向表示长、纵向表示宽。小区的实际尺寸在"小区设置"里改。</div>
            </div>
          </el-popover>
        </div>
        <div
          ref="scroller"
          class="grid-scroll"
          :class="{ panning }"
          @mousedown="onPanDown"
          @mousemove="onPanMove"
          @wheel="onWheel"
        >
          <PlotGrid
            v-if="field"
            ref="gridRef"
            v-model:selected="selected"
            :rows="field.rowCount"
            :cols="field.colCount"
            :cells="cells"
            :links="gridLinks"
            :devices="gridDevices"
            :zoom="zoom"
            :col-widths="colWidths"
            :row-heights="rowHeights"
            :panning="panning"
            @contextmenu="onContextMenu"
            @open="openDetail"
          />
        </div>
      </div>

      <aside class="side">
        <div class="side-block">
          <div class="side-title">图例</div>
          <div v-for="l in legend" :key="l.label" class="legend-row">
            <span class="swatch" :style="{ background: l.color }" />{{ l.label }}
          </div>
          <div v-if="legend.length === 0" class="hint">还没有分配</div>
          <div class="legend-row"><span class="dot" />今天录过数据</div>
          <div class="legend-row"><b class="ck">CK</b>对照品种　<b class="ck">!</b>有异常</div>
        </div>

        <div v-if="trial" class="side-block">
          <div class="side-title">对照关系</div>
          <el-checkbox v-if="layout?.links.length" v-model="display.showAllLinks" size="small">一直显示虚线</el-checkbox>
          <div v-if="layout?.links.length && !display.showAllLinks" class="hint">点选对照小区或比较的小区时显示虚线；鼠标移到下面的条目上也会显示。</div>
          <div v-if="!layout?.links.length" class="hint">
            {{ auth.isTeacher ? '选中对照小区和要比较的小区，点"设对照"。' : '还没有设对照关系。' }}
          </div>
          <div
            v-for="l in layout?.links ?? []"
            :key="l.id"
            class="link-row"
            @mouseenter="highlightedLink = l.id"
            @mouseleave="highlightedLink = null"
          >
            <div>
              <b>{{ l.label }}</b>
              <div class="hint">
                对照 {{ linkPlotsText(l.plots.filter((p) => p.control).map((p) => p.plotId)) }}，
                比较 {{ linkPlotsText(l.plots.filter((p) => !p.control).map((p) => p.plotId)) }}
              </div>
            </div>
            <el-button v-if="auth.isTeacher" link type="danger" size="small" @click="removeLink(l.id, l.label)">删除</el-button>
          </div>
        </div>

        <div class="side-block">
          <div class="side-title">操作</div>
          <div class="hint tips">
            单击选择小区；按住 Ctrl 或 Shift 单击多选；拖动鼠标框选；点行号、列号选整行、整列。<br>
            右键小区弹出菜单；双击打开小区详情。<br>
            Ctrl + 滚轮缩放；按住滚轮拖动画面。
          </div>
        </div>
      </aside>
    </div>

    <!-- 右键菜单 -->
    <div v-if="menu && menuPlot" class="context-menu" :style="{ left: menu.x + 'px', top: menu.y + 'px' }" @click.stop>
      <div class="menu-title">{{ menuPlot.code || `${menuPlot.rowNo}-${menuPlot.colNo}` }}<span v-if="selected.length > 1" class="muted">（已选 {{ selected.length }} 个）</span></div>
      <div class="menu-item" @click="runMenu((p) => openDetail(p.id))">查看详情</div>
      <template v-if="auth.canRecord && trial && menuPlot.kind === 'PLOT'">
        <div class="menu-item" @click="runMenu(() => openEntry(selectedPlots))">
          录入数据{{ selected.length > 1 ? `（${selected.length} 个小区）` : '' }}
        </div>
        <div v-if="!menuAbnormal || selected.length > 1" class="menu-item" @click="runMenu(() => markAbnormal(selectedPlots, true))">标记异常</div>
        <div v-if="menuAbnormal || selected.length > 1" class="menu-item" @click="runMenu(() => markAbnormal(selectedPlots, false))">取消异常</div>
      </template>
      <template v-if="auth.isTeacher">
        <div class="menu-sep" />
        <div v-if="trial" class="menu-item" @click="runMenu(() => (varietyDialog = true))">分配品种</div>
        <div v-if="trial" class="menu-item" @click="runMenu(assignReplicate)">设重复号</div>
        <div v-if="trial && selected.length >= 2" class="menu-item" @click="runMenu(() => (linkDialog = true))">设对照关系</div>
        <div class="menu-item" @click="runMenu(() => (numberingDialog = true))">编号</div>
        <div class="menu-item" @click="runMenu(() => (plotDialog = true))">小区设置（类型、尺寸）</div>
      </template>
    </div>

    <PlotDetailDrawer
      ref="drawerRef"
      v-model="drawerOpen"
      :trial-id="trialId"
      :plot-id="drawerPlotId"
      @entry="entryFromDrawer"
      @changed="refreshStatus"
    />
    <EntryDialog
      v-if="trialId"
      v-model="entryOpen"
      :trial-id="trialId"
      :plots="entryPlots"
      :initial-trait-id="entryTraitId"
      @saved="onEntrySaved"
    />
    <FieldFormDialog v-model="fieldDialog" :field="field" @saved="onFieldSaved" />
    <TrialFormDialog v-model="trialDialog" :field-id="fieldId" :trial="editingTrial" @saved="onTrialSaved" @removed="onTrialRemoved" />
    <AssignVarietyDialog v-if="trialId" v-model="varietyDialog" :trial-id="trialId" :plot-ids="selectedTrialPlots.map((p) => p.id)" @saved="onLayoutSaved" />
    <ControlLinkDialog
      v-if="trialId"
      v-model="linkDialog"
      :trial-id="trialId"
      :plots="selectedTrialPlots"
      :variety-of="(id: number) => plantingByPlot.get(id)?.varietyId ?? null"
      @saved="onLayoutSaved"
    />
    <NumberingDialog v-model="numberingDialog" :field-id="fieldId" :plots="selectedPlots" @saved="onPlotsSaved" />
    <PlotSettingsDialog v-model="plotDialog" :field-id="fieldId" :plots="selectedPlots" @saved="onPlotsSaved" />
  </div>
</template>

<style scoped>
.map-page {
  display: flex;
  flex-direction: column;
  height: 100%;
  padding: 12px 20px 16px;
  box-sizing: border-box;
  gap: 10px;
}

.topbar,
.actionbar,
.viewbar {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.topbar h2 {
  margin: 0;
  font-size: 19px;
}

.trial-select {
  width: 280px;
}

.spacer {
  flex: 1;
}

.indicators {
  display: grid;
  grid-template-columns: repeat(7, minmax(0, 1fr));
  gap: 10px;
}

.indicator {
  background: #fff;
  border: 1px solid var(--app-border);
  border-radius: 8px;
  padding: 8px 14px;
}

.ind-value {
  font-size: 22px;
  font-weight: 600;
  line-height: 1.3;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.ind-unit {
  font-size: 12px;
  margin-left: 2px;
  color: var(--app-text-secondary);
  font-weight: normal;
}

.ind-label {
  font-size: 12px;
  color: var(--app-text-secondary);
}

.no-trial {
  flex-shrink: 0;
}

.actionbar {
  background: #fff;
  border: 1px solid var(--app-border);
  border-radius: 8px;
  padding: 8px 12px;
}

.actionbar .el-button + .el-button {
  margin-left: 0;
}

.sel b {
  color: #1c64d6;
}

.workspace {
  flex: 1;
  min-height: 0;
  display: flex;
  gap: 12px;
}

.grid-panel {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  background: #fff;
  border: 1px solid var(--app-border);
  border-radius: 8px;
}

.viewbar {
  padding: 8px 12px;
  border-bottom: 1px solid var(--app-border);
}

.viewbar .el-button + .el-button {
  margin-left: 0;
}

.zoom-text {
  min-width: 58px;
}

.grid-scroll {
  flex: 1;
  overflow: auto;
  min-height: 0;
}

.grid-scroll.panning {
  cursor: grab;
}

.size-form {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.size-row {
  display: flex;
  align-items: center;
  gap: 12px;
}

.size-row .el-slider {
  flex: 1;
}

.side {
  width: 240px;
  flex-shrink: 0;
  overflow: auto;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.side-block {
  background: #fff;
  border: 1px solid var(--app-border);
  border-radius: 8px;
  padding: 10px 12px;
}

.side-title {
  font-weight: 600;
  margin-bottom: 6px;
}

.legend-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 2px 0;
  font-size: 13px;
}

.swatch {
  width: 16px;
  height: 12px;
  border-radius: 3px;
  border: 1px solid #c9d3cc;
}

.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #1c7ed6;
  margin: 0 4px;
}

.ck {
  color: #c92a2a;
  font-size: 12px;
}

.link-row {
  display: flex;
  justify-content: space-between;
  gap: 6px;
  padding: 6px 4px;
  border-radius: 4px;
  cursor: default;
}

.link-row:hover {
  background: #fff4e6;
}

.tips {
  line-height: 1.8;
}

.context-menu {
  position: fixed;
  z-index: 3000;
  min-width: 180px;
  background: #fff;
  border: 1px solid var(--app-border);
  border-radius: 6px;
  box-shadow: 0 6px 20px rgba(0, 0, 0, 0.12);
  padding: 4px 0;
  font-size: 13px;
}

.menu-title {
  padding: 6px 14px;
  font-weight: 600;
  border-bottom: 1px solid var(--app-border);
  margin-bottom: 4px;
}

.menu-item {
  padding: 7px 14px;
  cursor: pointer;
}

.menu-item:hover {
  background: #eef5ef;
}

.menu-sep {
  height: 1px;
  background: var(--app-border);
  margin: 4px 0;
}
</style>
