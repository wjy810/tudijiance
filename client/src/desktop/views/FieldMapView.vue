<script setup lang="ts">
// 实验田平面图（第一期的主界面）。分两种模式：
//   录入数据：日常用，选中小区后录入、标记异常、看详情；
//   布置试验：老师每季开始时用，分配品种、设重复、设对照、编号、改小区类型和尺寸。
// 选中小区后，底部浮出对应模式的操作条；右键小区弹出菜单，双击打开详情
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  ArrowDown, ArrowLeft, Close, EditPen, PieChart, QuestionFilled, Rank, Setting, Warning, ZoomIn, ZoomOut,
} from '@element-plus/icons-vue'

import {
  deviceApi, fieldApi, observationApi, trialApi,
  type DeviceView, type FieldDetail, type Layout, type Plot, type PlotStatusCode, type PlotValue, type TraitView, type Trial,
  type TrialStatus,
} from '@/shared/api'
import { PLOT_KIND, PLOT_STATUS, TRAIT_CATEGORY, replicateLabel } from '@/shared/labels'
import { REPLICATE_COLORS, SEQUENTIAL_GRADIENT, VARIETY_COLORS, sequentialColor } from '@/shared/colors'
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

const field = computed(() => detail.value?.field ?? null)
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
      catalog.loadTraits(),
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
  await nextTick()
  fitWidth()
})

// ---------- 模式和显示方式 ----------

type Mode = 'record' | 'layout'
type ViewMode = 'status' | 'variety' | 'replicate' | 'trait'

/** 老师刚建好试验、还没分配品种时，直接进入布置模式 */
const mode = ref<Mode>('record')
watch(layout, (l, old) => {
  if (auth.isTeacher && !old && l && l.plantings.every((p) => !p.varietyId)) {
    mode.value = 'layout'
  }
})

interface Display {
  view: ViewMode
  cellW: number
  cellH: number
  /** "按指标"着色时用的项目 */
  traitId: number | null
}

const DISPLAY_KEY = 'tudijiance.mapDisplay.v2'

