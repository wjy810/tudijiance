<script setup lang="ts">
// 实验田平面图（第一期的主界面）。分两种模式：
//   录入数据：日常用，选中小区后录入、标记异常、看详情；
//   布置试验：老师每季开始时用，分配品种、设重复、设对照、编号、改小区类型和尺寸。
// 选中小区后，底部浮出对应模式的操作条；右键小区弹出菜单，双击打开详情
import { computed, defineAsyncComponent, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  ArrowDown, ArrowLeft, ArrowRight, Box, Check, Close, Crop, EditPen, Grid, Notebook, Odometer, PieChart, Pointer,
  QuestionFilled, Rank, RefreshLeft, RefreshRight, Search, Warning, ZoomIn, ZoomOut,
} from '@element-plus/icons-vue'

import {
  breedingApi, deviceApi, fieldApi, observationApi, statsApi, trialApi,
  type Candidates, type DeviceView, type FieldDetail, type Layout, type Plot, type PlotDetail, type PlotStatusCode, type PlotValue,
  type TraitView, type Trial, type TrialStatus,
} from '@/shared/api'
import { PLOT_KIND, PLOT_STATUS, TRAIT_CATEGORY, replicateLabel } from '@/shared/labels'
import { REPLICATE_COLORS, SEQUENTIAL_GRADIENT, VARIETY_COLORS, sequentialColor } from '@/shared/colors'
import { rememberTrial } from '@/shared/composables/useTrialPicker'
import { useAuthStore } from '@/stores/auth'
import { useCatalogStore } from '@/stores/catalog'
import { useModuleStore } from '@/stores/modules'
import { useBreedingStore } from '@/stores/breeding'
import FieldCanvas, {
  type CanvasCell, type CanvasDevice, type CanvasLink, type CanvasTool, type RoadAction, type TrackResize,
} from '@/desktop/components/FieldCanvas.vue'
import {
  BLOCK_KIND, CELL_KIND, addRoad, addRoadProblem, anchors, blockSpan, checkLayout, defaultRoadWidth, fieldSize, fmt, innerCells,
  keyOf, mergeAt, moveBlockFit, moveCells, parseKey, plotPlace, plotPlaces, removeRoad, removedPlots, roadOfBlock, roadToBlocks,
  round, setBlockKind, setCellKinds, setInnerTrackPair, setTrackPair, swapBlocks, splitBlock, splitInner, trackToRoad,
  trackToRoadProblem, type Axis, type BlockKind, type CellKind, type FieldLayout,
} from '@/shared/fieldLayout'
import { useLayoutDraft } from '@/desktop/components/layoutDraft'
import LayoutEditPanel from '@/desktop/components/LayoutEditPanel.vue'
import type FieldScene3DType from '@/desktop/components/FieldScene3D.vue'

/** 3D 视角用到 three.js，点开时才加载 */
const FieldScene3D = defineAsyncComponent(() => import('@/desktop/components/FieldScene3D.vue'))
import MapCompass from '@/desktop/components/MapCompass.vue'
import { edgeLabels, northUpRotation } from '@/shared/compass'
import FieldFormDialog from '@/desktop/components/FieldFormDialog.vue'
import TrialFormDialog from '@/desktop/components/TrialFormDialog.vue'
import EntryDialog from '@/desktop/components/EntryDialog.vue'
import PlotDetailDrawer from '@/desktop/components/PlotDetailDrawer.vue'
import AssignVarietyDialog from '@/desktop/components/AssignVarietyDialog.vue'
import ControlLinkDialog from '@/desktop/components/ControlLinkDialog.vue'
import NumberingDialog from '@/desktop/components/NumberingDialog.vue'
import PlotSettingsDialog from '@/desktop/components/PlotSettingsDialog.vue'
import BreedingPanel from '@/desktop/components/breeding/BreedingPanel.vue'
import PlotLineCard from '@/desktop/components/breeding/PlotLineCard.vue'
import PlantDialog from '@/desktop/components/breeding/PlantDialog.vue'
import SelectDialog from '@/desktop/components/breeding/SelectDialog.vue'
import CrossDialog from '@/desktop/components/breeding/CrossDialog.vue'
import type { SelectLocation } from '@/desktop/components/breeding/types'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const catalog = useCatalogStore()
const modules = useModuleStore()
const breeding = useBreedingStore()

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
const fieldLayout = computed<FieldLayout | null>(() => detail.value?.layout ?? null)
const trial = computed(() => trials.value.find((t) => t.id === trialId.value) ?? null)
const plots = computed(() => detail.value?.plots ?? [])
const plotById = computed(() => new Map(plots.value.map((p) => [p.id, p])))
const plantingByPlot = computed(() => new Map((layout.value?.plantings ?? []).map((p) => [p.plotId, p])))
const statusByPlot = computed(() => new Map((status.value?.plots ?? []).map((p) => [p.plotId, p])))
const selectedPlots = computed(() => selected.value.map((id) => plotById.value.get(id)).filter((p): p is Plot => !!p))
const selectedTrialPlots = computed(() => selectedPlots.value.filter((p) => p.kind === 'PLOT'))

/** 育种的地块（属于某一季）：布置试验里种品系、选株、杂交，没有"试验"可选 */
const isBreeding = computed(() => !!detail.value?.field.seasonId)
const season = computed(() => (field.value?.seasonId ? breeding.seasonById.get(field.value.seasonId) ?? null : null))
/** 育种的地块里"小区"叫"小区块" */
const U = computed(() => (isBreeding.value ? '小区块' : '小区'))

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
    if (isBreeding.value) {
      await Promise.all([breeding.loadSeasons(), breeding.loadLines(), loadCandidates()])
    }
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
  canvasRef.value?.fit()
  // 从数据质量等页面带着 ?plot= 过来：选中这个小区并打开详情
  const plotId = Number(route.query.plot)
  if (plotId && plotById.value.has(plotId)) {
    selected.value = [plotId]
    openDetail(plotId)
  }
  // 从品系详情、系谱图带着 ?highlight= 过来：选中并放大到这些小区块
  applyHighlight()
  // 刚建好的地块直接进布置试验
  if (route.query.mode === 'layout' && auth.isTeacher) {
    mode.value = 'layout'
  }
})

function applyHighlight() {
  const ids = String(route.query.highlight ?? '').split(',').map(Number).filter((id) => plotById.value.has(id))
  if (ids.length) locatePlot(ids)
}

watch(() => route.query.highlight, () => applyHighlight())

// ---------- 模式和显示方式 ----------

/** 正在看的区块（"进入区块"），为空是整块地 */
const focus = ref<string | null>(null)

type Mode = 'record' | 'layout' | 'edit'
type ViewMode = 'status' | 'variety' | 'replicate' | 'trait'

/** 老师刚建好试验、还没分配品种时，直接进入布置模式 */
const mode = ref<Mode>('record')
watch(layout, (l, old) => {
  if (auth.isTeacher && !old && l && l.plantings.every((p) => !p.varietyId) && mode.value === 'record') {
    mode.value = 'layout'
  }
})

// ---------- 编辑地块（草稿，可以撤销，点保存才提交） ----------

const savedLayout = computed<FieldLayout | null>(() => detail.value?.layout ?? null)
const draft = useLayoutDraft(savedLayout)
const selectedBlocks = ref<string[]>([])
const selectedCells = ref<string[]>([])
const track = ref<{ axis: 'row' | 'col'; index: number } | null>(null)
const savingLayout = ref(false)

/** 切换模式：离开编辑地块时有没保存的改动要先问 */
const modeModel = computed<Mode>({
  get: () => mode.value,
  set: async (m) => {
    if (mode.value === 'edit' && m !== 'edit' && draft.dirty.value) {
      try {
        await ElMessageBox.confirm('地块布局改了还没保存，离开编辑会丢掉这些改动。', '还没保存', {
          type: 'warning', confirmButtonText: '丢掉改动', cancelButtonText: '继续编辑',
        })
      } catch {
        return
      }
    }
    mode.value = m
  },
})

watch(mode, (m, old) => {
  // 换模式时放好整块地（编辑地块多了标尺）
  nextTick(() => canvasRef.value?.fit(true))
  selected.value = []
  selectedBlocks.value = []
  selectedCells.value = []
  track.value = null
  if (m === 'edit') {
    draft.begin()
  } else if (old === 'edit') {
    draft.end()
    if (tool.value === 'road') tool.value = 'select'
  }
})

watch([focus, selectedBlocks], () => {
  if (mode.value !== 'edit') {
    return
  }
  if (selectedBlocks.value.length > 0) {
    track.value = null
  }
})

watch(focus, (f) => {
  selectedCells.value = []
  track.value = null
  if (f && tool.value === 'road') tool.value = 'select'
})

/** 点了标尺：编辑时选中那一行（列）的区块或小区，并显示它的大小 */
function onTrack(axis: 'row' | 'col', index: number) {
  if (mode.value !== 'edit') {
    return
  }
  const l = draft.draft.value
  if (!l) {
    return
  }
  track.value = { axis, index }
  if (focusBlock.value) {
    const b = l.blocks.find((x) => x.id === focus.value)
    if (!b?.inner) {
      return
    }
    selectedCells.value = anchors(b.inner.rows.length, b.inner.cols.length, b.inner.merges)
      .filter((sp) => (axis === 'row' ? index >= sp.r && index < sp.r + sp.rs : index >= sp.c && index < sp.c + sp.cs))
      .map((sp) => `${sp.r},${sp.c}`)
  } else {
    selectedBlocks.value = []
  }
}

function onMoveFit(id: string, x: number, y: number) {
  const l = draft.draft.value
  if (l) {
    draft.apply(moveBlockFit(l, id, x, y))
  }
}

/** 拖分界线改行高列宽 */
function onResizeTrack(r: TrackResize) {
  const l = draft.draft.value
  if (!l) {
    return
  }
  if (r.scope === 'field') {
    draft.apply(setTrackPair(l, r.axis, r.index, r.a, r.b))
  } else if (focus.value && r.b != null) {
    draft.apply(setInnerTrackPair(l, focus.value, r.axis, r.index, r.a, r.b))
  }
}

// ---------- 道路 ----------

/** 道路工具加的道路多宽（单位同布局）；点开道路工具时按已有道路的宽 */
const roadWidth = ref(1)

/** 道路工具点了区块之间的线或道路 */
function onRoad(r: RoadAction) {
  const l = draft.draft.value
  if (!l) return
  if (r.action === 'add') {
    const problem = addRoadProblem(l, r.axis, r.index)
    if (problem) {
      ElMessage.warning(problem)
      return
    }
    draft.apply(addRoad(l, r.axis, r.index, roadWidth.value))
  } else {
    draft.apply(removeRoad(l, r.axis, r.index))
  }
  selectedBlocks.value = []
  track.value = null
}

/** 在区块的上下左右加一条道路（右键菜单） */
function roadBeside(blockId: string, side: 'top' | 'bottom' | 'left' | 'right') {
  const l = draft.draft.value
  const b = l?.blocks.find((x) => x.id === blockId)
  if (!l || !b) return
  const sp = blockSpan(l, b)
  const axis: Axis = side === 'top' || side === 'bottom' ? 'row' : 'col'
  const index = side === 'top' ? sp.r : side === 'bottom' ? sp.r + sp.rs : side === 'left' ? sp.c : sp.c + sp.cs
  onRoad({ action: 'add', axis, index })
}

/** 区块所在的这一行（列）改成道路：里面的小区会去掉，先问 */
async function trackOfBlockToRoad(blockId: string, axis: Axis) {
  const l = draft.draft.value
  const b = l?.blocks.find((x) => x.id === blockId)
  if (!l || !b) return
  const sp = blockSpan(l, b)
  const index = axis === 'row' ? sp.r : sp.c
  const problem = trackToRoadProblem(l, axis, index)
  if (problem) {
    ElMessage.warning(problem)
    return
  }
  const next = trackToRoad(l, axis, index)
  const gone = removedPlots(l, next)
  if (gone.length > 0) {
    try {
      await ElMessageBox.confirm(
        `第 ${index + 1} ${axis === 'row' ? '行' : '列'}改成道路，会去掉里面 ${gone.length} 个${U.value}。已经种了、录了数据的保存时不能去掉。`,
        '改成道路', { type: 'warning', confirmButtonText: '改成道路', cancelButtonText: '取消' },
      )
    } catch {
      return
    }
  }
  draft.apply(next)
  selectedBlocks.value = []
}

/** 右键的区块在不在一条整条道路上 */
const editMenuRoad = computed(() => {
  const l = draft.draft.value
  const id = editMenu.value?.blockId
  return l && id ? roadOfBlock(l, id) : null
})

const editMenuBlock = computed(() => {
  const id = editMenu.value?.blockId
  return id ? draft.draft.value?.blocks.find((b) => b.id === id) ?? null : null
})

/** 右键的区块四边能不能加道路：不能时是原因（比如旁边已经是道路） */
const roadSideProblems = computed(() => {
  const l = draft.draft.value
  const b = editMenuBlock.value
  if (!l || !b) return { top: null, bottom: null, left: null, right: null }
  const sp = blockSpan(l, b)
  return {
    top: addRoadProblem(l, 'row', sp.r),
    bottom: addRoadProblem(l, 'row', sp.r + sp.rs),
    left: addRoadProblem(l, 'col', sp.c),
    right: addRoadProblem(l, 'col', sp.c + sp.cs),
  } as Record<'top' | 'bottom' | 'left' | 'right', string | null>
})

function roadToBlocksAt(r: { axis: Axis; index: number }) {
  const l = draft.draft.value
  if (l) draft.apply(roadToBlocks(l, r.axis, r.index))
  selectedBlocks.value = []
}

function removeRoadAt(r: { axis: Axis; index: number }) {
  onRoad({ action: 'remove', ...r })
}

/** 拖选中的格子：小区跟着挪，原来那里的小区换过来 */
function onMoveCells(blockId: string, keys: string[], dr: number, dc: number) {
  const l = draft.draft.value
  if (!l) {
    return
  }
  const next = moveCells(l, blockId, keys, dr, dc)
  if (next === l) {
    return
  }
  draft.apply(next)
  selectedCells.value = keys.map((k) => {
    const [r, c] = parseKey(k)
    return keyOf(r + dr, c + dc)
  })
}

/** 把区块拖到另一个区块上：交换位置 */
function onSwapBlocks(a: string, b: string) {
  const l = draft.draft.value
  if (!l) {
    return
  }
  const next = swapBlocks(l, a, b)
  if (next !== l) {
    draft.apply(next)
    selectedBlocks.value = [a]
  }
}

function plotCode(id: number): string {
  const p = plotById.value.get(id)
  return p ? p.code || `${p.rowNo}-${p.colNo}` : `#${id}`
}

async function saveLayout() {
  const l = draft.draft.value
  if (!l || savingLayout.value) {
    return
  }
  const problem = checkLayout(l)
  if (problem) {
    ElMessage.warning(problem)
    return
  }
  const gone = draft.removed.value
  if (gone.length > 0) {
    try {
      await ElMessageBox.confirm(
        `保存后会去掉 ${gone.length} 个小区（${gone.slice(0, 10).map(plotCode).join('、')}${gone.length > 10 ? ' 等' : ''}）`
          + `${draft.added.value ? `，新建 ${draft.added.value} 个小区` : ''}。已经分配了品种、录了数据的小区不能去掉，会提示你先清掉。`,
        '保存布局', { type: 'warning', confirmButtonText: '保存', cancelButtonText: '取消' },
      )
    } catch {
      return
    }
  }
  savingLayout.value = true
  try {
    const d = await fieldApi.saveLayout(fieldId.value, l)
    detail.value = d
    draft.begin()
    selected.value = []
    await loadTrialData().catch(() => undefined)
    ElMessage.success(`已保存布局，共 ${d.plots.length} 个小区`)
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    savingLayout.value = false
  }
}

async function discardLayout() {
  if (!draft.dirty.value) {
    return
  }
  try {
    await ElMessageBox.confirm('丢掉这次的所有改动，回到保存的布局？', '放弃改动', {
      type: 'warning', confirmButtonText: '放弃改动', cancelButtonText: '取消',
    })
  } catch {
    return
  }
  draft.begin()
  selectedBlocks.value = []
  selectedCells.value = []
  track.value = null
}

/** 画布上画的布局：编辑时画草稿 */
const shownLayout = computed(() => (mode.value === 'edit' ? draft.draft.value : fieldLayout.value))

onBeforeRouteLeave(async () => {
  if (mode.value !== 'edit' || !draft.dirty.value) {
    return true
  }
  try {
    await ElMessageBox.confirm('地块布局改了还没保存，离开后改动会丢掉。', '还没保存', {
      type: 'warning', confirmButtonText: '不保存，离开', cancelButtonText: '留在这里',
    })
    return true
  } catch {
    return false
  }
})