function loadDisplay(): Display {
  const fallback: Display = { view: 'status', cellW: 108, cellH: 72, traitId: null }
  try {
    const saved = JSON.parse(localStorage.getItem(DISPLAY_KEY) || '{}') as Partial<Display>
    return {
      view: saved.view ?? fallback.view,
      cellW: saved.cellW ?? fallback.cellW,
      cellH: saved.cellH ?? fallback.cellH,
      traitId: saved.traitId ?? fallback.traitId,
    }
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

// 布置试验时看品种分布更方便，录入时看状态
watch(mode, (m) => {
  display.value.view = m === 'layout' ? 'variety' : 'status'
})

const zoom = ref(1)
const panning = ref(false)

function zoomBy(step: number) {
  zoom.value = Math.min(2.5, Math.max(0.3, Math.round((zoom.value + step) * 10) / 10))
}

/** 缩放到整块地正好放得下（不放大，最小 50%） */
function fitWidth() {
  const el = scroller.value
  if (!el || !field.value) {
    return
  }
  // 和 PlotGrid 的边距、行号宽度一致
  const gridWidth = 20 + 30 + field.value.colCount * display.value.cellW
  zoom.value = Math.max(0.5, Math.min(1, Math.floor(((el.clientWidth - 8) / gridWidth) * 100) / 100))
}


const varietyColor = computed(() => {
  const ids = [...new Set((layout.value?.plantings ?? []).map((p) => p.varietyId).filter((v): v is number => !!v))]
  ids.sort((a, b) => a - b)
  return new Map(ids.map((id, i) => [id, VARIETY_COLORS[i % VARIETY_COLORS.length]!]))
})

// ---------- 按指标着色 ----------

const traitValues = ref(new Map<number, PlotValue>())
const metricTrait = computed(() => catalog.traitById.get(display.value.traitId ?? -1)?.trait ?? null)
const metricRange = computed(() => {
  const vals = [...traitValues.value.values()].map((v) => v.value)
  return vals.length ? { min: Math.min(...vals), max: Math.max(...vals) } : null
})

async function loadTraitValues() {
  if (display.value.view !== 'trait' || !trialId.value) {
    return
  }
  if (!display.value.traitId) {
    display.value.traitId = catalog.statTraits.find((t) => t.trait.code === 'yield_per_mu')?.trait.id
      ?? catalog.statTraits[0]?.trait.id ?? null
  }
  if (!display.value.traitId) {
    return
  }
  try {
    const list = await observationApi.traitValues(trialId.value, display.value.traitId)
    traitValues.value = new Map(list.map((v) => [v.plotId, v]))
  } catch (e) {
    traitValues.value = new Map()
    ElMessage.error((e as Error).message)
  }
}

watch([() => display.value.view, () => display.value.traitId, trialId], loadTraitValues)

function metricFill(plotId: number): string {
  const v = traitValues.value.get(plotId)
  const r = metricRange.value
  if (!v || !r) {
    return '#f1ebdd'
  }
  return sequentialColor(r.max === r.min ? 1 : (v.value - r.min) / (r.max - r.min))
}

function plotStatus(p: Plot): PlotStatusCode {
  if (p.kind !== 'PLOT') {
    return p.kind
  }
  return statusByPlot.value.get(p.id)?.status ?? 'NONE'
}

function fillOf(p: Plot): string {
  const planting = plantingByPlot.value.get(p.id)
  if (p.kind !== 'PLOT' || display.value.view === 'status') {
    return PLOT_STATUS[plotStatus(p)].color
  }
  if (display.value.view === 'variety') {
    return planting?.varietyId ? varietyColor.value.get(planting.varietyId)! : '#f1ebdd'
  }
  if (display.value.view === 'trait') {
    return metricFill(p.id)
  }
  return planting?.replicateNo
    ? REPLICATE_COLORS[(planting.replicateNo - 1) % REPLICATE_COLORS.length]!
    : '#f1ebdd'
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
      if (display.value.view === 'trait') {
        lines.push(traitValues.value.get(p.id)?.displayValue ?? '无数据')
      } else if (planting?.replicateNo) {
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
      display.value.view === 'trait' && metricTrait.value
        ? `${metricTrait.value.name}：${traitValues.value.get(p.id)?.displayValue ?? '无数据'}` : '',
      p.lengthM && p.widthM ? `尺寸：${p.lengthM} × ${p.widthM} m` : '',
    ].filter(Boolean).join('\n')
    return {
      plotId: p.id, rowNo: p.rowNo, colNo: p.colNo, lines, fill: fillOf(p),
      badge: badge || undefined, marker: st?.enteredToday, tooltip: tip,
    }
  }),
)

// 在右侧"对照关系"里点了哪一组；再点一次取消
const pinnedLink = ref<number | null>(null)

function togglePinnedLink(id: number) {
  pinnedLink.value = pinnedLink.value === id ? null : id
  if (pinnedLink.value !== null) {
    selected.value = []
  }
}

// 平时不画对照虚线。只有单击了一个有对照关系的小区，或者在右侧点了某一组时，才画出这一组。
// 框选、多选小区（准备录入）时不画，免得虚线挡住小区
const visibleLinks = computed(() => {
  const links = layout.value?.links ?? []
  if (pinnedLink.value !== null) {
    return links.filter((link) => link.id === pinnedLink.value)
  }
  if (selected.value.length !== 1) {
    return []
  }
  const focus = selected.value[0]
  return links.filter((link) => link.plots.some((p) => p.plotId === focus))
})

watch(selected, (ids) => {
  if (ids.length > 0) {
    pinnedLink.value = null
  }
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
    return { id: link.id, label: link.label, segments, controlPlotIds: controls.map((c) => c.id) }
  }),
)

const gridDevices = computed<GridDevice[]>(() =>
  devices.value
    .filter((d) => d.posRow && d.posCol)
    .map((d) => ({ id: d.id, row: d.posRow!, col: d.posCol!, label: d.name, status: d.status })),
)

const colWidths = computed(() => (field.value ? Array(field.value.colCount).fill(display.value.cellW) : undefined))
const rowHeights = computed(() => (field.value ? Array(field.value.rowCount).fill(display.value.cellH) : undefined))