interface Display {
  view: ViewMode
  /** "按指标"着色时用的项目 */
  traitId: number | null
}

const DISPLAY_KEY = 'tudijiance.mapDisplay.v3'

function loadDisplay(): Display {
  const fallback: Display = { view: 'status', traitId: null }
  try {
    const saved = JSON.parse(localStorage.getItem(DISPLAY_KEY) || '{}') as Partial<Display>
    return {
      view: saved.view ?? fallback.view,
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

// 布置试验、编辑地块时看品种分布更方便，录入时看状态
watch(mode, (m) => {
  display.value.view = m === 'record' ? 'status' : 'variety'
})

/** 画布缩放比例（相对"正好放下"的百分比） */
const zoom = ref(100)
const tool = ref<CanvasTool>('select')

watch(tool, (t) => {
  if (t === 'road' && draft.draft.value) roadWidth.value = defaultRoadWidth(draft.draft.value)
})
const canvasRef = ref<InstanceType<typeof FieldCanvas> | null>(null)

function zoomBy(factor: number) {
  if (view3d.value) sceneRef.value?.zoomBy(factor)
  else canvasRef.value?.zoomBy(factor)
}

const NO_LINKS: CanvasLink[] = []
const NO_DEVICES: CanvasDevice[] = []
/** 画布上浮着的工具条占的地方：上面模式切换，下面操作条 */
const CANVAS_INSETS = { top: 58, right: 16, bottom: 66, left: 16 }

// ---------- 视角和方位 ----------

/** 俯视图：按图纸朝上，或者按真北方向转到正北朝上 */
type Facing = 'drawing' | 'north'

/** 俯视图（平面图，能编辑）或 3D（自由转动看立体样子） */
type ViewKind = '2d' | '3d'

interface ViewPrefs {
  facing: Facing
  kind: ViewKind
}

const viewKey = () => `tudijiance.mapView.${fieldId.value}`

function loadView(): ViewPrefs {
  try {
    const v = JSON.parse(localStorage.getItem(viewKey()) || '{}') as Partial<ViewPrefs>
    return { facing: v.facing === 'north' ? 'north' : 'drawing', kind: v.kind === '3d' ? '3d' : '2d' }
  } catch {
    return { facing: 'drawing', kind: '2d' }
  }
}

const viewPrefs = ref<ViewPrefs>(loadView())
const facing = computed({
  get: () => viewPrefs.value.facing,
  set: (f: Facing) => { viewPrefs.value = { ...viewPrefs.value, facing: f } },
})
const northDeg = computed(() => shownLayout.value?.northDeg ?? 0)

/** 画面转多少度：图纸朝上不转，正北朝上按真北方向转 */
const rotation = computed(() => (facing.value === 'north' ? northUpRotation(northDeg.value) : 0))

watch(viewPrefs, (v) => {
  try {
    localStorage.setItem(viewKey(), JSON.stringify(v))
  } catch {
    // 不记忆
  }
}, { deep: true })

function toggleNorth() {
  facing.value = facing.value === 'north' ? 'drawing' : 'north'
}

/** 编辑地块只能在俯视图里 */
const view3d = computed({
  get: () => viewPrefs.value.kind === '3d' && mode.value !== 'edit',
  set: (v: boolean) => { viewPrefs.value = { ...viewPrefs.value, kind: v ? '3d' : '2d' } },
})
const sceneRef = ref<InstanceType<typeof FieldScene3DType> | null>(null)
const autoRotate = ref(false)

watch(view3d, () => {
  autoRotate.value = false
  tool.value = 'select'
})

const edges = computed(() => edgeLabels(northDeg.value, rotation.value))

function fitView() {
  if (view3d.value) sceneRef.value?.home()
  else canvasRef.value?.fit(true)
}

// ---------- 进入区块 ----------

const focusBlock = computed(() => shownLayout.value?.blocks.find((b) => b.id === focus.value) ?? null)

function enterBlock(id: string) {
  const b = shownLayout.value?.blocks.find((x) => x.id === id)
  if (!b || b.kind !== 'PLANT') {
    return
  }
  focus.value = id
  selected.value = []
}

function leaveBlock() {
  focus.value = null
}

const layoutStats = computed(() => {
  const l = fieldLayout.value
  if (!l) {
    return { blocks: 0, plots: 0, size: '' }
  }
  const s = fieldSize(l)
  return {
    blocks: l.blocks.filter((b) => b.kind === 'PLANT').length,
    plots: plots.value.length,
    size: `${fmt(s.w, l.unit)} × ${fmt(s.h, l.unit)}`,
  }
})

watch(() => shownLayout.value, (l) => {
  if (focus.value && !l?.blocks.some((b) => b.id === focus.value && b.kind === 'PLANT')) {
    focus.value = null
  }
  // 草稿里没有了的区块不再选着
  if (l && selectedBlocks.value.some((id) => !l.blocks.some((b) => b.id === id))) {
    selectedBlocks.value = selectedBlocks.value.filter((id) => l.blocks.some((b) => b.id === id))
  }
})


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

/** 区块布局时每个小区在哪个区块的第几行第几列 */
const placeById = computed(() => (detail.value?.layoutSaved ? plotPlaces(fieldLayout.value) : new Map<number, string>()))

const cells = computed(() => {
  const out = new Map<number, CanvasCell>()
  for (const p of plots.value) {
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
      } else {
        const extra = [planting?.replicateNo ? `重复 ${replicateLabel(planting.replicateNo)}` : '', planting?.generation ? `F${planting.generation}` : '']
          .filter(Boolean).join(' · ')
        if (extra) lines.push(extra)
      }
    }
    const pct = comparePct.value.get(p.id)
    const badge = [
      variety?.controlType || controlPlotIds.value.has(p.id) ? 'CK' : '', pct != null ? pctText(pct) : '', st?.status === 'ABNORMAL' ? '!' : '',
    ].filter(Boolean).join(' ')
    const place = placeById.value.get(p.id) ?? null
    const tip = [
      place ? `${U.value} ${code} · ${place}` : `${U.value} ${code}`,
      p.kind === 'PLOT'
        ? (isBreeding.value ? `品系：${variety ? `${variety.name}${variety.lineCode && variety.lineCode !== variety.name ? `（${variety.lineCode}）` : ''}` : '还没种'}${planting?.generation ? ` · F${planting.generation}` : ''}` : `品种：${variety?.name ?? '未分配'}`)
        : PLOT_KIND[p.kind],
      planting?.replicateNo ? `重复：${replicateLabel(planting.replicateNo)}` : '',
      p.kind === 'PLOT' && trial.value ? `状态：${PLOT_STATUS[plotStatus(p)].label}${st?.abnormalNote ? '（' + st.abnormalNote + '）' : ''}` : '',
      st?.enteredToday ? '今天录过数据' : '',
      display.value.view === 'trait' && metricTrait.value
        ? `${metricTrait.value.name}：${traitValues.value.get(p.id)?.displayValue ?? '无数据'}` : '',
      p.lengthM && p.widthM ? `尺寸：${p.widthM} m × ${p.lengthM} m` : '',
    ].filter(Boolean).join('\n')
    out.set(p.id, {
      lines, fill: fillOf(p), badge: badge || undefined, marker: st?.enteredToday, tooltip: tip, muted: p.kind !== 'PLOT',
    })
  }
  return out
})

/** 3D 里每个小区立起来多高（米）：按指标着色时按指标的值，否则按长势 */
const STATUS_HEIGHT: Partial<Record<PlotStatusCode, number>> = { NONE: 0.06, GROWING: 0.55, ABNORMAL: 0.35, HARVESTED: 0.12 }

const heights = computed(() => {
  const out = new Map<number, number>()
  const r = metricRange.value
  for (const p of plots.value) {
    if (p.kind !== 'PLOT') {
      out.set(p.id, 0.04)
    } else if (display.value.view === 'trait') {
      const v = traitValues.value.get(p.id)
      out.set(p.id, v && r ? 0.12 + 1.3 * (r.max === r.min ? 1 : (v.value - r.min) / (r.max - r.min)) : 0.04)
    } else {
      out.set(p.id, trial.value ? STATUS_HEIGHT[plotStatus(p)] ?? 0.3 : 0.3)
    }
  }
  return out
})

// 在右侧"对照关系"里点了哪一组；再点一次取消
const pinnedLink = ref<number | null>(null)

function togglePinnedLink(id: number) {
  pinnedLink.value = pinnedLink.value === id ? null : id
  if (pinnedLink.value !== null) {
    selected.value = []
  }
}

/**
 * 现在看的是哪一组对照：右侧点了某一组；或者只选了一个小区，它在哪一组里（优先它是被比较的那一组）
 */
const activeLink = computed(() => {
  const links = layout.value?.links ?? []
  if (pinnedLink.value !== null) {
    return links.find((link) => link.id === pinnedLink.value) ?? null
  }
  if (selected.value.length !== 1) {
    return null
  }
  const id = selected.value[0]
  return links.find((link) => link.plots.some((p) => p.plotId === id && !p.control))
    ?? links.find((link) => link.plots.some((p) => p.plotId === id)) ?? null
})

watch(selected, (ids) => {
  if (ids.length > 0) {
    pinnedLink.value = null
  }
})

/** 对照小区正好是某个区块的全部试验小区：这个区块是对照组 */
function controlBlocksOf(controls: number[]): string[] {
  const l = fieldLayout.value
  if (!l) {
    return []
  }
  const set = new Set(controls)
  return l.blocks.filter((b) => {
    const ids = Object.values(b.inner?.plots ?? {}).filter((id) => plotById.value.get(id)?.kind === 'PLOT')
    return ids.length > 0 && ids.every((id) => set.has(id))
  }).map((b) => b.id)
}

/** 平面图上不画连线：对照描红边（整个区块是对照组时整块描边），点开的一组再把比较的小区描出来 */
const gridLinks = computed<CanvasLink[]>(() =>
  (layout.value?.links ?? []).map((link) => {
    const controls = link.plots.filter((p) => p.control).map((p) => p.plotId)
    return {
      id: link.id,
      label: link.label,
      controlPlotIds: controls,
      comparePlotIds: link.plots.filter((p) => !p.control).map((p) => p.plotId),
      controlBlockIds: controlBlocksOf(controls),
      active: activeLink.value?.id === link.id,
    }
  }),
)

/** 当对照的小区（任何一组里） */
const controlPlotIds = computed(() => new Set((layout.value?.links ?? []).flatMap((l) => l.plots.filter((p) => p.control).map((p) => p.plotId))))

// ---------- 和对照比 ----------

const compareTraitId = ref<number | null>(null)
const compareValues = ref(new Map<number, PlotValue>())
const compareTrait = computed(() => catalog.traitById.get(compareTraitId.value ?? -1)?.trait ?? null)

async function loadCompareValues() {
  if (!activeLink.value || !trialId.value) {
    return
  }
  if (!compareTraitId.value) {
    compareTraitId.value = (display.value.view === 'trait' ? display.value.traitId : null)
      ?? catalog.statTraits.find((t) => t.trait.code === 'yield_per_mu')?.trait.id
      ?? catalog.statTraits[0]?.trait.id ?? null
  }
  if (!compareTraitId.value) {
    return
  }
  try {
    const list = await observationApi.traitValues(trialId.value, compareTraitId.value)
    compareValues.value = new Map(list.map((v) => [v.plotId, v]))
  } catch (e) {
    compareValues.value = new Map()
    ElMessage.error((e as Error).message)
  }
}

watch([() => activeLink.value?.id, compareTraitId, trialId], loadCompareValues)

const roundTo = (v: number) => Math.round(v * 100) / 100

/** 点开的一组对照：对照的平均值，每个比较的小区比对照高多少（按百分比从高到低） */
const comparison = computed(() => {
  const link = activeLink.value
  if (!link) {
    return null
  }
  const vals = compareValues.value
  const controls = link.plots.filter((p) => p.control)
  const ck = controls.map((p) => vals.get(p.plotId)?.value).filter((v): v is number => v != null)
  const mean = ck.length ? ck.reduce((a, b) => a + b, 0) / ck.length : null
  const rows = link.plots.filter((p) => !p.control).map((lp) => {
    const v = vals.get(lp.plotId)
    const pct = v && mean ? ((v.value - mean) / Math.abs(mean)) * 100 : null
    const planting = plantingByPlot.value.get(lp.plotId)
    return {
      plotId: lp.plotId,
      code: plotCode(lp.plotId),
      name: planting?.varietyId ? catalog.varietyById.get(planting.varietyId)?.name ?? '' : '',
      display: v ? String(roundTo(v.value)) : '没数据',
      pct,
    }
  }).sort((a, b) => (b.pct ?? -Infinity) - (a.pct ?? -Infinity))
  const blocks = controlBlocksOf(controls.map((p) => p.plotId))
  const blockName = blocks.length === 1 ? fieldLayout.value?.blocks.find((b) => b.id === blocks[0])?.name ?? '' : ''
  return {
    link, rows, mean: mean == null ? null : roundTo(mean), withData: ck.length, controls: controls.length, blockName,
    better: rows.filter((r) => r.pct != null && r.pct > 0).length,
  }
})

const comparePct = computed(() => new Map((comparison.value?.rows ?? []).filter((r) => r.pct != null).map((r) => [r.plotId, r.pct!])))

function pctText(v: number): string {
  return `${v > 0 ? '+' : ''}${Math.round(v * 10) / 10}%`
}

const compareShowAll = ref(false)
watch(() => activeLink.value?.id, () => { compareShowAll.value = false })

// 传感器（第二期）：登记时填的是第几行第几列，画在那个小区的中间
const DEVICE_COLORS: Record<string, string> = { ONLINE: '#2f9e44', OFFLINE: '#868e96', NEVER: '#f08c00' }

const gridDevices = computed<CanvasDevice[]>(() => {
  const l = fieldLayout.value
  if (!l) {
    return []
  }
  const centerOf = new Map<number, { x: number; y: number }>()
  for (const b of l.blocks) {
    for (const c of innerCells(l, b)) {
      if (c.plotId != null) {
        centerOf.set(c.plotId, { x: c.rect.x + c.rect.w / 2, y: c.rect.y + c.rect.h / 2 })
      }
    }
  }
  return devices.value.flatMap((d) => {
    const p = d.posRow && d.posCol
      ? plots.value.find((x) => x.rowNo === Math.round(d.posRow!) && x.colNo === Math.round(d.posCol!)) : undefined
    const at = p ? centerOf.get(p.id) : undefined
    return at ? [{ id: d.id, ...at, label: d.name, color: DEVICE_COLORS[d.status] ?? '#868e96' }] : []
  })
})

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

// ---------- 右键菜单 ----------

const menu = ref<{ x: number; y: number; plotId: number | null; blockId: string | null } | null>(null)

function onContextMenu(target: { plotId?: number; blockId?: string; cell?: string }, x: number, y: number) {
  // 育种的地块菜单长一些
  const tall = mode.value === 'edit' ? 470 : isBreeding.value ? 480 : 340
  const at = { x: Math.min(x, window.innerWidth - 220), y: Math.max(8, Math.min(y, window.innerHeight - tall)) }
  if (mode.value === 'edit') {
    if (target.blockId || target.cell) editMenu.value = { ...at, blockId: target.blockId, cell: target.cell }
    return
  }
  // 育种的地块：右键区块名称牌也有菜单（整块种、从这个区块选株）
  if (target.plotId == null && !(isBreeding.value && target.blockId)) {
    return
  }
  // 菜单不要超出窗口
  menu.value = { plotId: target.plotId ?? null, blockId: target.blockId ?? null, ...at }
}

function closeMenu() {
  menu.value = null
  editMenu.value = null
}

function typing(e: KeyboardEvent) {
  const t = e.target as HTMLElement | null
  return !!t && (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA' || t.isContentEditable)
}

function onKey(e: KeyboardEvent) {
  const ctrl = e.ctrlKey || e.metaKey
  // 找小区：/ 或 Ctrl F（输入框里不拦）
  if ((e.key === '/' && !typing(e)) || (ctrl && e.key.toLowerCase() === 'f')) {
    e.preventDefault()
    searchInput.value?.focus()
    return
  }
  if (typing(e)) {
    return
  }
  // 有弹窗、抽屉开着时不抢快捷键
  if (document.querySelector('.el-overlay:not([style*="display: none"])')) {
    return
  }
  if (mode.value === 'edit' && ctrl && !e.altKey) {
    const k = e.key.toLowerCase()
    if (k === 'z' && !e.shiftKey) {
      e.preventDefault()
      draft.undo()
      return
    }
    if (k === 'y' || (k === 'z' && e.shiftKey)) {
      e.preventDefault()
      draft.redo()
      return
    }
    if (k === 's') {
      e.preventDefault()
      saveLayout()
      return
    }
  }
  if (ctrl && e.key.toLowerCase() === 'a') {
    e.preventDefault()
    if (mode.value !== 'edit') {
      selectAllPlots()
    } else if (focusBlock.value?.inner) {
      const b = focusBlock.value
      selectedCells.value = anchors(b.inner!.rows.length, b.inner!.cols.length, b.inner!.merges).map((x) => keyOf(x.r, x.c))
    } else {
      selectedBlocks.value = (draft.draft.value?.blocks ?? []).map((b) => b.id)
    }
    return
  }
  if (ctrl || e.altKey) {
    return
  }
  const arrows: Record<string, [number, number]> = { ArrowUp: [-1, 0], ArrowDown: [1, 0], ArrowLeft: [0, -1], ArrowRight: [0, 1] }
  if (arrows[e.key] && mode.value !== 'edit') {
    if (stepSelection(...arrows[e.key]!)) e.preventDefault()
    return
  }
  if (e.key === 'Enter' && mode.value === 'record' && selected.value.length > 0 && auth.canRecord && trial.value) {
    e.preventDefault()
    openEntry(selectedPlots.value)
    return
  }
  if (e.key === 'Escape') {
    if (menu.value || editMenu.value) {
      closeMenu()
    } else if (tool.value !== 'select') {
      tool.value = 'select'
    } else if (selected.value.length || selectedBlocks.value.length || selectedCells.value.length || track.value) {
      selected.value = []
      selectedBlocks.value = []
      selectedCells.value = []
      track.value = null
    } else if (focus.value) {
      leaveBlock()
    }
    return
  }
  const k = e.key.toLowerCase()
  if (auth.isTeacher && (k === '1' || k === '2' || k === '3')) {
    modeModel.value = (['record', 'layout', 'edit'] as Mode[])[Number(k) - 1]!
  } else if (k === 'f') {
    fitView()
  } else if (k === 'n') {
    toggleNorth()
  } else if (k === 't' && mode.value !== 'edit') {
    view3d.value = !view3d.value
  } else if (k === 'm' && !view3d.value) {
    tool.value = tool.value === 'measure' ? 'select' : 'measure'
  } else if (k === 'h' && !view3d.value) {
    tool.value = tool.value === 'pan' ? 'select' : 'pan'
  } else if (k === 'r' && mode.value === 'edit' && !focus.value) {
    tool.value = tool.value === 'road' ? 'select' : 'road'
  } else if (k === 'v') {
    tool.value = 'select'
  } else if (e.key === ']') {
    inspectorOpen.value = !inspectorOpen.value
  } else if (e.key === '?') {
    shortcutsOpen.value = true
  }
}

onMounted(() => {
  window.addEventListener('click', closeMenu)
  window.addEventListener('keydown', onKey)
})
onBeforeUnmount(() => {
  window.removeEventListener('click', closeMenu)
  window.removeEventListener('keydown', onKey)
})

const menuPlot = computed(() => (menu.value?.plotId != null ? plotById.value.get(menu.value.plotId) ?? null : null))
/** 右键的是哪个区块（右键小区块时是它所在的区块） */
const menuBlock = computed(() => {
  if (!menu.value || !fieldLayout.value) return null
  const id = menu.value.blockId ?? (menu.value.plotId != null ? blockOfPlot(menu.value.plotId) : null)
  return fieldLayout.value.blocks.find((b) => b.id === id) ?? null
})

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
  } else if (cmd === 'book' && trial.value) {
    const name = [field.value?.name, trial.value.seasonYear, '记载本'].filter(Boolean).join('-')
    statsApi.exportRecordBook(trial.value.id, `${name}.xlsx`).catch((e) => ElMessage.error((e as Error).message))
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

/** 种植区块按从上到下、从左到右的顺序 */
const plantBlocks = computed(() => (fieldLayout.value?.blocks ?? [])
  .filter((b) => b.kind === 'PLANT' && b.inner && Object.keys(b.inner.plots).length > 0)
  .sort((a, b) => a.r - b.r || a.c - b.c))

/** 每个区块现在的重复号（都一样时显示，不一样时为空） */
const blockReplicates = computed(() => plantBlocks.value.map((b) => {
  const reps = new Set(Object.values(b.inner!.plots)
    .filter((id) => plotById.value.get(id)?.kind === 'PLOT')
    .map((id) => plantingByPlot.value.get(id)?.replicateNo ?? null))
  return { block: b, rep: reps.size === 1 ? [...reps][0] ?? null : null, mixed: reps.size > 1 }
}))

const assigningReps = ref(false)

/** 一个区块一个重复：区块1 → 重复Ⅰ，区块2 → 重复Ⅱ…… */
async function replicatesByBlock() {
  if (!trialId.value || plantBlocks.value.length === 0) {
    return
  }
  const already = blockReplicates.value.some((x) => x.rep != null || x.mixed)
  try {
    await ElMessageBox.confirm(
      `按区块顺序设重复：${plantBlocks.value.slice(0, 4).map((b, i) => `${b.name} → 重复 ${replicateLabel(i + 1)}`).join('，')}`
        + `${plantBlocks.value.length > 4 ? '……' : ''}。${already ? '已经设过的重复号会改掉。' : ''}`,
      '按区块设重复', { confirmButtonText: '设重复', cancelButtonText: '取消', type: already ? 'warning' : 'info' },
    )
  } catch {
    return
  }
  assigningReps.value = true
  try {
    let l: Layout | null = null
    for (const [i, b] of plantBlocks.value.entries()) {
      const ids = Object.values(b.inner!.plots).filter((id) => plotById.value.get(id)?.kind === 'PLOT')
      if (ids.length) {
        l = await trialApi.assign(trialId.value, ids, 'REPLICATE', i + 1)
      }
    }
    if (l) {
      onLayoutSaved(l)
    }
    ElMessage.success(`已按 ${plantBlocks.value.length} 个区块设好重复`)
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    assigningReps.value = false
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

/** 对照是哪些：整个区块写区块名，否则写小区编号 */
function linkControlText(l: { plots: { plotId: number; control: boolean }[] }): string {
  const ids = l.plots.filter((p) => p.control).map((p) => p.plotId)
  const blocks = controlBlocksOf(ids)
  if (blocks.length > 0) {
    const names = blocks.map((id) => fieldLayout.value?.blocks.find((b) => b.id === id)?.name ?? '')
    return `${names.join('、')}（${ids.length} 个）`
  }
  return linkPlotsText(ids)
}

async function onLinkSaved(l: Layout) {
  onLayoutSaved(l)
  const last = l.links[l.links.length - 1]
  if (last) {
    selected.value = []
    pinnedLink.value = last.id
  }
}

function linkPlotsText(plotIds: number[]): string {
  return plotIds.map((id) => {
    const p = plotById.value.get(id)
    return p ? p.code || `${p.rowNo}-${p.colNo}` : '?'
  }).join('、')
}

// ---------- 育种的地块：种植、选株、杂交 ----------

const candidates = ref<Candidates | null>(null)

async function loadCandidates() {
  if (!isBreeding.value) return
  try {
    candidates.value = await breedingApi.candidates(fieldId.value)
  } catch {
    candidates.value = null
  }
}

// 在别处新建了品系（新建品种、杂交、选株）：种植的下拉框跟着更新
watch(() => breeding.graph, () => {
  if (isBreeding.value) void loadCandidates()
})

/** 小区块在哪个区块 */
function blockOfPlot(plotId: number): string | null {
  for (const b of fieldLayout.value?.blocks ?? []) {
    if (b.inner && Object.values(b.inner.plots).includes(plotId)) return b.id
  }
  return null
}

/** 区块里能种的小区块 */
function plantPlotsOfBlock(blockId: string): number[] {
  const b = fieldLayout.value?.blocks.find((x) => x.id === blockId)
  return Object.values(b?.inner?.plots ?? {}).filter((id) => plotById.value.get(id)?.kind === 'PLOT')
}

function lineOf(plotId: number): number | null {
  return plantingByPlot.value.get(plotId)?.varietyId ?? null
}

/** 选中的小区块种的品系（按选的先后，去重） */
const selectedLineIds = computed(() => {
  const out: number[] = []
  for (const p of selectedTrialPlots.value) {
    const id = lineOf(p.id)
    if (id != null && !out.includes(id)) out.push(id)
  }
  return out
})

/** 这块地块种的品系 */
const plantedHere = computed(() => new Set((layout.value?.plantings ?? []).map((p) => p.varietyId).filter((v): v is number => v != null)))

function lineName(id: number | null): string {
  return id == null ? '' : catalog.varietyById.get(id)?.name ?? breeding.lineById.get(id)?.name ?? ''
}

async function afterBreedingChange() {
  await Promise.all([breeding.reload(), loadCandidates()])
}

async function plantLine(lineId: number | null, plotIds: number[]) {
  if (plotIds.length === 0) {
    ElMessage.warning(`请选择要种的${U.value}（道路、保护区、空地不能种）`)
    return
  }
  try {
    const r = await breedingApi.plant(fieldId.value, plotIds, lineId)
    onLayoutSaved(r.layout)
    r.notes.forEach((n) => ElMessage.info(n))
    ElMessage.success(lineId ? `${plotIds.length} 个小区块种上了“${lineName(lineId)}”` : `清掉了 ${plotIds.length} 个小区块`)
    await afterBreedingChange()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

// 种植对话框：给选中的（或者右键的区块的）小区块选品系
const plantOpen = ref(false)
const plantTargets = ref<number[]>([])
const plantTitle = ref<string | undefined>(undefined)

function openPlant(plotIds: number[], title?: string) {
  if (plotIds.length === 0) {
    ElMessage.warning(`请选择要种的${U.value}`)
    return
  }
  plantTargets.value = plotIds
  plantTitle.value = title
  void loadCandidates()
  plantOpen.value = true
}

async function clearPlants(plotIds: number[]) {
  const n = plotIds.filter((id) => lineOf(id) != null).length
  if (n === 0) return
  try {
    await ElMessageBox.confirm(`清掉 ${n} 个小区块种的品系？已经录了数据的不能清。`, '清掉', {
      type: 'warning', confirmButtonText: '清掉', cancelButtonText: '取消',
    })
  } catch {
    return
  }
  await plantLine(null, plotIds)
}

async function setGeneration(plotIds: number[]) {
  const planted = plotIds.filter((id) => lineOf(id) != null)
  if (planted.length === 0) return
  const now = plantingByPlot.value.get(planted[0]!)?.generation
  let value: number | null
  try {
    const res = await ElMessageBox.prompt(
      `${planted.length} 个小区块这一季是 F 几？世代按以前种的自动算，用的是存的老种子等情况可以改。清空表示不标。`, '改世代', {
        inputPattern: /^(|[1-9]|[1-3]\d|40)$/, inputErrorMessage: '请输入 1 到 40 的整数',
        inputValue: now ? String(now) : '', confirmButtonText: '保存', cancelButtonText: '取消',
      })
    value = res.value ? Number(res.value) : null
  } catch {
    return
  }
  try {
    onLayoutSaved((await breedingApi.setGeneration(fieldId.value, planted, value)).layout)
    ElMessage.success('世代已改')
    await afterBreedingChange()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

// 选株：选中的小区块都种的同一个品系，并且在同一个区块里
const selectOpen = ref(false)
const selectLineId = ref<number | null>(null)
const selectLocation = ref<SelectLocation | null>(null)

function locationOf(plotIds: number[]): SelectLocation | null {
  const blocks = new Set(plotIds.map(blockOfPlot))
  if (blocks.size !== 1 || !field.value) return null
  const blockId = [...blocks][0]!
  const block = fieldLayout.value?.blocks.find((b) => b.id === blockId)
  if (plotIds.length === 1) {
    const p = plotById.value.get(plotIds[0]!)
    return { fieldId: fieldId.value, plotId: plotIds[0]!, seasonId: field.value.seasonId, text: `${field.value.name} ${block?.name ?? ''} ${p?.code ?? ''} 号小区块` }
  }
  return { fieldId: fieldId.value, blockId, seasonId: field.value.seasonId, text: `${field.value.name} ${block?.name ?? ''}` }
}

function openSelect(plotIds: number[]) {
  const lines = [...new Set(plotIds.map(lineOf).filter((x): x is number => x != null))]
  if (lines.length !== 1) {
    ElMessage.warning(lines.length === 0 ? '选中的小区块还没种' : '选株要选种着同一个品系的小区块')
    return
  }
  const planted = plotIds.filter((id) => lineOf(id) === lines[0])
  const loc = locationOf(planted)
  if (!loc) {
    ElMessage.warning('选株要在同一个区块里选')
    return
  }
  selectLineId.value = lines[0]!
  selectLocation.value = loc
  selectOpen.value = true
}

// 杂交：选中的小区块里正好有两个品系，先选的是母本
const crossOpen = ref(false)
const crossPair = ref<{ mother: number | null; father: number | null }>({ mother: null, father: null })

function openCross() {
  const ids = selectedLineIds.value
  if (ids.length !== 2) {
    ElMessage.warning('杂交要选种着两个不同品系的小区块（先选的是母本）')
    return
  }
  crossPair.value = { mother: ids[0]!, father: ids[1]! }
  crossOpen.value = true
}

// 拖品系到平面图上种
const dropHint = ref<string | null>(null)

function dropTargets(hit: { plotId?: number; blockId?: string; onTag: boolean } | null | undefined): { ids: number[]; text: string } {
  if (!hit?.blockId) return { ids: [], text: '' }
  const block = fieldLayout.value?.blocks.find((b) => b.id === hit.blockId)
  if (!block || block.kind !== 'PLANT') return { ids: [], text: '' }
  if (hit.onTag || hit.plotId == null) {
    return { ids: plantPlotsOfBlock(block.id), text: `整块种：${block.name}` }
  }
  if (selected.value.includes(hit.plotId) && selectedTrialPlots.value.length > 1) {
    return { ids: selectedTrialPlots.value.map((p) => p.id), text: `种到选中的 ${selectedTrialPlots.value.length} 个小区块` }
  }
  const p = plotById.value.get(hit.plotId)
  if (!p || p.kind !== 'PLOT') return { ids: [], text: '道路、保护区、空地不能种' }
  return { ids: [p.id], text: `种到 ${block.name} ${p.code ?? ''} 号小区块` }
}

function onStageDragOver(e: DragEvent) {
  if (!isBreeding.value || !auth.isTeacher || view3d.value || mode.value === 'edit') return
  if (!e.dataTransfer?.types.includes('application/x-line-id')) return
  const t = dropTargets(canvasRef.value?.hitAt(e.clientX, e.clientY))
  dropHint.value = t.text || '拖到区块或小区块上'
  if (t.ids.length) {
    e.preventDefault()
    e.dataTransfer.dropEffect = 'copy'
  }
}

async function onStageDrop(e: DragEvent) {
  dropHint.value = null
  const lineId = Number(e.dataTransfer?.getData('application/x-line-id'))
  if (!lineId) return
  e.preventDefault()
  const t = dropTargets(canvasRef.value?.hitAt(e.clientX, e.clientY))
  if (t.ids.length) await plantLine(lineId, t.ids)
}

function locateLine(lineId: number) {
  const ids = plots.value.filter((p) => lineOf(p.id) === lineId).map((p) => p.id)
  if (ids.length) locatePlot(ids)
  else ElMessage.info('这块地块没种它')
}

function openLine(lineId: number) {
  router.push(`/breeding/lines/${lineId}`)
}

/** 右键菜单里种植的目标：右键的小区块在选中的里面时种全部选中的，否则只种它 */
function menuTargets(): number[] {
  const p = menuPlot.value
  if (!p) return []
  if (selected.value.includes(p.id) && selectedTrialPlots.value.length > 1) return selectedTrialPlots.value.map((x) => x.id)
  return p.kind === 'PLOT' ? [p.id] : []
}

function runMenuBlock(action: (blockId: string) => void) {
  const b = menuBlock.value
  closeMenu()
  if (b) action(b.id)
}

/** 录入的小区块正好是整个区块、种的同一个品系时：可以按整块录一个值 */
const entryWholeBlock = computed(() => {
  const ids = entryPlots.value.map((p) => p.id)
  if (ids.length < 2) return null
  const blockId = blockOfPlot(ids[0]!)
  if (!blockId) return null
  const all = plantPlotsOfBlock(blockId)
  if (all.length !== ids.length || !all.every((id) => ids.includes(id))) return null
  const lines = new Set(ids.map(lineOf))
  if (lines.size !== 1 || lines.has(null)) return null
  return { name: menuBlockName(blockId) }
})

function on3dUnsupported() {
  view3d.value = false
  ElMessage.warning('这台电脑的显卡画不了 3D（不支持 WebGL），先用俯视图')
}

function onBreedingMore(cmd: string) {
  const ids = selectedTrialPlots.value.map((p) => p.id)
  if (cmd === 'generation') void setGeneration(ids)
  else if (cmd === 'clear') void clearPlants(ids)
  else if (cmd === 'numbering') numberingDialog.value = true
  else if (cmd === 'kind') plotDialog.value = true
}

/** 右键菜单"种植"子菜单里每组最多列几个，更多的点"搜全部品系" */
const MENU_LINES = 8

const menuGroups = computed(() => (candidates.value?.groups ?? [])
  .filter((g) => g.lines.length > 0)
  .map((g) => ({ ...g, lines: g.lines.slice(0, MENU_LINES) })))

/** 先记下右键菜单的目标再关菜单 */
function runTargets(action: (ids: number[]) => void) {
  const ids = menuTargets()
  closeMenu()
  if (ids.length) action(ids)
}

function menuBlockName(blockId: string): string {
  return fieldLayout.value?.blocks.find((b) => b.id === blockId)?.name ?? ''
}

/** 区块里种的品系：都一样时返回它，否则为空 */
function blockLine(blockId: string): number | null {
  const lines = new Set(plantPlotsOfBlock(blockId).map(lineOf).filter((x): x is number => x != null))
  return lines.size === 1 ? [...lines][0]! : null
}

const winWidth = ref(window.innerWidth)
const winHeight = ref(window.innerHeight)
function onResize() {
  winWidth.value = window.innerWidth
  winHeight.value = window.innerHeight
}
onMounted(() => window.addEventListener('resize', onResize))
onBeforeUnmount(() => window.removeEventListener('resize', onResize))

// ---------- 其它 ----------

function selectAllPlots() {
  const inFocus = focusBlock.value ? new Set(Object.values(focusBlock.value.inner?.plots ?? {})) : null
  selected.value = plots.value.filter((p) => p.kind === 'PLOT' && (!inFocus || inFocus.has(p.id))).map((p) => p.id)
}

function exportMap() {
  const name = [field.value?.name, focusBlock.value?.name, trial.value?.seasonYear, '种植图'].filter(Boolean).join('-')
  if (view3d.value) sceneRef.value?.exportJpeg(`${name}-3D.jpg`)
  else canvasRef.value?.exportJpeg(`${name}.jpg`)
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

// ---------- 右侧面板：按选中的东西显示 ----------

const INSPECTOR_KEY = 'tudijiance.mapInspector'
const inspectorOpen = ref(localStorage.getItem(INSPECTOR_KEY) !== '0')
watch(inspectorOpen, (v) => {
  try {
    localStorage.setItem(INSPECTOR_KEY, v ? '1' : '0')
  } catch {
    // 不记忆
  }
})

/** 只选了一个小区时显示它的最近数据：选中后停一下再取，免得快速点选时连发请求 */
const single = computed(() => (mode.value !== 'edit' && selectedPlots.value.length === 1 ? selectedPlots.value[0]! : null))
const singleDetail = ref<PlotDetail | null>(null)
const singleLoading = ref(false)
let singleTimer = 0

watch([single, trialId], ([p, t]) => {
  clearTimeout(singleTimer)
  singleDetail.value = null
  if (!p || !t || p.kind !== 'PLOT') return
  singleLoading.value = true
  singleTimer = window.setTimeout(async () => {
    try {
      const d = await observationApi.plotDetail(t, p.id)
      if (single.value?.id === p.id) singleDetail.value = d
    } catch {
      // 取不到只是少了最近数据
    } finally {
      singleLoading.value = false
    }
  }, 220)
})

/** 选中的小区：品种、重复、状态各有几个，点一下选中同样的 */
interface Facet {
  key: string
  label: string
  count: number
  color: string
  pick: () => void
}

function selectWhere(pred: (p: Plot) => boolean) {
  const inFocus = focusBlock.value ? new Set(Object.values(focusBlock.value.inner?.plots ?? {})) : null
  selected.value = plots.value.filter((p) => (!inFocus || inFocus.has(p.id)) && pred(p)).map((p) => p.id)
}

const facets = computed(() => {
  const list = selectedTrialPlots.value
  const varieties = new Map<number | null, number>()
  const reps = new Map<number | null, number>()
  const statuses = new Map<PlotStatusCode, number>()
  for (const p of list) {
    const pl = plantingByPlot.value.get(p.id)
    varieties.set(pl?.varietyId ?? null, (varieties.get(pl?.varietyId ?? null) ?? 0) + 1)
    reps.set(pl?.replicateNo ?? null, (reps.get(pl?.replicateNo ?? null) ?? 0) + 1)
    const st = plotStatus(p)
    statuses.set(st, (statuses.get(st) ?? 0) + 1)
  }
  const variety: Facet[] = [...varieties.entries()].sort((a, b) => b[1] - a[1]).map(([id, n]) => {
    const v = id ? catalog.varietyById.get(id) : null
    return {
      key: `v${id}`, label: v ? `${v.name}${v.controlType ? '（CK）' : ''}` : '未分配品种', count: n,
      color: id ? varietyColor.value.get(id) ?? '#ddd' : '#f1ebdd',
      pick: () => selectWhere((p) => p.kind === 'PLOT' && (plantingByPlot.value.get(p.id)?.varietyId ?? null) === id),
    }
  })
  const replicate: Facet[] = [...reps.entries()].sort((a, b) => (a[0] ?? 99) - (b[0] ?? 99)).map(([r, n]) => ({
    key: `r${r}`, label: r ? `重复 ${replicateLabel(r)}` : '未设重复', count: n,
    color: r ? REPLICATE_COLORS[(r - 1) % REPLICATE_COLORS.length]! : '#f1ebdd',
    pick: () => selectWhere((p) => p.kind === 'PLOT' && (plantingByPlot.value.get(p.id)?.replicateNo ?? null) === r),
  }))
  const status: Facet[] = [...statuses.entries()].map(([st, n]) => ({
    key: `s${st}`, label: PLOT_STATUS[st].label, count: n, color: PLOT_STATUS[st].color,
    pick: () => selectWhere((p) => p.kind === 'PLOT' && plotStatus(p) === st),
  }))
  return { variety, replicate, status }
})

// ---------- 图例：点一项选中 ----------

const VIEW_OPTIONS: { value: ViewMode; label: string }[] = [
  { value: 'status', label: '按状态' },
  { value: 'variety', label: '按品种' },
  { value: 'replicate', label: '按重复' },
  { value: 'trait', label: '按指标' },
]

/** 季里的地块按品系着色 */
const viewOptions = computed(() => (isBreeding.value
  ? VIEW_OPTIONS.map((o) => (o.value === 'variety' ? { ...o, label: '按品系' } : o))
  : VIEW_OPTIONS))

function selectByLegend(label: string) {
  if (display.value.view === 'status') {
    const code = (Object.keys(PLOT_STATUS) as PlotStatusCode[]).find((k) => PLOT_STATUS[k].label === label)
    if (code) selectWhere((p) => plotStatus(p) === code)
  } else if (display.value.view === 'variety') {
    const id = [...varietyColor.value.keys()].find((v) => {
      const x = catalog.varietyById.get(v)
      return (x ? `${x.name}${x.controlType ? '（CK）' : ''}` : `品种 ${v}`) === label
    })
    if (id != null) selectWhere((p) => p.kind === 'PLOT' && plantingByPlot.value.get(p.id)?.varietyId === id)
  } else if (display.value.view === 'replicate') {
    const r = [...new Set((layout.value?.plantings ?? []).map((x) => x.replicateNo))].find((n) => n && `重复 ${replicateLabel(n)}` === label)
    if (r) selectWhere((p) => p.kind === 'PLOT' && plantingByPlot.value.get(p.id)?.replicateNo === r)
  }
}

// ---------- 找小区 ----------

const searchText = ref('')
const searchOpen = ref(false)
const searchInput = ref<{ focus: () => void } | null>(null)
const searchIndex = ref(0)

/** 全部匹配的小区：输数字只按编号找（编号开头一样的），输文字时也按品种名找 */
const searchAll = computed(() => {
  const q = searchText.value.trim().toLowerCase()
  if (!q) return []
  const numeric = /^[0-9-]+$/.test(q)
  const out: { plot: Plot; text: string; sub: string; exact: boolean }[] = []
  for (const p of plots.value) {
    const code = (p.code || `${p.rowNo}-${p.colNo}`).toLowerCase()
    const pl = plantingByPlot.value.get(p.id)
    const v = pl?.varietyId ? catalog.varietyById.get(pl.varietyId) : undefined
    const name = v?.name.toLowerCase() ?? ''
    if (code.startsWith(q) || (!numeric && name.includes(q))) {
      out.push({
        plot: p, text: `小区 ${p.code || code}`, exact: code === q,
        sub: [v?.name, pl?.replicateNo ? `重复 ${replicateLabel(pl.replicateNo)}` : '', placeById.value.get(p.id)].filter(Boolean).join(' · '),
      })
    }
  }
  // 编号完全一样的排前面
  return out.sort((a, b) => Number(b.exact) - Number(a.exact))
})

const searchHits = computed(() => searchAll.value.slice(0, 8))

watch(searchText, () => {
  searchIndex.value = 0
  searchOpen.value = true
})

/** 定位到小区：选中、放大过去（在别的区块里时先回到整块地） */
function locatePlot(ids: number[]) {
  if (ids.length === 0) return
  if (focus.value && !ids.every((id) => Object.values(focusBlock.value?.inner?.plots ?? {}).includes(id))) {
    focus.value = null
  }
  selected.value = ids
  nextTick(() => {
    if (view3d.value) sceneRef.value?.zoomToPlots(ids)
    else canvasRef.value?.zoomToPlots(ids)
  })
}

function pickHit(i: number) {
  const hit = searchHits.value[i]
  if (!hit) return
  searchOpen.value = false
  locatePlot([hit.plot.id])
}

function onSearchBlur() {
  // 等一下再关，点结果的 mousedown 先处理
  window.setTimeout(() => (searchOpen.value = false), 150)
}

function onSearchKey(e: KeyboardEvent) {
  if (e.key === 'ArrowDown') {
    e.preventDefault()
    searchIndex.value = Math.min(searchHits.value.length - 1, searchIndex.value + 1)
  } else if (e.key === 'ArrowUp') {
    e.preventDefault()
    searchIndex.value = Math.max(0, searchIndex.value - 1)
  } else if (e.key === 'Enter') {
    // 回车定位到亮着的那个；Shift 回车选中全部匹配的（例如按品种找）
    if (e.shiftKey && searchAll.value.length > 1) {
      searchOpen.value = false
      locatePlot(searchAll.value.map((h) => h.plot.id))
    } else {
      pickHit(searchIndex.value)
    }
  } else if (e.key === 'Escape') {
    searchOpen.value = false
    ;(e.target as HTMLElement).blur()
  }
}

// ---------- 方向键挪选中 ----------

/** 只选了一个小区时，方向键选旁边的小区（按拉平后的行列），回车录入 */
function stepSelection(dr: number, dc: number) {
  const p = selectedPlots.value.length === 1 ? selectedPlots.value[0]! : null
  if (!p) return false
  for (let k = 1; k <= 4; k++) {
    const next = plots.value.find((x) => x.rowNo === p.rowNo + dr * k && x.colNo === p.colNo + dc * k)
    if (next) {
      selected.value = [next.id]
      return true
    }
  }
  return false
}

// ---------- 快捷键说明 ----------

const shortcutsOpen = ref(false)

const SHORTCUTS: { keys: string; text: string }[] = [
  { keys: '1 / 2 / 3', text: '录入数据 / 布置试验 / 编辑地块（老师）' },
  { keys: 'T', text: '俯视图和 3D 切换' },
  { keys: '/ 或 Ctrl F', text: '找小区（编号或品种）' },
  { keys: 'Ctrl A', text: '选中全部小区（区块里时选这个区块的）' },
  { keys: '方向键', text: '只选了一个小区时，选旁边的小区' },
  { keys: 'Enter', text: '给选中的小区录入数据' },
  { keys: 'Esc', text: '取消选择；再按回到整块地' },
  { keys: 'F', text: '适应窗口（3D 里回到默认视角）' },
  { keys: 'N', text: '图纸朝上和正北朝上切换' },
  { keys: 'V / H / M', text: '选择 / 拖动画面 / 测距' },
  { keys: 'R', text: '编辑地块：道路工具（点区块之间的线加道路，点道路删掉）' },
  { keys: 'Shift + 拖线', text: '编辑地块：两边一起变，地块总大小不变（平时地块跟着变；Alt 也行）' },
  { keys: '空格 + 拖动', text: '拖动画面（滚轮缩放）' },
  { keys: 'Ctrl / Shift + 点', text: '加选；拖动框选' },
  { keys: 'Ctrl Z / Ctrl Y / Ctrl S', text: '编辑地块：撤销 / 重做 / 保存' },
  { keys: ']', text: '收起、展开右侧面板' },
  { keys: '?', text: '这张说明' },
]

// ---------- 编辑地块的右键菜单 ----------

const editMenu = ref<{ x: number; y: number; blockId?: string; cell?: string } | null>(null)
const editPanelRef = ref<InstanceType<typeof LayoutEditPanel> | null>(null)

function applyCellKind(kind: CellKind) {
  const l = draft.draft.value
  if (!l || !focus.value) return
  draft.apply(setCellKinds(l, focus.value, selectedCells.value, kind))
}

function applyBlockKind(kind: BlockKind) {
  const l = draft.draft.value
  if (!l) return
  const next = setBlockKind(l, selectedBlocks.value, kind)
  if (removedPlots(l, next).length > 0) {
    ElMessage.warning('改成这个类型会去掉区块里的小区，请在右边面板里改，会先提醒你')
    return
  }
  draft.apply(next)
}

/** 先记下右键的是哪个区块（和它在哪条道路上），再关菜单、执行；关了菜单以后 editMenu 就是空的 */
function runEdit(fn: (blockId: string, road: { axis: Axis; index: number } | null) => void) {
  const blockId = editMenu.value?.blockId ?? ''
  const road = editMenuRoad.value
  editMenu.value = null
  fn(blockId, road)
}
</script>

<template>
  <div class="map-page" v-loading="loading">
    <!-- 页头：一行放下实验田、试验、今天的情况和操作 -->
    <header class="bar">
      <el-tooltip :content="isBreeding ? '回季和地块' : '回首页'" placement="bottom">
        <button type="button" class="icon-btn" :aria-label="isBreeding ? '回季和地块' : '回首页'" @click="router.push(isBreeding ? '/breeding/seasons' : '/home')"><el-icon><ArrowLeft /></el-icon></button>
      </el-tooltip>
      <div class="title">
        <h2>{{ field?.name }}</h2>
        <span class="meta">
          <template v-if="detail?.layoutSaved && fieldLayout">{{ layoutStats.blocks }} 个区块 · {{ layoutStats.plots }} 个{{ U }} · {{ layoutStats.size }}</template>
          <template v-else>{{ field?.rowCount }} 行 × {{ field?.colCount }} 列</template>
        </span>
      </div>
      <span v-if="isBreeding" class="season-chip" title="这块地块属于哪一季">{{ season?.name ?? '育种地块' }}</span>
      <el-select v-else v-model="trialId" placeholder="还没有试验" class="trial-select" :disabled="trials.length === 0">
        <el-option v-for="t in trials" :key="t.id" :value="t.id" :label="`${t.seasonYear} 年 · ${t.name}`" />
      </el-select>
      <div v-if="ind" class="chips">
        <span class="chip-stat stage" title="当前生育期">{{ ind.currentStage ?? '未开始' }}</span>
        <span class="chip-stat" title="今天录过数据的小区"><em>今天</em><b class="num">{{ ind.enteredToday }}</b><small>/{{ ind.totalPlots }}</small></span>
        <span class="chip-stat" :class="{ danger: ind.abnormal > 0 }" title="标了异常的小区">
          <el-icon v-if="ind.abnormal > 0"><Warning /></el-icon><em>异常</em><b class="num">{{ ind.abnormal }}</b>
        </span>
        <span class="chip-stat" title="最近 7 天录入的条数"><em>本周</em><b class="num">{{ ind.weekEntries }}</b><small>条</small></span>
      </div>
      <div class="spacer" />
      <el-tooltip v-if="trial && modules.isOn('record_book')" content="封面、试验设计、栽培管理、气象资料、品种评价" placement="bottom">
        <el-button :icon="Notebook" @click="router.push(`/trials/${trial.id}/book`)">记载本</el-button>
      </el-tooltip>
      <el-dropdown v-if="auth.isTeacher" trigger="click" @command="onTrialCommand">
        <el-button>试验<el-icon class="el-icon--right"><ArrowDown /></el-icon></el-button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item v-if="!isBreeding" command="new">新建试验</el-dropdown-item>
            <el-dropdown-item command="edit" :disabled="!trial">{{ isBreeding ? '试验信息：类型、麦区、重复次数' : '修改或删除当前试验' }}</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
      <el-dropdown trigger="click" @command="onMoreCommand">
        <el-button>更多<el-icon class="el-icon--right"><ArrowDown /></el-icon></el-button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item v-if="auth.isTeacher" command="field">{{ isBreeding ? '修改地块信息' : '修改实验田信息' }}</el-dropdown-item>
            <el-dropdown-item v-if="auth.isTeacher && modules.isOn('import')" command="import">导入历史表格</el-dropdown-item>
            <el-dropdown-item command="export">导出{{ view3d ? ' 3D 画面' : '种植图' }}（jpg）</el-dropdown-item>
            <el-dropdown-item v-if="modules.isOn('record_book')" command="book" :disabled="!trial">导出记载本（Excel）</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </header>

    <el-alert
      v-if="!loading && field && trials.length === 0 && !isBreeding"
      type="info"
      show-icon
      :closable="false"
      class="no-trial"
      :title="auth.isTeacher ? '这块地还没有试验。点右上角&quot;试验&quot; → 新建试验，再分配品种、录入数据。' : '老师还没有给这块地建试验，暂时不能录入。'"
    />

    <div class="workspace" :class="{ 'inspector-closed': !inspectorOpen }">
      <section class="stage" @dragover="onStageDragOver" @dragleave="dropHint = null" @drop="onStageDrop">
        <div v-if="dropHint" class="drop-hint">{{ dropHint }}</div>
        <FieldScene3D
          v-if="shownLayout && view3d"
          ref="sceneRef"
          v-model:selected-plots="selected"
          :layout="shownLayout"
          :cells="cells"
          :heights="heights"
          :focus="focus"
          :links="gridLinks"
          :devices="gridDevices"
          :auto-rotate="autoRotate"
          @open="openDetail"
          @contextmenu="onContextMenu"
          @unsupported="on3dUnsupported"
        />
        <FieldCanvas
          v-else-if="shownLayout"
          ref="canvasRef"
          v-model:selected-plots="selected"
          v-model:selected-blocks="selectedBlocks"
          v-model:selected-cells="selectedCells"
          :rotation="rotation"
          :layout="shownLayout"
          :cells="cells"
          :focus="focus"
          :links="mode === 'edit' ? NO_LINKS : gridLinks"
          :devices="mode === 'edit' ? NO_DEVICES : gridDevices"
          :tool="tool"
          :road-width="roadWidth"
          :pick="mode === 'edit' ? (focus ? 'cell' : 'block') : 'plot'"
          :rulers="mode === 'edit'"
          :editable="mode === 'edit'"
          :insets="CANVAS_INSETS"
          @zoom="(z: number) => (zoom = z)"
          @contextmenu="onContextMenu"
          @open="openDetail"
          @enter="enterBlock"
          @track="onTrack"
          @move-fit="onMoveFit"
          @resize-track="onResizeTrack"
          @road="onRoad"
          @move-cells="onMoveCells"
          @swap-blocks="onSwapBlocks"
        />

        <template v-if="shownLayout">
          <!-- 四边方位（俯视图） -->
          <template v-if="!view3d">
            <span class="edge top">{{ edges.top }}</span>
            <span class="edge right">{{ edges.right }}</span>
            <span class="edge bottom">{{ edges.bottom }}</span>
            <span class="edge left">{{ edges.left }}</span>
          </template>

          <!-- 左上：模式、进入区块时的位置 -->
          <div class="ov ov-tl">
            <div class="tl-row">
            <div v-if="auth.isTeacher" class="seg modes" role="tablist" aria-label="模式">
              <button role="tab" :class="{ on: mode === 'record' }" title="选小区录入数据、标异常（1）" @click="modeModel = 'record'">
                <el-icon><EditPen /></el-icon>录入数据
              </button>
              <button role="tab" :class="{ on: mode === 'layout' }" :title="isBreeding ? '种植、选株、杂交、编号（2）' : '分配品种、设重复、设对照、编号（2）'" @click="modeModel = 'layout'">
                <el-icon><Grid /></el-icon>布置试验
              </button>
              <button role="tab" :class="{ on: mode === 'edit' }" title="区块和小区的大小、合并、类型（3）" @click="modeModel = 'edit'">
                <el-icon><Crop /></el-icon>编辑地块
              </button>
            </div>
            <!-- 找小区 -->
            <div class="search" @keydown.stop>
            <el-input
              ref="searchInput"
              v-model="searchText"
              :prefix-icon="Search"
              clearable
              :placeholder="isBreeding ? '找小区块：编号或品系（/）' : '找小区：编号或品种（/）'"
              class="search-input"
              @focus="searchOpen = true"
              @blur="onSearchBlur"
              @keydown="onSearchKey"
            />
            <div v-if="searchOpen && searchText.trim()" class="search-pop">
              <button
                v-for="(h, i) in searchHits" :key="h.plot.id" type="button" class="hit" :class="{ on: i === searchIndex }"
                @mousedown.prevent="pickHit(i)" @mouseenter="searchIndex = i"
              >
                <b>{{ h.text }}</b><span>{{ h.sub || PLOT_KIND[h.plot.kind] }}</span>
              </button>
              <div v-if="searchHits.length === 0" class="hit empty">没找到。可以输小区编号，或者品种名的一部分</div>
              <button
                v-else-if="searchAll.length > 1" type="button" class="hit all"
                @mousedown.prevent="locatePlot(searchAll.map((h) => h.plot.id)); searchOpen = false"
              >
                全部选中（{{ searchAll.length }} 个，Shift 回车）
              </button>
            </div>
          </div>

            </div>
            <div v-if="focusBlock" class="crumbs">
              <button type="button" class="crumb" @click="leaveBlock">整块地</button>
              <span class="crumb-sep">›</span>
              <b>{{ focusBlock.name }}</b>
              <span class="crumb-meta num">{{ focusBlock.inner?.rows.length }} × {{ focusBlock.inner?.cols.length }}</span>
              <button type="button" class="crumb-x" title="回到整块地（Esc）" aria-label="回到整块地" @click="leaveBlock"><el-icon><Close /></el-icon></button>
            </div>
            <div v-if="mode === 'edit' && tool === 'road' && !focus && shownLayout" class="road-bar" @keydown.stop>
              <i class="road-icon" />
              <span>道路宽</span>
              <el-input-number
                v-model="roadWidth" :min="0.01" :max="1000" :precision="2" :step="0.5" size="small" controls-position="right"
                class="road-w"
              />
              <span>{{ shownLayout.unit.symbol }}</span>
              <span class="road-tip">点区块之间的线加道路，点道路删掉。别的区块不变小，地块跟着变大。Esc 退出</span>
            </div>
          </div>

          <!-- 右上：视角、着色 -->
          <div class="ov ov-tr">
            <div class="seg" role="tablist" aria-label="视角">
              <button role="tab" :class="{ on: !view3d }" title="平面俯视图，能选、能编辑（T）" @click="view3d = false">俯视图</button>
              <el-tooltip :disabled="mode !== 'edit'" content="编辑地块要在俯视图里" placement="bottom">
                <button role="tab" :class="{ on: view3d }" :disabled="mode === 'edit'" title="自由转动看立体样子（T）" @click="view3d = true">
                  <el-icon><Box /></el-icon>3D
                </button>
              </el-tooltip>
            </div>
            <div v-if="mode !== 'edit'" class="seg small" role="tablist" aria-label="着色">
              <button v-for="o in viewOptions" :key="o.value" role="tab" :class="{ on: display.view === o.value }" @click="display.view = o.value">{{ o.label }}</button>
            </div>
            <el-select
              v-if="display.view === 'trait' && mode !== 'edit'"
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
            <div v-if="view3d" class="seg small presets">
              <button title="正上方往下看" @click="sceneRef?.preset('top')">俯看</button>
              <button title="从图纸下方斜着看" @click="sceneRef?.preset('oblique')">斜看</button>
              <button title="站在南边往北看" @click="sceneRef?.preset('south')">南</button>
              <button title="站在东边往西看" @click="sceneRef?.preset('east')">东</button>
              <button title="站在北边往南看" @click="sceneRef?.preset('north')">北</button>
              <button title="站在西边往东看" @click="sceneRef?.preset('west')">西</button>
              <button :class="{ on: autoRotate }" title="慢慢转着看（投屏展示）" @click="autoRotate = !autoRotate">转</button>
            </div>
            <div v-else class="seg small">
              <button :class="{ on: facing === 'drawing' }" title="按图纸画，图纸上方朝上" @click="facing = 'drawing'">图纸朝上</button>
              <button :class="{ on: facing === 'north' }" title="按真北方向转，正北朝上（N）" @click="facing = 'north'">正北朝上</button>
            </div>
          </div>

          <!-- 右下：工具、缩放、指北针 -->
          <div class="ov ov-br">
            <MapCompass
              v-if="!view3d"
              class="map-compass"
              :north-deg="northDeg"
              :rotation="rotation"
              :rotatable="false"
              @north-up="toggleNorth"
            />
            <div class="tools">
              <template v-if="!view3d">
                <button class="tool" :class="{ on: tool === 'select' }" title="选择（V）" aria-label="选择" @click="tool = 'select'"><el-icon><Pointer /></el-icon></button>
                <button class="tool" :class="{ on: tool === 'pan' }" title="拖动画面（H，也可以按住空格或滚轮拖动）" aria-label="拖动画面" @click="tool = tool === 'pan' ? 'select' : 'pan'"><el-icon><Rank /></el-icon></button>
                <button class="tool" :class="{ on: tool === 'measure' }" title="测距（M）：点两下量距离" aria-label="测距" @click="tool = tool === 'measure' ? 'select' : 'measure'"><el-icon><Odometer /></el-icon></button>
                <button
                  v-if="mode === 'edit'" class="tool road-tool" :class="{ on: tool === 'road' }" :disabled="!!focus"
                  title="道路（R）：点区块之间的线加道路，点道路删掉；别的区块不变小，地块跟着变大"
                  aria-label="道路" @click="tool = tool === 'road' ? 'select' : 'road'"
                ><i class="road-icon" /></button>
                <span class="tool-sep" />
              </template>
              <button class="tool" title="缩小" aria-label="缩小" @click="zoomBy(1 / 1.25)"><el-icon><ZoomOut /></el-icon></button>
              <button class="tool zoom num" :title="view3d ? '回到默认视角（F）' : '适应窗口（F）'" @click="fitView">{{ view3d ? '复位' : `${zoom}%` }}</button>
              <button class="tool" title="放大" aria-label="放大" @click="zoomBy(1.25)"><el-icon><ZoomIn /></el-icon></button>
              <span class="tool-sep" />
              <button class="tool" title="快捷键（?）" aria-label="快捷键" @click="shortcutsOpen = true"><el-icon><QuestionFilled /></el-icon></button>
            </div>
          </div>
          <div v-if="view3d" class="hint-3d">左键拖动转 · 右键拖动平移 · 滚轮缩放 · 单击选 · Shift 拖动框选</div>
        </template>

        <!-- 下方中间：编辑地块的保存条、选中后的操作条 -->
        <div v-if="mode === 'edit'" class="action-bar edit-bar">
          <el-tooltip content="撤销（Ctrl Z）" placement="top">
            <el-button :icon="RefreshLeft" :disabled="!draft.canUndo.value" @click="draft.undo()" />
          </el-tooltip>
          <el-tooltip content="重做（Ctrl Y）" placement="top">
            <el-button :icon="RefreshRight" :disabled="!draft.canRedo.value" @click="draft.redo()" />
          </el-tooltip>
          <span class="sel edit-state">
            <template v-if="draft.dirty.value">
              有改动未保存
              <em v-if="draft.removed.value.length" class="gone">去掉 {{ draft.removed.value.length }} 个小区</em>
              <em v-if="draft.added.value" class="new">新建 {{ draft.added.value }} 个</em>
            </template>
            <template v-else>和保存的布局一样</template>
          </span>
          <el-button :disabled="!draft.dirty.value" @click="discardLayout">放弃改动</el-button>
          <el-button type="primary" :icon="Check" :loading="savingLayout" :disabled="!draft.dirty.value" @click="saveLayout">保存布局</el-button>
        </div>
        <transition v-else name="rise">
          <div v-if="selected.length > 0" class="action-bar">
            <span class="sel">已选 <b>{{ selected.length }}</b> 个{{ U }}</span>
            <template v-if="mode === 'record'">
              <template v-if="auth.canRecord && trial">
                <el-button type="primary" :icon="EditPen" @click="openEntry(selectedPlots)">录入数据<kbd>Enter</kbd></el-button>
                <el-button :icon="Warning" @click="toggleAbnormal(selectedPlots)">{{ allAbnormal ? '取消异常' : '标记异常' }}</el-button>
              </template>
              <el-button v-if="selected.length === 1" @click="openDetail(selected[0]!)">详情</el-button>
              <el-button v-if="selected.length > 1 && modules.isOn('analysis')" :icon="PieChart" @click="analyzeSelection">做图表</el-button>
            </template>
            <template v-else-if="isBreeding">
              <el-button type="primary" @click="openPlant(selectedTrialPlots.map((p) => p.id))">种植</el-button>
              <el-button :disabled="selectedLineIds.length !== 1" @click="openSelect(selectedTrialPlots.map((p) => p.id))">选株</el-button>
              <el-tooltip :disabled="selectedLineIds.length === 2" content="选种着两个不同品系的小区块，先选的是母本" placement="top">
                <span><el-button :disabled="selectedLineIds.length !== 2" @click="openCross">杂交</el-button></span>
              </el-tooltip>
              <el-button @click="assignReplicate">设重复</el-button>
              <el-button @click="openLayoutDialog('link')">设对照</el-button>
              <el-dropdown trigger="click" placement="top" @command="onBreedingMore">
                <el-button>更多<el-icon class="el-icon--right"><ArrowDown /></el-icon></el-button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item command="generation" :disabled="selectedLineIds.length === 0">改世代</el-dropdown-item>
                    <el-dropdown-item command="clear" :disabled="selectedLineIds.length === 0">清掉种的品系</el-dropdown-item>
                    <el-dropdown-item command="numbering" divided>编号</el-dropdown-item>
                    <el-dropdown-item command="kind">小区块类型</el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </template>
            <template v-else>
              <el-button type="primary" @click="openLayoutDialog('variety')">分配品种</el-button>
              <el-button @click="assignReplicate">设重复</el-button>
              <el-button @click="openLayoutDialog('link')">设对照</el-button>
              <el-button @click="numberingDialog = true">编号</el-button>
              <el-button @click="plotDialog = true">{{ detail?.layoutSaved ? '小区类型' : '类型和尺寸' }}</el-button>
            </template>
            <el-button text :icon="Close" class="clear" title="取消选择（Esc）" @click="selected = []" />
          </div>
          <div v-else-if="trial && shownLayout" class="idle-hint">
            {{ mode === 'record'
              ? `点选或框选${U}开始录入 · 双击看详情 · 右键更多`
              : isBreeding
                ? '把右边的品系拖到区块或小区块上种 · 选中小区块后种植、选株、杂交 · 右键更多'
                : '选中小区后分配品种、设重复、设对照 · 点区块名称牌选整个区块' }}
          </div>
        </transition>
      </section>

      <!-- 右侧面板 -->
      <button
        type="button" class="inspector-toggle" :title="inspectorOpen ? '收起右侧面板（]）' : '展开右侧面板（]）'"
        :aria-label="inspectorOpen ? '收起右侧面板' : '展开右侧面板'" @click="inspectorOpen = !inspectorOpen"
      >
        <el-icon><component :is="inspectorOpen ? ArrowRight : ArrowLeft" /></el-icon>
      </button>
      <aside v-show="inspectorOpen" class="inspector">
        <LayoutEditPanel
          v-if="mode === 'edit' && draft.draft.value"
          ref="editPanelRef"
          v-model:selected-blocks="selectedBlocks"
          v-model:selected-cells="selectedCells"
          v-model:track="track"
          :layout="draft.draft.value"
          :focus="focus"
          :rotation="rotation"
          :plot-code="plotCode"
          @apply="draft.apply"
          @enter="enterBlock"
          @leave="leaveBlock"
        />

        <!-- 选了一个小区：它的情况和最近数据 -->
        <template v-else-if="single">
          <section class="panel panel-pad plot-card">
            <div class="plot-head">
              <span class="plot-code serif">{{ single.code || `${single.rowNo}-${single.colNo}` }}</span>
              <div class="plot-head-text">
                <b>{{ single.kind === 'PLOT' ? (catalog.varietyById.get(plantingByPlot.get(single.id)?.varietyId ?? -1)?.name ?? (isBreeding ? '还没种' : '未分配品种')) : PLOT_KIND[single.kind] }}</b>
                <span class="muted small">{{ placeById.get(single.id) ?? `第 ${single.rowNo} 行第 ${single.colNo} 列` }}</span>
              </div>
            </div>
            <div class="kv">
              <span>重复</span><b>{{ plantingByPlot.get(single.id)?.replicateNo ? replicateLabel(plantingByPlot.get(single.id)!.replicateNo!) : '—' }}</b>
              <span>状态</span>
              <b><i class="dot-status" :style="{ background: PLOT_STATUS[plotStatus(single)].color }" />{{ PLOT_STATUS[plotStatus(single)].label }}</b>
              <template v-if="statusByPlot.get(single.id)?.abnormalNote">
                <span>异常</span><b class="danger-text">{{ statusByPlot.get(single.id)!.abnormalNote }}</b>
              </template>
              <template v-if="single.widthM && single.lengthM"><span>大小</span><b class="num">{{ single.widthM }} × {{ single.lengthM }} m</b></template>
            </div>
            <div class="plot-actions">
              <el-button v-if="mode === 'record' && auth.canRecord && trial && single.kind === 'PLOT'" type="primary" :icon="EditPen" @click="openEntry([single])">录入</el-button>
              <el-button v-if="isBreeding && mode === 'layout' && auth.isTeacher && single.kind === 'PLOT'" type="primary" @click="openPlant([single.id])">种植</el-button>
              <el-button v-if="trial" @click="openDetail(single.id)">全部记录</el-button>
            </div>
            <PlotLineCard
              v-if="isBreeding && lineOf(single.id) != null"
              :line-id="lineOf(single.id)!"
              :generation="plantingByPlot.get(single.id)?.generation ?? null"
            />
          </section>
          <section v-if="single.kind === 'PLOT' && trial" class="panel panel-pad">
            <div class="panel-title">最近录的数据</div>
            <div v-if="singleLoading && !singleDetail" class="skeleton" style="height: 120px" />
            <div v-else-if="singleDetail && singleDetail.latest.length === 0" class="hint">这个{{ U }}还没有录过数据</div>
            <div v-else-if="singleDetail" class="latest">
              <div v-for="v in singleDetail.latest.slice(0, 10)" :key="v.traitId" class="latest-row">
                <span class="name">{{ v.traitName }}</span>
                <b class="num">{{ v.displayValue }}</b>
                <span class="faint num">{{ v.observedOn?.slice(5) ?? '' }}</span>
              </div>
              <el-button v-if="singleDetail.latest.length > 10" link type="primary" size="small" @click="openDetail(single.id)">
                还有 {{ singleDetail.latest.length - 10 }} 项
              </el-button>
            </div>
            <div class="hint">方向键选旁边的{{ U }}，回车录入。</div>
          </section>
        </template>

        <!-- 选了好几个小区：分布，点一下选中同样的 -->
        <template v-else-if="selected.length > 1">
          <section class="panel panel-pad">
            <div class="panel-title">已选 {{ selected.length }} 个{{ U }}<span v-if="selected.length !== selectedTrialPlots.length" class="sub">其中能种的 {{ selectedTrialPlots.length }} 个</span></div>
            <div class="facet-group">
              <div class="facet-title">{{ isBreeding ? '品系' : '品种' }}</div>
              <button v-for="f in facets.variety" :key="f.key" type="button" class="facet" :title="`选中所有种这个${isBreeding ? '品系' : '品种'}的${U}`" @click="f.pick()">
                <i :style="{ background: f.color }" /><span>{{ isBreeding && f.label === '未分配品种' ? '还没种' : f.label }}</span><b class="num">{{ f.count }}</b>
              </button>
            </div>
            <div class="facet-group">
              <div class="facet-title">重复</div>
              <button v-for="f in facets.replicate" :key="f.key" type="button" class="facet" title="选中这个重复的所有小区" @click="f.pick()">
                <i :style="{ background: f.color }" /><span>{{ f.label }}</span><b class="num">{{ f.count }}</b>
              </button>
            </div>
            <div v-if="trial" class="facet-group">
              <div class="facet-title">状态</div>
              <button v-for="f in facets.status" :key="f.key" type="button" class="facet" title="选中这个状态的所有小区" @click="f.pick()">
                <i :style="{ background: f.color }" /><span>{{ f.label }}</span><b class="num">{{ f.count }}</b>
              </button>
            </div>
            <div class="hint">点一项，选中地里所有同样的{{ U }}（进入区块时只在这个区块里选）。</div>
          </section>
        </template>

        <!-- 什么都没选：图例、区块和重复、对照关系 -->
        <template v-else>
          <section class="panel panel-pad">
            <div class="panel-title">图例<span class="sub">{{ viewOptions.find((o) => o.value === display.view)?.label }}</span></div>
            <template v-if="display.view === 'trait'">
              <div class="metric-name">{{ metricTrait?.name ?? '选择指标' }}<span v-if="metricTrait?.unit && metricTrait.unit !== '级'" class="muted">（{{ metricTrait.unit }}）</span></div>
              <template v-if="metricRange">
                <div class="ramp" :style="{ background: SEQUENTIAL_GRADIENT }" />
                <div class="ramp-labels num"><span>{{ metricRange.min }}</span><span>{{ metricRange.max }}</span></div>
                <div v-if="view3d" class="hint">3D 里小区越高，这项的值越大。</div>
              </template>
              <div v-else class="hint">这个试验还没有这项数据</div>
              <div class="legend-row"><span class="swatch" style="background: #f1ebdd" />无数据</div>
            </template>
            <template v-else>
              <button
                v-for="l in legend" :key="l.label" type="button" class="legend-row clickable" :title="`选中：${l.label}`"
                @click="selectByLegend(l.label)"
              >
                <span class="swatch" :style="{ background: l.color }" />{{ l.label }}
              </button>
              <div v-if="legend.length === 0" class="hint">还没有分配</div>
              <div v-if="view3d && display.view === 'status'" class="hint">3D 里小区的高度按长势：生长中最高，收获后变矮。</div>
            </template>
            <div class="legend-sep" />
            <div class="legend-row"><span class="dot" />今天录过数据</div>
            <div class="legend-row"><b class="ck">CK</b>对照品种</div>
            <div class="legend-row"><b class="ck alert">!</b>有异常</div>
          </section>

          <section v-if="trial && mode === 'layout' && plantBlocks.length > 1" class="panel panel-pad">
            <div class="panel-title">区块和重复<span class="sub">{{ plantBlocks.length }} 个区块</span></div>
            <div v-for="x in blockReplicates" :key="x.block.id" class="rep-row">
              <span>{{ x.block.name }}</span>
              <span v-if="x.rep" class="chip green">重复 {{ replicateLabel(x.rep) }}</span>
              <span v-else-if="x.mixed" class="chip wheat">几个重复</span>
              <span v-else class="faint">未设</span>
            </div>
            <el-button size="small" type="primary" plain :loading="assigningReps" class="rep-btn" @click="replicatesByBlock">
              一个区块一个重复
            </el-button>
          </section>

          <section v-if="trial" class="panel panel-pad">
            <div class="panel-title">对照<span class="sub">{{ layout?.links.length ?? 0 }} 组</span></div>
            <div v-if="!layout?.links.length" class="hint">
              {{ auth.isTeacher
                ? `在"布置试验"里选中对照的${U}（或点区块名称牌选整个区块），点"设对照"。`
                : '还没有设对照。' }}
            </div>
            <div
              v-for="l in layout?.links ?? []"
              :key="l.id"
              class="link-row"
              :class="{ active: activeLink?.id === l.id }"
              @click="togglePinnedLink(l.id)"
            >
              <div>
                <div class="link-label"><i class="ck-dot" />{{ l.label }}</div>
                <div class="hint">
                  对照 {{ linkControlText(l) }} · 和 {{ l.plots.filter((p) => !p.control).length }} 个{{ U }}比
                </div>
              </div>
              <el-button v-if="auth.isTeacher && mode === 'layout'" link type="danger" size="small" @click.stop="removeLink(l.id, l.label)">删除</el-button>
            </div>
            <div v-if="layout?.links.length && !activeLink" class="hint">点一组看各{{ U }}比对照高多少。</div>
          </section>

          <section v-if="trial && comparison" class="panel panel-pad compare">
            <div class="panel-title">和对照比<span class="sub">{{ comparison.link.label }}</span></div>
            <el-select v-model="compareTraitId" size="small" filterable placeholder="选项目" class="compare-trait">
              <el-option-group v-for="g in metricGroups" :key="g.label" :label="g.label">
                <el-option
                  v-for="t in g.items" :key="t.trait.id" :value="t.trait.id"
                  :label="t.trait.unit && t.trait.unit !== '级' ? `${t.trait.name}（${t.trait.unit}）` : t.trait.name"
                />
              </el-option-group>
            </el-select>
            <div class="ck-mean">
              <span class="ck-tag">CK</span>
              <span>{{ comparison.blockName ? `${comparison.blockName}平均` : comparison.controls > 1 ? '对照平均' : '对照' }}</span>
              <b class="num">{{ comparison.mean ?? '没数据' }}</b>
              <span class="faint">{{ compareTrait?.unit && compareTrait.unit !== '级' ? compareTrait.unit : '' }}</span>
              <span v-if="comparison.controls > 1" class="faint">（{{ comparison.withData }}/{{ comparison.controls }} 个有数据）</span>
            </div>
            <div v-if="comparison.mean != null" class="hint">{{ comparison.rows.length }} 个里 {{ comparison.better }} 个比对照高</div>
            <div class="cmp-list">
              <button
                v-for="r in (compareShowAll ? comparison.rows : comparison.rows.slice(0, 10))" :key="r.plotId" type="button" class="cmp-row"
                @click="selected = [r.plotId]"
              >
                <span class="cmp-code num">{{ r.code }}</span>
                <span class="cmp-name">{{ r.name }}</span>
                <span class="cmp-val num">{{ r.display }}</span>
                <span class="cmp-bar">
                  <i
                    v-if="r.pct != null" :class="r.pct >= 0 ? 'up' : 'down'"
                    :style="{ width: `${Math.min(50, Math.abs(r.pct) * 2.5)}%`, [r.pct >= 0 ? 'left' : 'right']: '50%' }"
                  />
                </span>
                <span class="cmp-pct num" :class="r.pct == null ? 'faint' : r.pct >= 0 ? 'up' : 'down'">{{ r.pct == null ? '—' : pctText(r.pct) }}</span>
              </button>
            </div>
            <el-button v-if="comparison.rows.length > 10" link type="primary" size="small" @click="compareShowAll = !compareShowAll">
              {{ compareShowAll ? '收起' : `全部 ${comparison.rows.length} 个` }}
            </el-button>
          </section>

          <section v-if="!isBreeding" class="panel panel-pad tips">
            <div class="panel-title">怎么用</div>
            <ul>
              <li>点小区选中，<kbd>Ctrl</kbd> 点加选，拖动框选</li>
              <li>双击区块名称牌进入区块；<kbd>Esc</kbd> 返回</li>
              <li><kbd>/</kbd> 找小区，<kbd>T</kbd> 切换 3D</li>
              <li>按 <kbd>?</kbd> 看全部快捷键</li>
            </ul>
          </section>
        </template>

        <BreedingPanel
          v-if="isBreeding && mode !== 'edit'"
          :candidates="candidates"
          :layout-mode="mode === 'layout'"
          :teacher="auth.isTeacher"
          :focus-line-ids="selectedLineIds"
          :planted-here="plantedHere"
          :selection-count="mode === 'layout' ? selectedTrialPlots.length : 0"
          @plant="(id: number) => plantLine(id, selectedTrialPlots.map((p) => p.id))"
          @open="openLine"
          @locate="locateLine"
        />
      </aside>
    </div>

    <!-- 右键菜单：录入、布置 -->
    <div
      v-if="menu && (menuPlot || (isBreeding && menuBlock))"
      class="context-menu"
      :class="{ flip: menu.x > winWidth - 460, up: menu.y > winHeight / 2 }"
      :style="{ left: menu.x + 'px', top: menu.y + 'px' }"
      @click.stop
    >
      <div class="menu-title">
        <template v-if="menuPlot">{{ U }} {{ menuPlot.code || `${menuPlot.rowNo}-${menuPlot.colNo}` }}<span v-if="selected.length > 1" class="muted">（已选 {{ selected.length }} 个）</span></template>
        <template v-else>{{ menuBlock?.name }}</template>
      </div>
      <template v-if="menuPlot">
        <div class="menu-item" @click="runMenu((p) => openDetail(p.id))">查看详情</div>
        <template v-if="auth.canRecord && trial && menuPlot.kind === 'PLOT'">
          <div class="menu-item" @click="runMenu(() => openEntry(selectedPlots))">
            录入数据{{ selected.length > 1 ? `（${selected.length} 个${U}）` : '' }}<kbd>Enter</kbd>
          </div>
          <div class="menu-item" @click="runMenu(() => toggleAbnormal(selectedPlots))">{{ allAbnormal ? '取消异常' : '标记异常' }}</div>
        </template>
        <template v-if="menuPlot.kind === 'PLOT'">
          <div class="menu-sep" />
          <div v-if="plantingByPlot.get(menuPlot.id)?.varietyId" class="menu-item" @click="runMenu((p) => selectWhere((x) => x.kind === 'PLOT' && plantingByPlot.get(x.id)?.varietyId === plantingByPlot.get(p.id)?.varietyId))">选中同{{ isBreeding ? '品系' : '品种' }}的{{ U }}</div>
          <div v-if="plantingByPlot.get(menuPlot.id)?.replicateNo" class="menu-item" @click="runMenu((p) => selectWhere((x) => x.kind === 'PLOT' && plantingByPlot.get(x.id)?.replicateNo === plantingByPlot.get(p.id)?.replicateNo))">选中同一重复的小区</div>
          <div v-if="isBreeding && lineOf(menuPlot.id) != null" class="menu-item" @click="runMenu((p) => openLine(lineOf(p.id)!))">看品系详情</div>
        </template>
      </template>

      <!-- 育种的地块：种植、整块种、选株、杂交 -->
      <template v-if="isBreeding && auth.isTeacher">
        <div class="menu-sep" />
        <div v-if="menuPlot && menuPlot.kind === 'PLOT'" class="menu-item has-sub">
          种植{{ menuTargets().length > 1 ? `（${menuTargets().length} 个小区块）` : '' }}<span class="arrow">▸</span>
          <div class="submenu">
            <template v-for="g in menuGroups" :key="g.key">
              <div class="sub-title">{{ g.title }}</div>
              <div v-for="l in g.lines" :key="l.id" class="menu-item" @click="runTargets((ids) => plantLine(l.id, ids))">
                {{ l.name }}<span v-if="l.name !== l.code" class="muted">{{ l.code }}</span>
              </div>
            </template>
            <div v-if="menuGroups.length === 0" class="sub-title">"全新开始"的季：搜品系或新建品种</div>
            <div class="menu-sep" />
            <div class="menu-item" @click="runTargets((ids) => openPlant(ids))">搜全部品系、新建品种…</div>
          </div>
        </div>
        <div v-if="menuBlock && menuBlock.kind === 'PLANT'" class="menu-item has-sub">
          整块种：{{ menuBlock.name }}<span class="arrow">▸</span>
          <div class="submenu">
            <template v-for="g in menuGroups" :key="g.key">
              <div class="sub-title">{{ g.title }}</div>
              <div v-for="l in g.lines" :key="l.id" class="menu-item" @click="runMenuBlock((b) => plantLine(l.id, plantPlotsOfBlock(b)))">
                {{ l.name }}<span v-if="l.name !== l.code" class="muted">{{ l.code }}</span>
              </div>
            </template>
            <div class="menu-sep" />
            <div class="menu-item" @click="runMenuBlock((b) => openPlant(plantPlotsOfBlock(b), `整块种：${menuBlockName(b)}`))">搜全部品系、新建品种…</div>
          </div>
        </div>
        <div v-if="menuPlot && lineOf(menuPlot.id) != null" class="menu-item" @click="runTargets((ids) => openSelect(ids))">
          选株{{ menuTargets().length > 1 ? '（从选中的小区块）' : '' }}…
        </div>
        <div v-if="menuBlock && blockLine(menuBlock.id) != null" class="menu-item" @click="runMenuBlock((b) => openSelect(plantPlotsOfBlock(b)))">
          从整个{{ menuBlock.name }}选株…
        </div>
        <div v-if="selectedLineIds.length === 2" class="menu-item" @click="runMenu(() => openCross())">
          杂交：{{ lineName(selectedLineIds[0]!) }} × {{ lineName(selectedLineIds[1]!) }}…
        </div>
        <template v-if="menuPlot && lineOf(menuPlot.id) != null">
          <div class="menu-item" @click="runTargets((ids) => setGeneration(ids))">改世代…</div>
          <div class="menu-item" @click="runTargets((ids) => clearPlants(ids))">清掉</div>
        </template>
        <template v-if="menuPlot">
          <div class="menu-sep" />
          <div v-if="trial" class="menu-item" @click="runMenu(assignReplicate)">设重复号</div>
          <div v-if="trial && selected.length >= 1" class="menu-item" @click="runMenu(() => openLayoutDialog('link'))">设对照…</div>
          <div class="menu-item" @click="runMenu(() => (numberingDialog = true))">编号</div>
          <div class="menu-item" @click="runMenu(() => (plotDialog = true))">小区块类型</div>
        </template>
      </template>
      <template v-else-if="auth.isTeacher && menuPlot">
        <div class="menu-sep" />
        <div v-if="trial" class="menu-item" @click="runMenu(() => openLayoutDialog('variety'))">分配品种</div>
        <div v-if="trial" class="menu-item" @click="runMenu(assignReplicate)">设重复号</div>
        <div v-if="trial && selected.length >= 1" class="menu-item" @click="runMenu(() => openLayoutDialog('link'))">设对照…</div>
        <div class="menu-item" @click="runMenu(() => (numberingDialog = true))">编号</div>
        <div class="menu-item" @click="runMenu(() => (plotDialog = true))">小区类型和尺寸</div>
      </template>
    </div>

    <!-- 右键菜单：编辑地块 -->
    <div v-if="editMenu" class="context-menu" :style="{ left: editMenu.x + 'px', top: editMenu.y + 'px' }" @click.stop>
      <template v-if="editMenu.cell && focus">
        <div class="menu-title">选中的 {{ selectedCells.length }} 个格子</div>
        <div v-for="k in (['PLANT', 'ROAD', 'GUARD', 'BLANK'] as CellKind[])" :key="k" class="menu-item" @click="runEdit(() => applyCellKind(k))">设成{{ CELL_KIND[k] }}</div>
        <template v-if="selectedCells.length === 1 && draft.draft.value && focusBlock?.inner && mergeAt(focusBlock.inner.merges, ...parseKey(selectedCells[0]!))">
          <div class="menu-sep" />
          <div class="menu-item" @click="runEdit(() => { const [r, c] = parseKey(selectedCells[0]!); draft.apply(splitInner(draft.draft.value!, focus!, r, c)) })">拆开合并的小区</div>
        </template>
        <div class="menu-sep" />
        <div class="menu-item" @click="runEdit(leaveBlock)">回到整块地<kbd>Esc</kbd></div>
      </template>
      <template v-else-if="editMenu.blockId">
        <div class="menu-title">{{ selectedBlocks.length > 1 ? `选中的 ${selectedBlocks.length} 个区块` : draft.draft.value?.blocks.find((b) => b.id === editMenu!.blockId)?.name }}</div>
        <div v-if="selectedBlocks.length === 1 && draft.draft.value?.blocks.find((b) => b.id === editMenu!.blockId)?.kind === 'PLANT'" class="menu-item" @click="runEdit((id) => enterBlock(id))">进入区块改小区</div>
        <div v-if="selectedBlocks.length > 1" class="menu-item" @click="runEdit(() => editPanelRef?.merge())">合并成一个区块…</div>
        <div
          v-if="selectedBlocks.length === 1 && draft.draft.value && (() => { const b = draft.draft.value!.blocks.find((x) => x.id === editMenu!.blockId); if (!b) return false; const sp = blockSpan(draft.draft.value!, b); return sp.rs * sp.cs > 1 })()"
          class="menu-item" @click="runEdit((id) => draft.apply(splitBlock(draft.draft.value!, id)))"
        >
          拆开
        </div>
        <template v-if="selectedBlocks.length <= 1 && editMenuRoad">
          <div class="menu-sep" />
          <div class="menu-item" @click="runEdit((_, road) => road && removeRoadAt(road))">删掉这条道路<span class="menu-note">地块变小</span></div>
          <div class="menu-item" @click="runEdit((_, road) => road && roadToBlocksAt(road))">这条道路改成区块</div>
        </template>
        <template v-else-if="selectedBlocks.length <= 1 && editMenuBlock && editMenuBlock.kind !== 'ROAD'">
          <div class="menu-sep" />
          <div class="menu-sub">加道路<span class="menu-note">别的区块不变小</span></div>
          <div class="menu-row">
            <button type="button" :disabled="!!roadSideProblems.top" :title="roadSideProblems.top ?? ''" @click="runEdit((id) => roadBeside(id, 'top'))">上面</button>
            <button type="button" :disabled="!!roadSideProblems.bottom" :title="roadSideProblems.bottom ?? ''" @click="runEdit((id) => roadBeside(id, 'bottom'))">下面</button>
            <button type="button" :disabled="!!roadSideProblems.left" :title="roadSideProblems.left ?? ''" @click="runEdit((id) => roadBeside(id, 'left'))">左边</button>
            <button type="button" :disabled="!!roadSideProblems.right" :title="roadSideProblems.right ?? ''" @click="runEdit((id) => roadBeside(id, 'right'))">右边</button>
          </div>
          <div class="menu-item" @click="runEdit((id) => trackOfBlockToRoad(id, 'row'))">这一行改成道路</div>
          <div class="menu-item" @click="runEdit((id) => trackOfBlockToRoad(id, 'col'))">这一列改成道路</div>
        </template>
        <div class="menu-sep" />
        <div v-for="k in (['PLANT', 'ROAD', 'GUARD', 'BLANK'] as BlockKind[])" :key="k" class="menu-item" @click="runEdit(() => applyBlockKind(k))">设成{{ BLOCK_KIND[k] }}</div>
      </template>
    </div>

    <el-dialog v-model="shortcutsOpen" title="快捷键" width="520px" append-to-body>
      <div class="shortcuts">
        <template v-for="k in SHORTCUTS" :key="k.keys">
          <kbd>{{ k.keys }}</kbd><span>{{ k.text }}</span>
        </template>
      </div>
    </el-dialog>

    <PlotDetailDrawer
      ref="drawerRef"
      v-model="drawerOpen"
      :trial-id="trialId"
      :plot-id="drawerPlotId"
      :place="drawerPlotId != null && detail?.layoutSaved ? plotPlace(fieldLayout, drawerPlotId) : null"
      @entry="entryFromDrawer"
      @changed="refreshStatus"
    />
    <EntryDialog
      v-if="trialId"
      v-model="entryOpen"
      :trial-id="trialId"
      :plots="entryPlots"
      :initial-trait-id="entryTraitId"
      :whole-block="isBreeding ? entryWholeBlock : undefined"
      :name-of="(id: number) => catalog.varietyById.get(plantingByPlot.get(id)?.varietyId ?? -1)?.name ?? '—'"
      @saved="onEntrySaved"
    />
    <FieldFormDialog v-model="fieldDialog" :field="field" :layout-saved="detail?.layoutSaved" @saved="onFieldSaved" />
    <TrialFormDialog v-model="trialDialog" :field-id="fieldId" :trial="editingTrial" :land="isBreeding" @saved="onTrialSaved" @removed="onTrialRemoved" />
    <AssignVarietyDialog v-if="trialId" v-model="varietyDialog" :trial-id="trialId" :plot-ids="selectedTrialPlots.map((p) => p.id)" @saved="onLayoutSaved" />
    <ControlLinkDialog
      v-if="trialId"
      v-model="linkDialog"
      :trial-id="trialId"
      :plots="selectedTrialPlots"
      :all-plots="plots"
      :layout="fieldLayout"
      :unit="U"
      :name-of="(id: number) => catalog.varietyById.get(plantingByPlot.get(id)?.varietyId ?? -1)?.name ?? '—'"
      :is-ck="(id: number) => !!catalog.varietyById.get(plantingByPlot.get(id)?.varietyId ?? -1)?.controlType"
      @saved="onLinkSaved"
    />
    <NumberingDialog v-model="numberingDialog" :field-id="fieldId" :plots="selectedPlots" @saved="onPlotsSaved" />
    <PlotSettingsDialog v-model="plotDialog" :field-id="fieldId" :plots="selectedPlots" :layout-saved="detail?.layoutSaved" @saved="onPlotsSaved" />
    <template v-if="isBreeding">
      <PlantDialog
        v-model="plantOpen"
        :candidates="candidates"
        :count="plantTargets.length"
        :title="plantTitle"
        @pick="(id: number) => plantLine(id, plantTargets)"
      />
      <SelectDialog v-model="selectOpen" :line-id="selectLineId" :location="selectLocation" @saved="afterBreedingChange" />
      <CrossDialog
        v-model="crossOpen"
        :mother-id="crossPair.mother"
        :father-id="crossPair.father"
        :season-id="field?.seasonId ?? null"
        @saved="afterBreedingChange"
      />
    </template>
  </div>
</template>

<style scoped>
@keyframes hint-fade {
  0%, 75% { opacity: 1; }
  100% { opacity: 0; }
}

/* ---------- 整页：页头一行，下面全是地图 ---------- */

.map-page {
  display: flex;
  flex-direction: column;
  height: 100%;
  box-sizing: border-box;
}

.bar {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 18px 10px 12px;
  border-bottom: 1px solid var(--line);
  background: rgba(255, 253, 248, 0.7);
  flex-shrink: 0;
  min-width: 0;
}

.bar .el-button + .el-button {
  margin-left: 0;
}

.icon-btn {
  width: 34px;
  height: 34px;
  display: grid;
  place-items: center;
  border: none;
  border-radius: 10px;
  background: none;
  color: var(--ink-2);
  font-size: 17px;
  cursor: pointer;
  flex-shrink: 0;
}

.icon-btn:hover {
  background: var(--surface-2);
  color: var(--forest);
}

.title {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.title h2 {
  margin: 0;
  font-family: var(--font-serif);
  font-size: 20px;
  line-height: 1.2;
  font-weight: 700;
  color: var(--forest);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.title .meta {
  font-size: 12px;
  color: var(--ink-3);
  white-space: nowrap;
}

.trial-select {
  width: 250px;
  flex-shrink: 0;
}

.chips {
  display: flex;
  gap: 6px;
  min-width: 0;
  overflow: hidden;
}

.chip-stat {
  display: inline-flex;
  align-items: baseline;
  gap: 4px;
  padding: 4px 10px;
  border-radius: 999px;
  background: var(--surface-2);
  border: 1px solid var(--line);
  font-size: 12.5px;
  color: var(--ink-2);
  white-space: nowrap;
}

.chip-stat em {
  font-style: normal;
  color: var(--ink-3);
}

.chip-stat b {
  font-size: 14px;
  color: var(--ink);
}

.chip-stat small {
  color: var(--ink-3);
}

.chip-stat.stage {
  background: linear-gradient(180deg, #f6e2ad, #edcf86);
  border-color: #dcb862;
  color: #5d4410;
  font-weight: 700;
  font-family: var(--font-serif);
}

.chip-stat.danger {
  background: #f8e6df;
  border-color: #ecc9bc;
  color: #8f3f28;
  align-items: center;
}

.chip-stat.danger b {
  color: #8f3f28;
}

.spacer {
  flex: 1;
}

.no-trial {
  margin: 10px 18px 0;
  width: auto;
}

/* ---------- 工作区：地图 + 右侧面板 ---------- */

.workspace {
  position: relative;
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: minmax(0, 1fr) 330px;
}

.workspace.inspector-closed {
  grid-template-columns: minmax(0, 1fr) 0;
}

.stage {
  position: relative;
  min-width: 0;
  min-height: 0;
  overflow: hidden;
  /* 地块外面露出土色 */
  background: #e9e1cd url('@/assets/art/soil.jpg') repeat;
  background-size: 256px;
  box-shadow: inset 0 2px 10px rgba(80, 60, 20, 0.12);
}

.ov {
  position: absolute;
  z-index: 3;
  display: flex;
  gap: 8px;
}

.ov-tl {
  top: 12px;
  left: 12px;
  flex-direction: column;
  align-items: flex-start;
}

.tl-row {
  display: flex;
  align-items: flex-start;
  gap: 8px;
}

.ov-tr {
  top: 12px;
  right: 12px;
  flex-direction: column;
  align-items: flex-end;
}

.ov-br {
  right: 12px;
  bottom: 78px;
  flex-direction: column;
  align-items: flex-end;
  gap: 10px;
}

/* 分段按钮：模式、视角、着色 */
.seg {
  display: inline-flex;
  padding: 3px;
  gap: 2px;
  border-radius: 11px;
  background: rgba(255, 253, 248, 0.95);
  border: 1px solid rgba(90, 70, 30, 0.18);
  box-shadow: 0 6px 18px -10px rgba(60, 40, 10, 0.45);
}

.seg button {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  border: none;
  background: none;
  padding: 6px 12px;
  border-radius: 8px;
  font: inherit;
  font-size: 13px;
  font-weight: 600;
  color: var(--ink-2);
  cursor: pointer;
  white-space: nowrap;
}

.seg.modes button {
  padding: 7px 14px;
  font-size: 13.5px;
}

.seg.small button {
  padding: 4px 9px;
  font-size: 12.5px;
  font-weight: 500;
}

.seg button:hover:not(:disabled):not(.on) {
  background: var(--surface-2);
  color: var(--forest);
}

.seg button.on {
  background: var(--forest);
  color: #fbf3df;
  box-shadow: 0 1px 0 rgba(255, 255, 255, 0.12) inset;
}

.seg button:disabled {
  color: var(--ink-3);
  cursor: not-allowed;
  opacity: 0.6;
}

.metric-select {
  width: 220px;
}

.crumbs {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 5px 6px 5px 12px;
  border-radius: 10px;
  background: rgba(255, 253, 248, 0.96);
  border: 1px solid rgba(90, 70, 30, 0.2);
  box-shadow: 0 6px 18px -10px rgba(60, 40, 10, 0.45);
  font-size: 13.5px;
}

.crumb {
  border: none;
  background: none;
  padding: 0;
  font: inherit;
  color: var(--green);
  cursor: pointer;
}

.crumb:hover {
  text-decoration: underline;
}

.crumb-sep {
  color: var(--ink-3);
}

.crumb-meta {
  font-size: 12px;
  color: var(--ink-3);
}

.crumb-x {
  width: 24px;
  height: 24px;
  display: grid;
  place-items: center;
  border: none;
  border-radius: 7px;
  background: none;
  color: var(--ink-3);
  cursor: pointer;
}

.crumb-x:hover {
  background: var(--surface-2);
  color: var(--ink);
}

/* 找小区 */
.search {
  position: relative;
  width: 250px;
  display: flex;
  flex-direction: column;
}

.search-pop {
  position: absolute;
  top: 100%;
  left: 0;
  width: 320px;
}

.search-input :deep(.el-input__wrapper) {
  border-radius: 11px;
  box-shadow: 0 0 0 1px rgba(90, 70, 30, 0.18), 0 6px 18px -10px rgba(60, 40, 10, 0.45);
  background: rgba(255, 253, 248, 0.96);
}

.search-pop {
  margin-top: 6px;
  padding: 5px;
  border-radius: 12px;
  background: #fffdf8;
  border: 1px solid var(--line);
  box-shadow: 0 14px 34px -12px rgba(60, 40, 10, 0.45);
  display: flex;
  flex-direction: column;
}

.hit {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 1px;
  padding: 7px 10px;
  border: none;
  border-radius: 8px;
  background: none;
  font: inherit;
  text-align: left;
  cursor: pointer;
}

.hit b {
  font-size: 13.5px;
  color: var(--ink);
}

.hit span {
  font-size: 12px;
  color: var(--ink-3);
}

.hit.on {
  background: var(--green-wash);
}

.hit.empty {
  font-size: 12.5px;
  color: var(--ink-3);
  cursor: default;
}

.hit.all {
  margin-top: 2px;
  border-top: 1px dashed var(--line);
  border-radius: 0 0 8px 8px;
  font-size: 12.5px;
  color: var(--green);
  align-items: center;
}

/* 右下工具 */
.tools {
  display: flex;
  align-items: center;
  gap: 2px;
  padding: 4px;
  border-radius: 12px;
  background: rgba(255, 253, 248, 0.95);
  border: 1px solid var(--line);
  box-shadow: 0 6px 18px -10px rgba(60, 40, 10, 0.45);
}

.tool {
  min-width: 32px;
  height: 32px;
  padding: 0 4px;
  display: grid;
  place-items: center;
  border: none;
  border-radius: 8px;
  background: transparent;
  color: var(--ink-2);
  font-size: 16px;
  cursor: pointer;
}

.tool:hover:not(:disabled) {
  background: var(--surface-3);
  color: var(--forest);
}

.tool:focus-visible {
  outline: none;
  box-shadow: var(--ring);
}

.tool.on {
  background: var(--green);
  color: #fff;
}

.tool.zoom {
  font-size: 11.5px;
  font-weight: 600;
  min-width: 44px;
}

.tool-sep {
  width: 1px;
  height: 18px;
  background: var(--line);
  margin: 0 2px;
}

.map-compass {
  position: static;
}

.edge {
  position: absolute;
  z-index: 2;
  padding: 2px 8px;
  border-radius: 999px;
  background: rgba(255, 253, 248, 0.88);
  border: 1px solid var(--line);
  color: var(--ink-2);
  font-family: var(--font-serif);
  font-weight: 700;
  font-size: 13px;
  pointer-events: none;
}

.edge.top {
  top: 56px;
  left: 50%;
  transform: translateX(-50%);
}

.edge.bottom {
  bottom: 70px;
  left: 50%;
  transform: translateX(-50%);
}

.edge.left {
  left: 8px;
  top: 50%;
  transform: translateY(-50%);
}

.edge.right {
  right: 8px;
  top: 50%;
  transform: translateY(-50%);
}

.hint-3d {
  position: absolute;
  left: 50%;
  top: 58px;
  transform: translateX(-50%);
  z-index: 1;
  padding: 4px 12px;
  border-radius: 999px;
  background: rgba(255, 253, 248, 0.85);
  color: var(--ink-3);
  font-size: 12px;
  pointer-events: none;
  white-space: nowrap;
  animation: hint-fade 7s ease 1 forwards;
}

/* 底部浮动操作条 */
.action-bar {
  position: absolute;
  z-index: 3;
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
  padding: 8px 8px 8px 18px;
  box-shadow: 0 14px 34px -10px rgba(16, 46, 32, 0.55);
  white-space: nowrap;
}

.action-bar .el-button + .el-button {
  margin-left: 0;
}

.action-bar kbd {
  margin-left: 6px;
  padding: 0 5px;
  border-radius: 4px;
  background: rgba(59, 42, 12, 0.15);
  font-size: 10.5px;
  font-family: inherit;
}

/* 深色操作条上的按钮：主操作用麦穗金，其它是半透明的；不能点的更淡 */
.action-bar .el-button.is-disabled:not(.el-button--primary) {
  --el-button-disabled-bg-color: rgba(255, 255, 255, 0.04);
  --el-button-disabled-text-color: rgba(251, 243, 223, 0.35);
  --el-button-disabled-border-color: rgba(226, 182, 90, 0.15);
}

.action-bar .el-button--primary.is-disabled {
  opacity: 0.55;
}

.action-bar .el-button:not(.el-button--primary):not(.is-text) {
  --el-button-bg-color: rgba(255, 255, 255, 0.08);
  --el-button-text-color: #fbf3df;
  --el-button-border-color: rgba(226, 182, 90, 0.28);
  --el-button-hover-bg-color: rgba(255, 255, 255, 0.16);
  --el-button-hover-text-color: #ffffff;
  --el-button-hover-border-color: rgba(226, 182, 90, 0.55);
}

.action-bar .el-button--primary {
  background: linear-gradient(180deg, #ecc778, #d9a748) !important;
  border-color: #c8953a !important;
  color: #3b2a0c !important;
  box-shadow: 0 1px 0 rgba(255, 255, 255, 0.4) inset !important;
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
  z-index: 2;
  left: 50%;
  bottom: 18px;
  transform: translateX(-50%);
  color: var(--ink-2);
  font-size: 12.5px;
  background: rgba(255, 253, 248, 0.92);
  padding: 6px 16px;
  border-radius: 999px;
  border: 1px solid var(--line-strong);
  box-shadow: var(--shadow);
  white-space: nowrap;
}

.edit-bar .edit-state {
  font-size: 13px;
  color: #dfe9e1;
  display: flex;
  gap: 8px;
  align-items: center;
}

.edit-bar .edit-state em {
  font-style: normal;
  font-size: 12px;
  padding: 1px 8px;
  border-radius: 999px;
}

.edit-bar .gone {
  background: rgba(176, 84, 58, 0.85);
  color: #fff6ee;
}

.edit-bar .new {
  background: rgba(226, 182, 90, 0.85);
  color: #3b2a0c;
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

/* ---------- 右侧面板 ---------- */

.inspector-toggle {
  position: absolute;
  z-index: 4;
  top: 50%;
  right: 330px;
  transform: translate(50%, -50%);
  width: 20px;
  height: 44px;
  display: grid;
  place-items: center;
  padding: 0;
  border-radius: 10px;
  border: 1px solid var(--line-strong);
  background: #fffdf8;
  color: var(--ink-3);
  box-shadow: 0 4px 12px -6px rgba(60, 40, 10, 0.4);
  cursor: pointer;
}

.inspector-closed .inspector-toggle {
  right: 0;
  transform: translate(0, -50%);
  border-radius: 10px 0 0 10px;
}

.inspector-toggle:hover {
  color: var(--forest);
}

.inspector {
  min-width: 0;
  overflow: auto;
  padding: 14px 14px 18px;
  border-left: 1px solid var(--line);
  background: rgba(250, 246, 237, 0.85);
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.inspector :deep(.edit-panel) {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.plot-card .plot-head {
  display: flex;
  align-items: center;
  gap: 12px;
}

.plot-code {
  min-width: 52px;
  height: 52px;
  padding: 0 8px;
  display: grid;
  place-items: center;
  border-radius: 14px;
  background: var(--green-soft, #e3efe1);
  border: 1px solid #c9dccf;
  color: var(--forest);
  font-size: 22px;
  font-weight: 700;
}

.plot-head-text {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.plot-head-text b {
  font-size: 16px;
  color: var(--forest);
}

.small {
  font-size: 12px;
}

.kv {
  display: grid;
  grid-template-columns: 40px 1fr;
  gap: 6px 10px;
  margin: 14px 0 12px;
  font-size: 13px;
}

.kv span {
  color: var(--ink-3);
}

.kv b {
  font-weight: 600;
  display: flex;
  align-items: center;
  gap: 6px;
}

.dot-status {
  width: 10px;
  height: 10px;
  border-radius: 3px;
  border: 1px solid rgba(0, 0, 0, 0.12);
}

.danger-text {
  color: var(--clay);
}

.plot-actions {
  display: flex;
  gap: 8px;
}

.plot-actions .el-button {
  flex: 1;
  margin: 0;
}

.latest {
  display: flex;
  flex-direction: column;
}

.latest-row {
  display: grid;
  grid-template-columns: 1fr auto 42px;
  gap: 8px;
  align-items: baseline;
  padding: 6px 0;
  border-bottom: 1px dashed var(--line);
  font-size: 13px;
}

.latest-row .name {
  color: var(--ink-2);
}

.latest-row b {
  color: var(--ink);
}

.latest-row .faint {
  font-size: 11.5px;
  text-align: right;
}

.facet-group {
  margin-top: 10px;
}

.facet-title {
  font-size: 12px;
  color: var(--ink-3);
  margin-bottom: 4px;
}

.facet {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 5px 8px;
  margin: 0 -8px;
  width: calc(100% + 16px);
  border: none;
  border-radius: 7px;
  background: none;
  font: inherit;
  font-size: 13px;
  text-align: left;
  cursor: pointer;
}

.facet:hover {
  background: var(--green-wash);
}

.facet i {
  width: 14px;
  height: 14px;
  border-radius: 4px;
  border: 1px solid rgba(0, 0, 0, 0.1);
  flex-shrink: 0;
}

.facet span {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.facet b {
  color: var(--ink-2);
}

.legend-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 3px 0;
  font-size: 13px;
}

button.legend-row {
  width: calc(100% + 16px);
  margin: 0 -8px;
  padding: 4px 8px;
  border: none;
  border-radius: 7px;
  background: none;
  font: inherit;
  font-size: 13px;
  text-align: left;
  cursor: pointer;
}

button.legend-row:hover {
  background: var(--green-wash);
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
  border-radius: 4px;
  border: 1px solid rgba(0, 0, 0, 0.08);
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

.rep-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 3px 0;
  font-size: 13px;
}

.rep-btn {
  margin: 8px 0 2px;
  width: 100%;
}

.link-row {
  display: flex;
  justify-content: space-between;
  gap: 6px;
  padding: 6px 8px;
  margin: 0 -8px;
  border-radius: 6px;
  cursor: pointer;
}

.link-row:hover,
.link-row.active {
  background: #fff4e6;
}

.link-label {
  display: flex;
  align-items: center;
  gap: 6px;
  font-weight: 600;
  font-size: 13px;
}

.link-row.active {
  box-shadow: inset 3px 0 0 #c0562f;
}

.ck-dot {
  width: 10px;
  height: 10px;
  border-radius: 3px;
  border: 2px solid #c0562f;
  background: #fbe3d8;
  flex-shrink: 0;
}

.compare-trait {
  width: 100%;
  margin: 2px 0 10px;
}

.ck-mean {
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: 6px;
  padding: 8px 10px;
  border-radius: 8px;
  background: #fbefe7;
  font-size: 12.5px;
  color: #6f3a22;
}

.ck-mean b {
  font-size: 18px;
  color: #8f3a1c;
}

.ck-tag {
  align-self: center;
  padding: 0 5px;
  border-radius: 4px;
  background: #c0562f;
  color: #fff;
  font-size: 11px;
  font-weight: 700;
}

.compare .hint {
  margin: 6px 0 4px;
}

.cmp-list {
  display: flex;
  flex-direction: column;
  margin: 0 -6px;
}

.cmp-row {
  display: grid;
  grid-template-columns: 26px minmax(0, 1fr) auto 34px 50px;
  align-items: center;
  gap: 6px;
  padding: 5px 6px;
  border: none;
  border-radius: 6px;
  background: none;
  font: inherit;
  font-size: 12.5px;
  text-align: left;
  cursor: pointer;
}

.cmp-row:hover {
  background: var(--surface-3);
}

.cmp-code {
  color: var(--ink-3);
}

.cmp-name {
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
  color: var(--ink);
}

.cmp-val {
  color: var(--ink-2);
}

/* 小条：中间是对照，往右比对照高，往左比对照低 */
.cmp-bar {
  position: relative;
  height: 8px;
  border-radius: 4px;
  background: linear-gradient(90deg, transparent calc(50% - 0.5px), var(--ink-4) calc(50% - 0.5px), var(--ink-4) calc(50% + 0.5px), transparent calc(50% + 0.5px)), var(--surface-3);
}

.cmp-bar i {
  position: absolute;
  top: 1px;
  bottom: 1px;
  border-radius: 3px;
}

.cmp-bar i.up {
  background: #3f8f5a;
}

.cmp-bar i.down {
  background: #c0562f;
}

.cmp-pct {
  text-align: right;
  font-weight: 600;
}

.cmp-pct.up {
  color: #2f7a48;
}

.cmp-pct.down {
  color: #b0462a;
}

.tips ul {
  margin: 0;
  padding-left: 18px;
  font-size: 12.5px;
  line-height: 1.9;
  color: var(--ink-2);
}

kbd {
  display: inline-block;
  padding: 0 6px;
  border-radius: 5px;
  border: 1px solid var(--line-strong);
  border-bottom-width: 2px;
  background: #fffdf8;
  font-family: inherit;
  font-size: 11.5px;
  line-height: 18px;
  color: var(--ink-2);
}

/* 右键菜单 */
/* 育种：右键菜单的子菜单（种植、整块种） */
.menu-item.has-sub {
  position: relative;
}

.menu-item .arrow {
  color: var(--ink-4);
}

.submenu {
  display: none;
  position: absolute;
  left: 100%;
  top: -5px;
  min-width: 210px;
  max-height: 420px;
  overflow: auto;
  padding: 4px;
  background: #fffdf8;
  border: 1px solid var(--line);
  border-radius: 10px;
  box-shadow: 0 14px 34px -12px rgba(60, 40, 10, 0.45);
  cursor: default;
}

.context-menu.flip .submenu {
  left: auto;
  right: 100%;
}

.context-menu.up .submenu {
  top: auto;
  bottom: -5px;
}

.menu-item.has-sub:hover > .submenu {
  display: block;
}

.submenu .sub-title {
  padding: 6px 10px 2px;
  font-size: 11px;
  color: var(--ink-3);
}

.submenu .muted {
  margin-left: auto;
  font-family: var(--font-mono);
  font-size: 11px;
}

.drop-hint {
  position: absolute;
  left: 50%;
  top: 58px;
  transform: translateX(-50%);
  z-index: 20;
  padding: 6px 14px;
  border-radius: 999px;
  background: var(--forest);
  color: #fff;
  font-size: var(--fs-sm);
  pointer-events: none;
  box-shadow: 0 6px 18px -8px rgba(0, 0, 0, 0.5);
}

.season-chip {
  padding: 4px 10px;
  border-radius: 999px;
  background: var(--wheat-wash);
  border: 1px solid var(--wheat-soft);
  color: var(--wheat-ink);
  font-size: var(--fs-sm);
  font-weight: 600;
  white-space: nowrap;
}

.context-menu {
  position: fixed;
  z-index: 3000;
  min-width: 190px;
  background: #fffdf8;
  border: 1px solid var(--line);
  border-radius: 10px;
  box-shadow: 0 14px 34px -12px rgba(60, 40, 10, 0.45);
  padding: 4px;
  font-size: 13px;
}

.menu-title {
  padding: 6px 10px;
  font-weight: 600;
  border-bottom: 1px solid var(--line);
  margin-bottom: 4px;
  color: var(--forest);
}

.menu-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  padding: 7px 10px;
  border-radius: 7px;
  cursor: pointer;
}

.menu-item:hover {
  background: var(--green-wash);
}

.menu-item kbd {
  font-size: 10.5px;
  line-height: 16px;
}

.menu-sep {
  height: 1px;
  background: var(--line);
  margin: 4px 0;
}

.menu-note {
  font-size: 11.5px;
  color: var(--ink-3);
}

.menu-sub {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  padding: 5px 10px 3px;
  color: var(--ink-2);
}

.menu-row {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 4px;
  padding: 2px 6px 6px;
}

.menu-row button {
  padding: 4px 0;
  border: 1px solid var(--line-strong);
  border-radius: 6px;
  background: var(--surface);
  font: inherit;
  font-size: 12.5px;
  color: var(--ink);
  cursor: pointer;
}

.menu-row button:hover:not(:disabled) {
  border-color: var(--green-2);
  background: var(--green-wash);
}

.menu-row button:disabled {
  color: var(--ink-4);
  background: var(--surface-2);
  cursor: not-allowed;
}

/* 道路工具：一段带虚线的路 */
.road-icon {
  display: inline-block;
  width: 16px;
  height: 10px;
  border-radius: 2px;
  background: #cdbf9f linear-gradient(90deg, transparent 15%, #fff 15%, #fff 40%, transparent 40%, transparent 60%, #fff 60%, #fff 85%, transparent 85%) center / 100% 1.5px no-repeat;
  border: 1px solid #8a7a55;
}

.tool.on .road-icon {
  border-color: #fff;
}

.road-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 12px;
  border-radius: 10px;
  background: rgba(255, 253, 248, 0.96);
  border: 1px solid rgba(90, 70, 30, 0.2);
  box-shadow: 0 6px 18px -10px rgba(60, 40, 10, 0.45);
  font-size: 13px;
  color: var(--ink-2);
  max-width: 640px;
}

.road-w {
  width: 96px;
}

.road-tip {
  font-size: 12px;
  color: var(--ink-3);
}

.shortcuts {
  display: grid;
  grid-template-columns: max-content 1fr;
  gap: 9px 16px;
  align-items: center;
  font-size: 13.5px;
}

.shortcuts kbd {
  justify-self: start;
}

@media (max-width: 1280px) {
  .chips .chip-stat:not(.stage):not(.danger) {
    display: none;
  }

  .workspace {
    grid-template-columns: minmax(0, 1fr) 290px;
  }

  .inspector-toggle {
    right: 290px;
  }
}
</style>