const legend = computed(() => {
  if (display.value.view === 'trait') {
    return []
  }
  if (display.value.view === 'status') {
    return (Object.keys(PLOT_STATUS) as PlotStatusCode[]).map((k) => ({ label: PLOT_STATUS[k].label, color: PLOT_STATUS[k].color }))
  }
  if (display.value.view === 'variety') {
    return [...varietyColor.value.entries()].map(([id, color]) => {
      const v = catalog.varietyById.get(id)
      return { label: v ? `${v.name}${v.controlType ? '（CK）' : ''}` : `品种 ${id}`, color }
    })
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

// ---------- 详情、录入、异常 ----------

const drawerOpen = ref(false)
const drawerPlotId = ref<number | null>(null)
const drawerRef = ref<InstanceType<typeof PlotDetailDrawer> | null>(null)
const entryOpen = ref(false)
const entryPlots = ref<Plot[]>([])
const entryTraitId = ref<number | null>(null)

/** 用选中的小区做图表：带着实验田、试验和小区去自助分析 */
function analyzeSelection() {
  router.push({
    path: '/analysis',
    query: { fieldId: String(fieldId.value), trialId: trialId.value ? String(trialId.value) : undefined, plots: selected.value.join(',') },
  })
}

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
  loadTraitValues()
  if (drawerOpen.value) {
    drawerRef.value?.reload()
  }
}

/** 选中的试验小区是不是全都标了异常：是就显示"取消异常"，否则显示"标记异常" */
const allAbnormal = computed(() =>
  selectedTrialPlots.value.length > 0
  && selectedTrialPlots.value.every((p) => statusByPlot.value.get(p.id)?.status === 'ABNORMAL'))

async function toggleAbnormal(list: Plot[]) {
  if (!trialId.value) {
    return
  }
  const targets = list.filter((p) => p.kind === 'PLOT')
  if (targets.length === 0) {
    ElMessage.warning('只能给试验小区标记异常')
    return
  }
  const abnormal = !targets.every((p) => statusByPlot.value.get(p.id)?.status === 'ABNORMAL')
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

function onTrialCommand(cmd: string) {
  editingTrial.value = cmd === 'edit' ? trial.value : null
  trialDialog.value = true
}

function onMoreCommand(cmd: string) {
  if (cmd === 'field') {
    fieldDialog.value = true
  } else if (cmd === 'export') {
    exportMap()
  } else if (cmd === 'import') {
    router.push('/import')
  }
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

function openLayoutDialog(which: 'variety' | 'link') {
  if (!trialId.value) {
    ElMessage.warning('先新建试验')
    return
  }
  if (selectedTrialPlots.value.length === 0) {
    ElMessage.warning('请选择试验小区（保护区、走道不分配品种）')
    return
  }
  if (which === 'link' && selectedTrialPlots.value.length < 2) {
    ElMessage.warning('设对照至少要选 2 个小区：对照小区和要比较的小区')
    return
  }
  if (which === 'variety') {
    varietyDialog.value = true
  } else {
    linkDialog.value = true
  }
}

async function assignReplicate() {
  if (!trialId.value) {
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
      inputValue: '1', confirmButtonText: '保存', cancelButtonText: '取消',
    })
    value = res.value ? Number(res.value) : null
  } catch {
    return
  }
  try {
    onLayoutSaved(await trialApi.assign(trialId.value, targets.map((p) => p.id), 'REPLICATE', value))
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

const ind = computed(() => status.value?.indicators ?? null)

/** 着色可选的指标，按分类分组 */
const metricGroups = computed(() => {
  const groups = new Map<string, TraitView[]>()
  for (const t of catalog.statTraits) {
    const label = TRAIT_CATEGORY[t.trait.category]
    groups.set(label, [...(groups.get(label) ?? []), t])
  }
  return [...groups.entries()].map(([label, items]) => ({ label, items }))
})
</script>

<template>
  <div class="map-page" v-loading="loading">
    <!-- 页头：实验田、试验 -->
    <header class="head">
      <div class="head-left">
        <el-button :icon="ArrowLeft" text class="back" @click="router.push('/home')">首页</el-button>
        <div>
          <h2>{{ field?.name }}</h2>
          <div class="page-desc">
            {{ field?.rowCount }} 行 × {{ field?.colCount }} 列
            <template v-if="field?.location"> · {{ field.location }}</template>
          </div>
        </div>
      </div>
      <div class="head-right">
        <el-select v-model="trialId" placeholder="还没有试验" class="trial-select" :disabled="trials.length === 0">
          <el-option v-for="t in trials" :key="t.id" :value="t.id" :label="`${t.seasonYear} 年 · ${t.name}`" />
        </el-select>
        <el-dropdown v-if="auth.isTeacher" trigger="click" @command="onTrialCommand">
          <el-button>试验<el-icon class="el-icon--right"><ArrowDown /></el-icon></el-button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="new">新建试验</el-dropdown-item>
              <el-dropdown-item command="edit" :disabled="!trial">修改或删除当前试验</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
        <el-dropdown trigger="click" @command="onMoreCommand">
          <el-button>更多<el-icon class="el-icon--right"><ArrowDown /></el-icon></el-button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item v-if="auth.isTeacher" command="field">修改实验田（行列、尺寸）</el-dropdown-item>
              <el-dropdown-item v-if="auth.isTeacher" command="import">导入历史表格</el-dropdown-item>
              <el-dropdown-item command="export">导出种植图（jpg）</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </header>

    <!-- 状态条 -->
    <div v-if="ind" class="stats panel">
      <div class="stat stage">
        <span class="stat-label">当前生育期</span>
        <span class="stage-badge">{{ ind.currentStage ?? '未开始' }}</span>
      </div>
      <div class="stat">
        <span class="stat-label">今天已录</span>
        <span class="stat-value num">{{ ind.enteredToday }}<small> / {{ ind.totalPlots }}</small></span>
      </div>
      <div class="stat">
        <span class="stat-label">已种植</span>
        <span class="stat-value num">{{ ind.planted }}</span>
      </div>
      <div class="stat">
        <span class="stat-label">已收获</span>
        <span class="stat-value num">{{ ind.harvested }}</span>
      </div>
      <div class="stat" :class="{ danger: ind.abnormal > 0 }">
        <span class="stat-label">有异常</span>
        <span class="stat-value num">
          <el-icon v-if="ind.abnormal > 0"><Warning /></el-icon>{{ ind.abnormal }}
        </span>
      </div>
      <div class="stat">
        <span class="stat-label">本周录入</span>
        <span class="stat-value num">{{ ind.weekEntries }}<small> 条</small></span>
      </div>
    </div>
    <el-alert
      v-else-if="!loading && field && trials.length === 0"
      type="info"
      show-icon
      :closable="false"
      :title="auth.isTeacher ? '这块地还没有试验。点右上角&quot;试验&quot; → 新建试验，再分配品种、录入数据。' : '老师还没有给这块地建试验，暂时不能录入。'"
    />

    <div class="workspace">
      <section class="map-panel panel">
        <div class="toolbar">
          <el-radio-group v-if="auth.isTeacher" v-model="mode" class="mode">
            <el-radio-button value="record">录入数据</el-radio-button>
            <el-radio-button value="layout">布置试验</el-radio-button>
          </el-radio-group>
          <el-radio-group v-model="display.view" size="small">
            <el-radio-button value="status">按状态</el-radio-button>
            <el-radio-button value="variety">按品种</el-radio-button>
            <el-radio-button value="replicate">按重复</el-radio-button>
            <el-radio-button value="trait">按指标</el-radio-button>
          </el-radio-group>
          <el-select
            v-if="display.view === 'trait'"
            v-model="display.traitId"
            size="small"
            filterable
            placeholder="选择指标"
            class="metric-select"
          >
            <el-option-group v-for="g in metricGroups" :key="g.label" :label="g.label">
              <el-option
                v-for="t in g.items"
                :key="t.trait.id"
                :value="t.trait.id"
                :label="t.trait.unit && t.trait.unit !== '级' ? `${t.trait.name}（${t.trait.unit}）` : t.trait.name"
              />
            </el-option-group>
          </el-select>
          <div class="spacer" />
          <el-button size="small" text @click="selectAllPlots">全选小区</el-button>
          <el-button-group size="small">
            <el-button :icon="ZoomOut" @click="zoomBy(-0.1)" />
            <el-tooltip content="点一下适应窗口" placement="top">
              <el-button class="zoom-text" @click="fitWidth">{{ Math.round(zoom * 100) }}%</el-button>
            </el-tooltip>
            <el-button :icon="ZoomIn" @click="zoomBy(0.1)" />
          </el-button-group>
          <el-tooltip content="打开后按住左键拖动画面（不打开时可以按住滚轮拖动）" placement="top">
            <el-button size="small" :type="panning ? 'primary' : 'default'" :icon="Rank" @click="panning = !panning" />
          </el-tooltip>
          <el-popover placement="bottom-end" :width="280" trigger="click">
            <template #reference>
              <el-button size="small" :icon="Setting" />
            </template>
            <div class="size-form">
              <div class="pop-title">小区显示大小</div>
              <div class="size-row">
                <span>宽</span>
                <el-slider v-model="display.cellW" :min="64" :max="220" :step="2" />
              </div>
              <div class="size-row">
                <span>高</span>
                <el-slider v-model="display.cellH" :min="48" :max="160" :step="2" />
              </div>
            </div>
          </el-popover>
          <el-popover placement="bottom-end" :width="300" trigger="hover">
            <template #reference>
              <el-button size="small" :icon="QuestionFilled" text />
            </template>
            <div class="help">
              <p><b>选择：</b>单击选一个；按住 Ctrl 或 Shift 单击多选；拖动鼠标框选；点行号、列号选整行、整列。</p>
              <p><b>操作：</b>选中后底部出现操作条；右键小区弹出菜单；双击小区看详情。</p>
              <p><b>画面：</b>Ctrl + 滚轮缩放；按住滚轮拖动画面。</p>
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

        <!-- 选中小区后浮出的操作条 -->
        <transition name="rise">
          <div v-if="selected.length > 0" class="action-bar">
            <span class="sel">已选 <b>{{ selected.length }}</b> 个小区</span>
            <template v-if="mode === 'record'">
              <template v-if="auth.canRecord && trial">
                <el-button type="primary" :icon="EditPen" @click="openEntry(selectedPlots)">录入数据</el-button>
                <el-button :icon="Warning" @click="toggleAbnormal(selectedPlots)">{{ allAbnormal ? '取消异常' : '标记异常' }}</el-button>
              </template>
              <el-button v-if="selected.length === 1" @click="openDetail(selected[0]!)">查看详情</el-button>
              <el-button v-if="selected.length > 1" :icon="PieChart" @click="analyzeSelection">做图表</el-button>
            </template>
            <template v-else>
              <el-button type="primary" @click="openLayoutDialog('variety')">分配品种</el-button>
              <el-button @click="assignReplicate">设重复</el-button>
              <el-button @click="openLayoutDialog('link')">设对照</el-button>
              <el-button @click="numberingDialog = true">编号</el-button>
              <el-button @click="plotDialog = true">小区类型和尺寸</el-button>
            </template>
            <el-button text :icon="Close" class="clear" @click="selected = []">取消选择</el-button>
          </div>
          <div v-else-if="trial" class="idle-hint">
            {{ mode === 'record' ? '点选或框选小区，开始录入数据' : '选中小区后分配品种、设重复、设对照' }}
          </div>
        </transition>
      </section>

      <aside class="side">
        <section class="panel panel-pad">
          <div class="panel-title">图例</div>
          <template v-if="display.view === 'trait'">
            <div class="metric-name">{{ metricTrait?.name ?? '选择指标' }}<span v-if="metricTrait?.unit && metricTrait.unit !== '级'" class="muted">（{{ metricTrait.unit }}）</span></div>
            <template v-if="metricRange">
              <div class="ramp" :style="{ background: SEQUENTIAL_GRADIENT }" />
              <div class="ramp-labels num"><span>{{ metricRange.min }}</span><span>{{ metricRange.max }}</span></div>
            </template>
            <div v-else class="hint">这个试验还没有这项数据</div>
            <div class="legend-row"><span class="swatch" style="background: #f1ebdd" />无数据</div>
          </template>
          <template v-else>
            <div v-for="l in legend" :key="l.label" class="legend-row">
              <span class="swatch" :style="{ background: l.color }" />{{ l.label }}
            </div>
            <div v-if="legend.length === 0" class="hint">还没有分配</div>
          </template>
          <div class="legend-sep" />
          <div class="legend-row"><span class="dot" />今天录过数据</div>
          <div class="legend-row"><b class="ck">CK</b>对照品种</div>
          <div class="legend-row"><b class="ck alert">!</b>有异常</div>
        </section>

        <section v-if="trial" class="panel panel-pad">
          <div class="panel-title">对照关系<span class="sub">{{ layout?.links.length ?? 0 }} 组</span></div>
          <div v-if="!layout?.links.length" class="hint">
            {{ auth.isTeacher ? '在"布置试验"里选中对照小区和要比较的小区，点"设对照"。' : '还没有设对照关系。' }}
          </div>
          <div
            v-for="l in layout?.links ?? []"
            :key="l.id"
            class="link-row"
            :class="{ active: pinnedLink === l.id }"
            @click="togglePinnedLink(l.id)"
          >
            <div>
              <div class="link-label">{{ l.label }}</div>
              <div class="hint">
                对照 {{ linkPlotsText(l.plots.filter((p) => p.control).map((p) => p.plotId)) }} ·
                比较 {{ linkPlotsText(l.plots.filter((p) => !p.control).map((p) => p.plotId)) }}
              </div>
            </div>
            <el-button v-if="auth.isTeacher && mode === 'layout'" link type="danger" size="small" @click.stop="removeLink(l.id, l.label)">删除</el-button>
          </div>
          <div v-if="layout?.links.length" class="hint link-tip">单击有对照关系的小区，或点上面的某一组，平面图上才显示这一组的虚线。</div>
        </section>
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
        <div class="menu-item" @click="runMenu(() => toggleAbnormal(selectedPlots))">{{ allAbnormal ? '取消异常' : '标记异常' }}</div>
      </template>
      <template v-if="auth.isTeacher">
        <div class="menu-sep" />
        <div v-if="trial" class="menu-item" @click="runMenu(() => openLayoutDialog('variety'))">分配品种</div>
        <div v-if="trial" class="menu-item" @click="runMenu(assignReplicate)">设重复号</div>
        <div v-if="trial && selected.length >= 2" class="menu-item" @click="runMenu(() => openLayoutDialog('link'))">设对照关系</div>
        <div class="menu-item" @click="runMenu(() => (numberingDialog = true))">编号</div>
        <div class="menu-item" @click="runMenu(() => (plotDialog = true))">小区类型和尺寸</div>
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
  padding: 18px 24px 20px;
  box-sizing: border-box;
  gap: 14px;
}

.head {
  display: flex;
  justify-content: space-between;
  align-items: flex-end;
  gap: 16px;
}

.head-left {
  display: flex;
  align-items: flex-end;
  gap: 8px;
}

.back {
  margin-bottom: 2px;
}

.head h2 {
  margin: 0;
  font-family: var(--font-serif);
  font-size: 24px;
  font-weight: 700;
  color: var(--forest);
  letter-spacing: 1px;
}

.head-right {
  display: flex;
  align-items: center;
  gap: 8px;
}

.trial-select {
  width: 300px;
}

/* 状态条 */
.stats {
  display: flex;
  align-items: stretch;
  padding: 0;
  flex-shrink: 0;
}

.stat {
  flex: 1;
  padding: 10px 18px;
  display: flex;
  flex-direction: column;
  gap: 2px;
  border-left: 1px solid var(--app-border);
}

.stat:first-child {
  border-left: none;
}

.stat-label {
  font-size: 12px;
  color: var(--app-text-secondary);
}

.stat-value {
  font-family: var(--font-serif);
  font-size: 24px;
  font-weight: 700;
  color: var(--forest);
  display: flex;
  align-items: center;
  gap: 4px;
}

.stat-value small {
  font-size: 13px;
  font-weight: normal;
  color: var(--app-text-secondary);
}

.stat.danger .stat-value {
  color: var(--app-danger);
}

.stat.stage {
  flex: 1.3;
}

.stage-badge {
  align-self: flex-start;
  margin-top: 3px;
  background: linear-gradient(180deg, #f8ebc9, #f0dca9);
  border: 1px solid #e5c98a;
  color: #7d5814;
  border-radius: 999px;
  padding: 3px 16px;
  font-family: var(--font-serif);
  font-size: 15px;
  font-weight: 700;
}

/* 平面图 */
.workspace {
  flex: 1;
  min-height: 0;
  display: flex;
  gap: 14px;
}

.map-panel {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  position: relative;
  overflow: hidden;
}

.toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 14px;
  border-bottom: 1px solid var(--line);
  background: var(--surface-2);
  flex-wrap: wrap;
}

.toolbar .el-button + .el-button {
  margin-left: 0;
}

.mode {
  margin-right: 6px;
}

.spacer {
  flex: 1;
}

.zoom-text {
  min-width: 58px;
}

.grid-scroll {
  flex: 1;
  overflow: auto;
  min-height: 0;
  padding: 8px 8px 76px;
  /* 小区之间露出田埂的土色 */
  background: #e9e1cd url('@/assets/art/soil.jpg') repeat;
  background-size: 256px;
  box-shadow: inset 0 2px 10px rgba(80, 60, 20, 0.12);
}

.grid-scroll.panning {
  cursor: grab;
}

.action-bar {
  position: absolute;
  left: 50%;
  bottom: 16px;
  transform: translateX(-50%);
  display: flex;
  align-items: center;
  gap: 8px;
  background: linear-gradient(180deg, #24513d, #1a3e2e);
  border: 1px solid rgba(226, 182, 90, 0.35);
  color: #fbf3df;
  border-radius: 14px;
  padding: 8px 10px 8px 18px;
  box-shadow: 0 14px 34px -10px rgba(16, 46, 32, 0.55);
  white-space: nowrap;
}

.action-bar .el-button + .el-button {
  margin-left: 0;
}

.action-bar .sel {
  margin-right: 8px;
}

.action-bar .sel b {
  color: var(--wheat-bright);
  font-family: var(--font-serif);
  font-size: 18px;
}

.action-bar .clear {
  color: #b9cfc1;
}

.idle-hint {
  position: absolute;
  left: 50%;
  bottom: 18px;
  transform: translateX(-50%);
  color: var(--ink-2);
  font-size: 13px;
  background: rgba(255, 253, 248, 0.92);
  padding: 7px 16px;
  border-radius: 999px;
  border: 1px solid var(--line-strong);
  box-shadow: var(--shadow);
}

.rise-enter-active,
.rise-leave-active {
  transition: opacity 0.15s, transform 0.15s;
}

.rise-enter-from,
.rise-leave-to {
  opacity: 0;
  transform: translate(-50%, 8px);
}

.pop-title {
  font-weight: 600;
  margin-bottom: 4px;
}

.size-form {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.size-row {
  display: flex;
  align-items: center;
  gap: 12px;
}

.size-row .el-slider {
  flex: 1;
}

.help p {
  margin: 0 0 6px;
  font-size: 13px;
  line-height: 1.6;
}

/* 右侧 */
.side {
  width: 250px;
  flex-shrink: 0;
  overflow: auto;
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.legend-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 3px 0;
  font-size: 13px;
}

.metric-select {
  width: 190px;
}

.metric-name {
  font-size: 13px;
  margin-bottom: 8px;
}

.ramp {
  height: 10px;
  border-radius: 3px;
}

.ramp-labels {
  display: flex;
  justify-content: space-between;
  font-size: 12px;
  color: var(--app-text-secondary);
  margin: 4px 0 6px;
}

.legend-sep {
  height: 1px;
  background: var(--app-border);
  margin: 8px 0;
}

.swatch {
  width: 18px;
  height: 12px;
  border-radius: 3px;
  border: 1px solid rgba(0, 0, 0, 0.08);
}

.swatch {
  border-radius: 4px;
}

.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--green);
  box-shadow: 0 0 0 1.5px #fff, 0 0 0 2.5px rgba(46, 107, 79, 0.25);
  margin: 0 5px;
}

/* 和平面图上的角标一样：金色小牌子 */
.ck {
  min-width: 18px;
  padding: 0 3px;
  border-radius: 5px;
  background: #d9a748;
  color: #3b2a0c;
  font-size: 10px;
  line-height: 16px;
  text-align: center;
}

.ck.alert {
  background: var(--clay);
  color: #fff6ee;
}

.link-row {
  display: flex;
  justify-content: space-between;
  gap: 6px;
  padding: 6px 8px;
  margin: 0 -8px;
  border-radius: 6px;
}

.link-row {
  cursor: pointer;
}

.link-row:hover,
.link-row.active {
  background: #fff4e6;
}

.link-label {
  font-weight: 600;
  font-size: 13px;
}

.link-tip {
  margin-top: 6px;
}

/* 右键菜单 */
.context-menu {
  position: fixed;
  z-index: 3000;
  min-width: 180px;
  background: #fff;
  border: 1px solid var(--app-border);
  border-radius: 8px;
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.12);
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
  background: var(--app-accent-soft);
}

.menu-sep {
  height: 1px;
  background: var(--app-border);
  margin: 4px 0;
}
</style>
