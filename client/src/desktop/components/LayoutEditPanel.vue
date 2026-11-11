<script setup lang="ts">
// 编辑地块的属性面板：按选中的东西显示——整块地（大小、道路宽、方向、单位）、一个区块或一条道路（名称、类型、位置、大小、
// 小区块行列和大小）、几个区块（对齐、排开、合并）、区块里的小区块（行列、行高列宽、合并拆分）
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowLeft, CopyDocument, Delete, Grid, Scissor } from '@element-plus/icons-vue'

import {
  BLOCK_KIND, CELL_KIND, UNIT_PRESETS, alignBlocks, anchors, arrayBlocks, blockRect, cellKind, conflicts, contentBounds, distributeBlocks,
  evenInnerTracks, fieldSize, fitField, fmt, innerSizes, keyOf, mergeAt, mergeBlocks, mergeInner, parseKey, plotCount, plotSize,
  removeRoad, removedPlots, renameBlock, resizeInner, roadAxis, round, setBlockKind, setBlockRect, setCellKinds, setFieldSize,
  setInnerTrackSize, setPlotSize, setRoadWidth, setUnit, splitBlock, splitInner,
  type Align, type BlockKind, type FieldLayout, type LayoutBlock, type Unit,
} from '@/shared/fieldLayout'
import MergeBlocksDialog from './MergeBlocksDialog.vue'

const props = defineProps<{
  layout: FieldLayout
  selectedBlocks: string[]
  selectedCells: string[]
  focus: string | null
  track: { axis: 'row' | 'col'; index: number } | null
  /** 画面现在转了多少度：设"屏幕上方为北"时用 */
  rotation?: number
  /** 小区块 id → 编号，提示会去掉哪些小区块时用 */
  plotCode: (id: number) => string
  /** 画的道路多宽；摆区块时也按它留间隔 */
  roadWidth: number
}>()

const emit = defineEmits<{
  apply: [layout: FieldLayout]
  'update:selectedBlocks': [ids: string[]]
  'update:selectedCells': [keys: string[]]
  'update:track': [t: { axis: 'row' | 'col'; index: number } | null]
  'update:roadWidth': [w: number]
  enter: [id: string]
  leave: []
  /** 删掉选中的区块、复制一份（平面图页面里做，快捷键也走那里） */
  remove: []
  duplicate: []
}>()

const u = computed(() => props.layout.unit)
const blocks = computed(() => props.selectedBlocks.map((id) => props.layout.blocks.find((b) => b.id === id)).filter((b) => !!b))
const one = computed(() => (blocks.value.length === 1 ? blocks.value[0]! : null))
const focusBlock = computed(() => props.layout.blocks.find((b) => b.id === props.focus) ?? null)

/** 会去掉小区块时先问一下 */
async function confirmDrop(next: FieldLayout, what: string): Promise<boolean> {
  const gone = removedPlots(props.layout, next)
  if (gone.length === 0) return true
  const codes = gone.slice(0, 8).map(props.plotCode).join('、')
  try {
    await ElMessageBox.confirm(
      `${what}会去掉 ${gone.length} 个已有的小区块（${codes}${gone.length > 8 ? ' 等' : ''}）。保存时，已经分配了品种或录了数据的小区块不能去掉。`,
      '会去掉小区块', { type: 'warning', confirmButtonText: '继续', cancelButtonText: '取消' },
    )
    return true
  } catch {
    return false
  }
}

async function commit(next: FieldLayout, what = '这样改') {
  if (next === props.layout) return
  if (await confirmDrop(next, what)) emit('apply', next)
}

// ---------- 整块地 ----------

const CUSTOM = '__custom'
const unitPick = ref('')
const customUnit = reactive({ name: '', meters: 1 })
const convert = ref(true)

watch(() => props.layout.unit, (unit) => {
  unitPick.value = UNIT_PRESETS.some((p) => p.name === unit.name && Math.abs(p.meters - unit.meters) < 1e-9) ? unit.name : CUSTOM
  customUnit.name = unit.name
  customUnit.meters = round(unit.meters, 4)
}, { immediate: true })

function changeUnit() {
  let next: Unit
  if (unitPick.value === CUSTOM) {
    const name = customUnit.name.trim()
    if (!name || !(customUnit.meters > 0)) return
    next = { name, symbol: name, meters: customUnit.meters }
  } else {
    next = UNIT_PRESETS.find((p) => p.name === unitPick.value) ?? UNIT_PRESETS[0]!
  }
  if (next.name === u.value.name && Math.abs(next.meters - u.value.meters) < 1e-9) return
  // 道路宽也跟着换算
  if (convert.value) emit('update:roadWidth', round((props.roadWidth * u.value.meters) / next.meters, 3))
  emit('apply', setUnit(props.layout, next, convert.value))
}

const size = computed(() => fieldSize(props.layout))
const content = computed(() => contentBounds(props.layout))
/** 地块四周有没有空着的边（能收紧） */
const loose = computed(() => fitField(props.layout) !== props.layout)

function setSize(w: number | undefined, h: number | undefined) {
  const next = setFieldSize(props.layout, w ?? size.value.w, h ?? size.value.h)
  if (next === props.layout && (w != null || h != null)) {
    const c = content.value
    if (c && ((w != null && w < c.x + c.w - 1e-6) || (h != null && h < c.y + c.h - 1e-6))) {
      ElMessage.warning('地块不能比里面的区块、道路小。先把它们挪进来或者删掉')
    }
  }
  emit('apply', next)
}

function setDefaultInner(rows: number | undefined, cols: number | undefined) {
  if (!rows || !cols) return
  emit('apply', { ...props.layout, defaultInner: { rows, cols } })
}

const stats = computed(() => ({
  plant: props.layout.blocks.filter((b) => b.kind === 'PLANT').length,
  road: props.layout.blocks.filter((b) => b.kind === 'ROAD').length,
  other: props.layout.blocks.filter((b) => b.kind === 'GUARD' || b.kind === 'BLANK').length,
  plots: plotCount(props.layout),
}))

/** 叠在一起的（叠着不能保存） */
const clashes = computed(() => {
  const name = (id: string) => props.layout.blocks.find((b) => b.id === id)?.name ?? id
  return conflicts(props.layout).map(([a, b]) => ({ a, b, text: `${name(a)} 和 ${name(b)}` }))
})

// ---------- 真北方向 ----------

const north = computed(() => props.layout.northDeg ?? 0)
const NORTH_PRESETS: { deg: number; label: string }[] = [
  { deg: 0, label: '北在上' }, { deg: 90, label: '北在右' }, { deg: 180, label: '北在下' }, { deg: 270, label: '北在左' },
]

function setNorth(deg: number | undefined) {
  if (deg == null || !Number.isFinite(deg)) return
  const d = Math.round((((deg % 360) + 360) % 360) * 10) / 10
  if (d !== north.value) emit('apply', { ...props.layout, northDeg: d })
}

// ---------- 区块 ----------

const KINDS: BlockKind[] = ['PLANT', 'ROAD', 'GUARD', 'BLANK']

const kindOfSelection = computed<BlockKind | ''>(() => {
  const kinds = new Set(blocks.value.map((b) => b.kind))
  return kinds.size === 1 ? [...kinds][0]! : ''
})

async function setKind(kind: BlockKind) {
  await commit(setBlockKind(props.layout, props.selectedBlocks, kind), `改成${BLOCK_KIND[kind]}`)
}

function rename(name: string) {
  if (one.value && name.trim() && name.trim() !== one.value.name) emit('apply', renameBlock(props.layout, one.value.id, name))
}

const nameDraft = ref('')
watch(() => one.value?.name, (n) => { nameDraft.value = n ?? '' }, { immediate: true })

/** 改选中这一个的位置、大小（没给的不变） */
function setRect(part: { x?: number; y?: number; w?: number; h?: number }) {
  const b = one.value
  if (!b) return
  const next = { x: b.x, y: b.y, w: b.w, h: b.h }
  for (const [k, v] of Object.entries(part) as ['x' | 'y' | 'w' | 'h', number | undefined][]) {
    if (v == null || !Number.isFinite(v)) return
    next[k] = v
  }
  emit('apply', setBlockRect(props.layout, b.id, next))
}

const plotsIn = (b: LayoutBlock) => (b.inner ? anchors(b.inner.rows.length, b.inner.cols.length, b.inner.merges).length : 0)

const innerCountOfSelection = computed(() => {
  const withInner = blocks.value.filter((b) => b.inner)
  if (withInner.length === 0) return null
  const r = new Set(withInner.map((b) => b.inner!.rows.length))
  const c = new Set(withInner.map((b) => b.inner!.cols.length))
  return {
    rows: r.size === 1 ? [...r][0]! : undefined, cols: c.size === 1 ? [...c][0]! : undefined, n: withInner.length,
    plots: withInner.reduce((n, b) => n + plotsIn(b), 0),
  }
})

async function setInnerCounts(rows: number | undefined, cols: number | undefined) {
  let next = props.layout
  for (const b of blocks.value) {
    if (!b.inner) continue
    next = resizeInner(next, b.id, rows ?? b.inner.rows.length, cols ?? b.inner.cols.length)
  }
  await commit(next, '减少小区块行列')
}

/** 选中的种植区块里每个小区块多大：都一样时才有数，不一样的那一项是 undefined */
const plotSizeOfSelection = computed(() => {
  const sizes = blocks.value.filter((b) => b.inner).map((b) => plotSize(props.layout, b))
  if (sizes.length === 0) return null
  const same = (pick: (s: { w: number; h: number }) => number) => {
    if (sizes.some((s) => !s)) return undefined
    const v = pick(sizes[0]!)
    return sizes.every((s) => Math.abs(pick(s!) - v) < 1e-4) ? round(v, 3) : undefined
  }
  return { w: same((s) => s.w), h: same((s) => s.h) }
})

function setPlot(w: number | undefined, h: number | undefined) {
  emit('apply', setPlotSize(props.layout, props.selectedBlocks, w ?? null, h ?? null))
}

// ---------- 合并、拆开、照着排一片 ----------

const mergeProblem = computed(() => (blocks.value.length >= 2 ? mergeBlocks(props.layout, props.selectedBlocks).problem : null))
const mergeOpen = ref(false)

function merge() {
  if (blocks.value.length < 2) return
  if (mergeProblem.value) {
    ElMessage.warning(`合并不了：${mergeProblem.value}`)
    return
  }
  mergeOpen.value = true
}

function onMerged(next: FieldLayout, anchorId: string) {
  emit('apply', next)
  emit('update:selectedBlocks', [anchorId])
}

const splitOpen = ref(false)
const splitForm = reactive({ rows: 2, cols: 1 })

function openSplit() {
  const b = one.value
  if (!b) return
  // 默认照长的方向拆成两块；那个方向上只有一行（列）小区块、拆不了时换另一个方向
  const canRows = !b.inner || b.inner.rows.length >= 2
  const canCols = !b.inner || b.inner.cols.length >= 2
  const byRows = b.h >= b.w ? canRows || !canCols : !canCols && canRows
  splitForm.rows = byRows ? 2 : 1
  splitForm.cols = byRows ? 1 : 2
  splitOpen.value = true
}

const splitPreview = computed(() => (one.value ? splitBlock(props.layout, one.value.id, splitForm.rows, splitForm.cols) : null))

function applySplit() {
  const res = splitPreview.value
  if (!res || res.problem) return
  emit('apply', res.layout)
  emit('update:selectedBlocks', res.ids)
  splitOpen.value = false
}

const arrayOpen = ref(false)
const arrayForm = reactive({ cols: 3, rows: 1, gapX: 1, gapY: 1 })

function openArray() {
  arrayForm.cols = 3
  arrayForm.rows = 1
  arrayForm.gapX = props.roadWidth
  arrayForm.gapY = props.roadWidth
  arrayOpen.value = true
}

const arrayPreview = computed(() => {
  const b = one.value
  if (!b) return null
  return {
    w: round(arrayForm.cols * b.w + (arrayForm.cols - 1) * arrayForm.gapX, 3),
    h: round(arrayForm.rows * b.h + (arrayForm.rows - 1) * arrayForm.gapY, 3),
    count: arrayForm.cols * arrayForm.rows - 1,
  }
})

function applyArray() {
  const b = one.value
  if (!b) return
  const res = arrayBlocks(props.layout, b.id, arrayForm.cols, arrayForm.rows, arrayForm.gapX, arrayForm.gapY)
  if (res.ids.length === 0) {
    ElMessage.warning('排不了：至少要排出两个，区块、道路加起来也不能太多')
    return
  }
  emit('apply', res.layout)
  emit('update:selectedBlocks', [b.id, ...res.ids])
  arrayOpen.value = false
}

defineExpose({ merge, openSplit, openArray })

// ---------- 对齐、排开 ----------

const ALIGNS: [Align, string][] = [
  ['left', '靠左'], ['hcenter', '左右居中'], ['right', '靠右'], ['top', '靠上'], ['vcenter', '上下居中'], ['bottom', '靠下'],
]

function align(a: Align) {
  emit('apply', alignBlocks(props.layout, props.selectedBlocks, a))
}

const spreadGap = ref(1)
watch(() => props.roadWidth, (w) => { spreadGap.value = w }, { immediate: true })

/** 排开：axis 为 col 是横着排，row 是竖着排；even 为 true 时两头不动、中间匀开 */
function spread(axis: 'row' | 'col', even: boolean) {
  const next = distributeBlocks(props.layout, props.selectedBlocks, axis, even ? undefined : spreadGap.value)
  if (next === props.layout && even && blocks.value.length < 3) ElMessage.info('匀开要选三个或更多')
  emit('apply', next)
}

// ---------- 道路 ----------

const road = computed(() => {
  const b = one.value
  if (!b || b.kind !== 'ROAD') return null
  const axis = roadAxis(b)
  return { axis, width: axis === 'row' ? b.h : b.w, length: axis === 'row' ? b.w : b.h }
})

function changeRoadWidth(v: number | undefined) {
  if (one.value && v && v > 0) emit('apply', setRoadWidth(props.layout, one.value.id, v))
}

function changeRoadLength(v: number | undefined) {
  if (!one.value || !road.value || !v || v <= 0) return
  setRect(road.value.axis === 'row' ? { w: v } : { h: v })
}

/** 删掉这条道路，后面的收回来合拢 */
function closeRoad() {
  if (!one.value) return
  const res = removeRoad(props.layout, one.value.id, true)
  if (!res.closed) ElMessage.info('后面的东西收回来会和别的叠在一起，所以只删了路，没有合拢')
  emit('apply', res.layout)
  emit('update:selectedBlocks', [])
}

// ---------- 区块里的小区块 ----------

const trackName = computed(() => {
  const t = props.track
  if (!t) return ''
  return t.axis === 'row' ? `第 ${t.index + 1} 行` : `第 ${t.index + 1} 列`
})

const innerTrackSize = computed(() => {
  const b = focusBlock.value
  const t = props.track
  if (!b || !t) return null
  const s = innerSizes(props.layout, b)
  return (t.axis === 'row' ? s.rows : s.cols)[t.index] ?? null
})

function setInnerTrack(v: number | undefined) {
  const b = focusBlock.value
  const t = props.track
  if (!b || !t || !v || v <= 0) return
  emit('apply', setInnerTrackSize(props.layout, b.id, t.axis, t.index, v))
}

function evenTracks(axis: 'row' | 'col') {
  if (focusBlock.value) emit('apply', evenInnerTracks(props.layout, focusBlock.value.id, axis))
}

async function setFocusInner(rows: number | undefined, cols: number | undefined) {
  const b = focusBlock.value
  if (!b?.inner || !rows || !cols) return
  await commit(resizeInner(props.layout, b.id, rows, cols), '减少小区块行列')
}

const cellsSel = computed(() => props.selectedCells.map(parseKey))

const cellMerge = computed(() => {
  const b = focusBlock.value
  if (!b?.inner || cellsSel.value.length < 2) return null
  const rs = cellsSel.value.map(([r, c]) => b.inner!.merges.find((m) => m.r === r && m.c === c) ?? { r, c, rs: 1, cs: 1 })
  const a = { r: Math.min(...rs.map((s) => s.r)), c: Math.min(...rs.map((s) => s.c)) }
  const z = { r: Math.max(...rs.map((s) => s.r + s.rs - 1)), c: Math.max(...rs.map((s) => s.c + s.cs - 1)) }
  return { a, z, result: mergeInner(props.layout, b.id, a, z) }
})

async function mergeCells() {
  const m = cellMerge.value
  if (!m) return
  if (await confirmDrop(m.result.layout, '合并这几个小区块')) {
    emit('apply', m.result.layout)
    emit('update:selectedCells', [keyOf(m.a.r, m.a.c)])
  }
}

const splittableCell = computed(() => {
  const b = focusBlock.value
  if (!b?.inner || cellsSel.value.length !== 1) return null
  const [r, c] = cellsSel.value[0]!
  return mergeAt(b.inner.merges, r, c) ?? null
})

function splitCell() {
  const b = focusBlock.value
  const m = splittableCell.value
  if (b && m) emit('apply', splitInner(props.layout, b.id, m.r, m.c))
}


/** 选中格子的类型（都一样时） */
const cellKindOfSelection = computed(() => {
  const b = focusBlock.value
  if (!b?.inner || props.selectedCells.length === 0) return ''
  const kinds = new Set(props.selectedCells.map((k) => cellKind(b.inner, k)))
  return kinds.size === 1 ? [...kinds][0]! : ''
})

function setCellKind(kind: BlockKind) {
  const b = focusBlock.value
  if (b) emit('apply', setCellKinds(props.layout, b.id, props.selectedCells, kind))
}
</script>

<template>
  <div class="edit-panel">
    <MergeBlocksDialog v-model="mergeOpen" :layout="layout" :block-ids="selectedBlocks" :plot-code="plotCode" @apply="onMerged" />

    <el-dialog v-model="splitOpen" title="拆成几块" width="420px" append-to-body>
      <div v-if="one" class="dlg-text">把“{{ one.name }}”拆成几个区块。拆完还在原来的地方、拼起来一样大，可以再拖开，或者在中间画道路。</div>
      <div class="row-form">
        <el-input-number v-model="splitForm.rows" :min="1" :max="60" controls-position="right" class="n2" />
        <span>行 ×</span>
        <el-input-number v-model="splitForm.cols" :min="1" :max="60" controls-position="right" class="n2" />
        <span>列区块</span>
      </div>
      <div v-if="splitPreview?.problem" class="hint warn">{{ splitPreview.problem }}</div>
      <div v-else-if="one?.inner" class="hint">按小区块之间的线拆，小区块都留着，编号、品种、数据跟着。</div>
      <template #footer>
        <el-button @click="splitOpen = false">取消</el-button>
        <el-button type="primary" :disabled="!splitPreview || !!splitPreview.problem" @click="applySplit">拆开</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="arrayOpen" title="照着排一片" width="440px" append-to-body>
      <div v-if="one" class="dlg-text">照着“{{ one.name }}”再排出一样的区块（一样大、一样的小区块行列），它自己算第一个。</div>
      <div class="row-form">
        <span class="lbl">横着排</span>
        <el-input-number v-model="arrayForm.cols" :min="1" :max="50" controls-position="right" class="n2" />
        <span>个，间隔</span>
        <el-input-number v-model="arrayForm.gapX" :min="0" :precision="2" :step="0.5" controls-position="right" class="n2" />
        <span>{{ u.symbol }}</span>
      </div>
      <div class="row-form">
        <span class="lbl">竖着排</span>
        <el-input-number v-model="arrayForm.rows" :min="1" :max="50" controls-position="right" class="n2" />
        <span>个，间隔</span>
        <el-input-number v-model="arrayForm.gapY" :min="0" :precision="2" :step="0.5" controls-position="right" class="n2" />
        <span>{{ u.symbol }}</span>
      </div>
      <div v-if="arrayPreview" class="hint">
        新加 {{ arrayPreview.count }} 个区块，这一片一共 {{ fmt(arrayPreview.w, u) }} × {{ fmt(arrayPreview.h, u) }}。间隔里是空地，要铺成道路就用道路工具画。
      </div>
      <template #footer>
        <el-button @click="arrayOpen = false">取消</el-button>
        <el-button type="primary" :disabled="!arrayPreview || arrayPreview.count < 1" @click="applyArray">排出来</el-button>
      </template>
    </el-dialog>

    <!-- 区块里 -->
    <template v-if="focusBlock">
      <section class="panel panel-pad">
        <div class="panel-title">
          {{ focusBlock.name }}<span class="sub">区块里的小区块</span>
        </div>
        <el-button size="small" :icon="ArrowLeft" class="back" @click="emit('leave')">回到整块地</el-button>
        <div class="row-form">
          <span class="lbl">小区块</span>
          <el-input-number
            :model-value="focusBlock.inner?.rows.length" :min="1" :max="60" size="small" controls-position="right" class="n"
            @change="(v: number | undefined) => setFocusInner(v, focusBlock?.inner?.cols.length)"
          />
          <span>行 ×</span>
          <el-input-number
            :model-value="focusBlock.inner?.cols.length" :min="1" :max="60" size="small" controls-position="right" class="n"
            @change="(v: number | undefined) => setFocusInner(focusBlock?.inner?.rows.length, v)"
          />
          <span>列</span>
        </div>
        <div class="hint">区块 {{ fmt(blockRect(layout, focusBlock).w, u) }} × {{ fmt(blockRect(layout, focusBlock).h, u) }}，小区块按比例分。</div>
        <div class="btn-row">
          <el-button size="small" @click="evenTracks('row')">各行一样高</el-button>
          <el-button size="small" @click="evenTracks('col')">各列一样宽</el-button>
        </div>
      </section>

      <section v-if="track && innerTrackSize != null" class="panel panel-pad">
        <div class="panel-title">{{ trackName }}小区块</div>
        <div class="row-form">
          <span class="lbl">{{ track.axis === 'row' ? '高' : '宽' }}</span>
          <el-input-number
            :model-value="round(innerTrackSize, 3)" :min="0.001" :precision="3" :controls="false" size="small" class="w"
            @change="setInnerTrack"
          />
          <span>{{ u.symbol }}</span>
        </div>
        <div class="hint">其它{{ track.axis === 'row' ? '行' : '列' }}按原来的比例分剩下的，区块总大小不变。</div>
      </section>

      <section class="panel panel-pad">
        <div class="panel-title">
          选中的小区块<span class="sub">{{ selectedCells.length }} 个</span>
        </div>
        <div v-if="selectedCells.length === 0" class="hint">
          点小区块选中；拖动框选几个；点上面、左边的行号列号选整行整列。选中后可以拖到别的位置，拖小区块之间的线改行高列宽。
        </div>
        <template v-else>
          <div class="lbl-top">类型</div>
          <div class="kinds">
            <button
              v-for="k in KINDS" :key="k" class="kind" :class="[k.toLowerCase(), { on: cellKindOfSelection === k }]"
              @click="setCellKind(k)"
            >
              <i />{{ CELL_KIND[k] }}
            </button>
          </div>
          <div class="hint">道路、保护区、空地不种试验品种，也不录数据；保存布局后小区块类型跟着变。</div>
        </template>
        <div class="btn-row">
          <el-button v-if="cellMerge" type="primary" size="small" :icon="CopyDocument" @click="mergeCells">
            合并成一个小区块
          </el-button>
          <el-button v-if="splittableCell" size="small" :icon="Scissor" @click="splitCell">拆开</el-button>
        </div>
        <div v-if="cellMerge && cellMerge.result.dropped.length" class="hint warn">
          合并后留下左上角的小区块，其余 {{ cellMerge.result.dropped.length }} 个小区块去掉。
        </div>
      </section>
    </template>

    <!-- 整块地：选了一个或几个区块、道路 -->
    <template v-else-if="blocks.length > 0">
      <section class="panel panel-pad">
        <div class="panel-title">
          {{ one ? one.name : `选了 ${blocks.length} 个` }}
          <span v-if="one" class="sub">{{ BLOCK_KIND[one.kind] }}</span>
        </div>

        <div v-if="one" class="row-form">
          <span class="lbl">名称</span>
          <el-input v-model="nameDraft" size="small" maxlength="30" @change="rename" />
        </div>

        <div class="lbl-top">类型</div>
        <div class="kinds">
          <button
            v-for="k in KINDS" :key="k" class="kind" :class="[k.toLowerCase(), { on: kindOfSelection === k }]"
            @click="setKind(k)"
          >
            <i />{{ BLOCK_KIND[k] }}
          </button>
        </div>

        <div class="btn-row">
          <el-button v-if="one?.kind === 'PLANT'" type="primary" size="small" :icon="Grid" @click="emit('enter', one.id)">进去改小区块</el-button>
          <el-button v-if="blocks.length > 1" type="primary" size="small" :icon="CopyDocument" @click="merge">合并成一个</el-button>
          <el-button size="small" :icon="CopyDocument" @click="emit('duplicate')">复制一份</el-button>
          <el-button v-if="one && one.kind !== 'ROAD'" size="small" :icon="Scissor" @click="openSplit">拆成几块</el-button>
          <el-button v-if="one && one.kind !== 'ROAD'" size="small" @click="openArray">照着排一片</el-button>
          <el-button size="small" type="danger" plain :icon="Delete" @click="emit('remove')">删除</el-button>
        </div>
        <div v-if="blocks.length > 1 && mergeProblem" class="hint">现在合并不了：{{ mergeProblem }}。</div>
      </section>

      <!-- 一条道路 -->
      <section v-if="one && road" class="panel panel-pad">
        <div class="panel-title">这条道路<span class="sub">{{ road.axis === 'row' ? '横向' : '纵向' }}</span></div>
        <div class="row-form">
          <span class="lbl">路宽</span>
          <el-input-number
            :model-value="round(road.width, 3)" :min="0.01" :precision="3" :controls="false" size="small" class="w"
            @change="changeRoadWidth"
          />
          <span>{{ u.symbol }}</span>
        </div>
        <div class="hint">改路宽时两边的区块大小不变：变宽了后面的往后挪，变窄了收回来，地块跟着变。</div>
        <div class="row-form">
          <span class="lbl">路长</span>
          <el-input-number
            :model-value="round(road.length, 3)" :min="0.01" :precision="3" :controls="false" size="small" class="w"
            @change="changeRoadLength"
          />
          <span>{{ u.symbol }}</span>
        </div>
        <div class="btn-row">
          <el-button size="small" @click="closeRoad">删掉并合拢</el-button>
        </div>
        <div class="hint">“删除”只去掉路，空出来的地方留着；“删掉并合拢”把后面的收回来。也可以直接在图上拖它、拉它的两头。</div>
      </section>

      <!-- 一个：位置和大小 -->
      <section v-if="one" class="panel panel-pad">
        <div class="panel-title">位置和大小<span class="sub">{{ u.name }}</span></div>
        <div class="row-form pair">
          <span class="lbl">离左边</span>
          <el-input-number
            :model-value="round(one.x, 3)" :precision="3" :controls="false" size="small" class="w2"
            @change="(v: number | undefined) => setRect({ x: v })"
          />
          <span class="lbl">离上边</span>
          <el-input-number
            :model-value="round(one.y, 3)" :precision="3" :controls="false" size="small" class="w2"
            @change="(v: number | undefined) => setRect({ y: v })"
          />
        </div>
        <div v-if="!road" class="row-form pair">
          <span class="lbl">宽</span>
          <el-input-number
            :model-value="round(one.w, 3)" :min="0.01" :precision="3" :controls="false" size="small" class="w2"
            @change="(v: number | undefined) => setRect({ w: v })"
          />
          <span class="lbl">长</span>
          <el-input-number
            :model-value="round(one.h, 3)" :min="0.01" :precision="3" :controls="false" size="small" class="w2"
            @change="(v: number | undefined) => setRect({ h: v })"
          />
        </div>
        <div class="lbl-top">贴到地块的边上</div>
        <div class="align">
          <el-tooltip v-for="a in ALIGNS" :key="a[0]" :content="a[1]" placement="top">
            <button class="align-btn" :class="a[0]" :aria-label="a[1]" @click="align(a[0])"><i /></button>
          </el-tooltip>
        </div>
        <div class="hint">也可以直接在图上按住拖；选中后拖四周的小方块改大小；方向键一点点挪。</div>
      </section>

      <!-- 几个：对齐、排开 -->
      <section v-else class="panel panel-pad">
        <div class="panel-title">对齐和排开</div>
        <div class="align">
          <el-tooltip v-for="a in ALIGNS" :key="a[0]" :content="a[1]" placement="top">
            <button class="align-btn" :class="a[0]" :aria-label="a[1]" @click="align(a[0])"><i /></button>
          </el-tooltip>
        </div>
        <div class="row-form">
          <span class="lbl">间隔</span>
          <el-input-number v-model="spreadGap" :min="0" :precision="2" :step="0.5" size="small" controls-position="right" class="w" />
          <span>{{ u.symbol }}</span>
        </div>
        <div class="btn-row tight">
          <el-button size="small" @click="spread('col', false)">横着一个挨一个排</el-button>
          <el-button size="small" @click="spread('row', false)">竖着一个挨一个排</el-button>
        </div>
        <div class="btn-row">
          <el-button size="small" @click="spread('col', true)">横着匀开</el-button>
          <el-button size="small" @click="spread('row', true)">竖着匀开</el-button>
        </div>
        <div class="hint">“一个挨一个排”从最左（最上）那个开始，中间隔上面填的间隔；“匀开”两头不动，中间的间隔调成一样。</div>
      </section>

      <section v-if="innerCountOfSelection" class="panel panel-pad">
        <div class="panel-title">
          里面的小区块<span class="sub">{{ innerCountOfSelection.n > 1 ? `${innerCountOfSelection.n} 个区块一起改` : `${innerCountOfSelection.plots} 个` }}</span>
        </div>
        <div class="row-form">
          <el-input-number
            :model-value="innerCountOfSelection.rows" :min="1" :max="60" size="small" controls-position="right" class="n"
            placeholder="—" @change="(v: number | undefined) => setInnerCounts(v, undefined)"
          />
          <span>行 ×</span>
          <el-input-number
            :model-value="innerCountOfSelection.cols" :min="1" :max="60" size="small" controls-position="right" class="n"
            placeholder="—" @change="(v: number | undefined) => setInnerCounts(undefined, v)"
          />
          <span>列</span>
        </div>
        <div class="lbl-top">每个小区块多大</div>
        <div v-if="plotSizeOfSelection" class="row-form">
          <span class="lbl short">宽</span>
          <el-input-number
            :model-value="plotSizeOfSelection.w" :min="0.01" :precision="3" :controls="false" size="small" class="w2" placeholder="不一样"
            @change="(v: number | undefined) => setPlot(v, undefined)"
          />
          <span class="lbl short mid">长</span>
          <el-input-number
            :model-value="plotSizeOfSelection.h" :min="0.01" :precision="3" :controls="false" size="small" class="w2" placeholder="不一样"
            @change="(v: number | undefined) => setPlot(undefined, v)"
          />
          <span>{{ u.symbol }}</span>
        </div>
        <div class="hint">改行列数：区块大小不变，小区块跟着变大变小。填小区块的宽、长：区块按“行列数 × 小区块大小”重新算大小，左上角不动。</div>
      </section>
    </template>

    <!-- 整块地：什么都没选 -->
    <template v-else>
      <section class="panel panel-pad">
        <div class="panel-title">整块地<span class="sub">{{ stats.plant }} 个区块 · {{ stats.road }} 条道路 · {{ stats.plots }} 个小区块</span></div>
        <div class="row-form pair">
          <span class="lbl">宽</span>
          <el-input-number
            :model-value="round(size.w, 3)" :min="0.01" :precision="3" :controls="false" size="small" class="w2"
            @change="(v: number | undefined) => setSize(v, undefined)"
          />
          <span class="lbl">长</span>
          <el-input-number
            :model-value="round(size.h, 3)" :min="0.01" :precision="3" :controls="false" size="small" class="w2"
            @change="(v: number | undefined) => setSize(undefined, v)"
          />
          <span>{{ u.symbol }}</span>
        </div>
        <div class="btn-row tight">
          <el-button size="small" :disabled="!loose" @click="emit('apply', fitField(layout))">收紧到正好放下</el-button>
        </div>
        <div class="hint">也可以拖地块的右边、下边。把区块挪到外面、画道路把后面的顶出去，地块会自己跟着变大。</div>
        <div class="row-form">
          <span class="lbl">道路宽</span>
          <el-input-number
            :model-value="roadWidth" :min="0.01" :max="1000" :precision="2" :step="0.5" size="small" controls-position="right" class="w"
            @change="(v: number | undefined) => v && emit('update:roadWidth', v)"
          />
          <span>{{ u.symbol }}</span>
        </div>
        <div class="hint">画道路按这个宽；拖区块时离别的区块正好这么远会自动贴住，留出一条路。</div>
        <div class="row-form">
          <span class="lbl">新区块</span>
          <el-input-number
            :model-value="layout.defaultInner?.rows ?? 1" :min="1" :max="60" size="small" controls-position="right" class="n"
            @change="(v: number | undefined) => setDefaultInner(v, layout.defaultInner?.cols ?? 1)"
          />
          <span>×</span>
          <el-input-number
            :model-value="layout.defaultInner?.cols ?? 1" :min="1" :max="60" size="small" controls-position="right" class="n"
            @change="(v: number | undefined) => setDefaultInner(layout.defaultInner?.rows ?? 1, v)"
          />
          <span>小区块</span>
        </div>
        <div v-if="stats.plant > 0" class="hint">地里已经有种植区块时，新画的区块按现有小区块的大小分行列。</div>
      </section>

      <section v-if="clashes.length" class="panel panel-pad clash">
        <div class="panel-title">叠在一起的<span class="sub">{{ clashes.length }} 处，叠着不能保存</span></div>
        <button v-for="c in clashes.slice(0, 8)" :key="c.a + c.b" type="button" class="clash-item" @click="emit('update:selectedBlocks', [c.a, c.b])">
          {{ c.text }}
        </button>
        <div class="hint">点一下选中这两个（图上描了红框），把其中一个挪开或者改小。道路和道路可以交叉。</div>
      </section>

      <section class="panel panel-pad">
        <div class="panel-title">真北方向<span class="sub">图纸上方顺时针 {{ north }}°</span></div>
        <div class="norths">
          <button v-for="p in NORTH_PRESETS" :key="p.deg" class="north-btn" :class="{ on: north === p.deg }" @click="setNorth(p.deg)">
            <i :style="{ transform: `rotate(${p.deg}deg)` }">↑</i>{{ p.label }}
          </button>
        </div>
        <div class="row-form">
          <span class="lbl">角度</span>
          <el-input-number
            :model-value="north" :min="0" :max="359.9" :precision="1" :step="5" size="small" controls-position="right" class="w"
            @change="setNorth"
          />
          <span>°</span>
        </div>
        <div class="hint">按实地填：站在地块里，图纸的上方对着哪个方向。设好后可以切换“正北朝上”，3D 里东南西北也按这个画。</div>
      </section>

      <section class="panel panel-pad">
        <div class="panel-title">长度单位</div>
        <div class="row-form">
          <el-select v-model="unitPick" size="small" class="unit" @change="changeUnit">
            <el-option v-for="p in UNIT_PRESETS" :key="p.name" :value="p.name" :label="`${p.name}（${p.symbol}）`" />
            <el-option :value="CUSTOM" label="自定义…" />
          </el-select>
        </div>
        <div v-if="unitPick === CUSTOM" class="row-form">
          <el-input v-model="customUnit.name" size="small" maxlength="10" placeholder="名称" class="unit-name" />
          <span>= </span>
          <el-input-number v-model="customUnit.meters" :min="0.0001" :max="1000" :precision="4" :controls="false" size="small" class="w2" />
          <span>米</span>
          <el-button size="small" @click="changeUnit">用这个</el-button>
        </div>
        <el-checkbox v-model="convert" size="small">换单位时换算数字（实际大小不变）</el-checkbox>
      </section>

      <section class="panel panel-pad tips">
        <div class="panel-title">怎么改</div>
        <ul>
          <li>区块、道路都是随便摆的：按住拖动挪位置，选中后拖四周的小方块改大小，Delete 删掉，Ctrl D 复制，方向键一点点挪。</li>
          <li>道路工具（右下角，R）：按住拖，从哪儿拖到哪儿路就画到哪儿；在区块的边或小区块之间的线上点一下，加一条通到头的。</li>
          <li>路画在空地上，别的都不动；压到了区块，区块在最近的小区块分界线处切开，后面的往后挪一个路宽，小区块大小不变。</li>
          <li>区块工具（B）：拖出一个方框就是一个新区块。选中一个区块点“照着排一片”，一次排出一样的几行几列。</li>
          <li>选几个（Ctrl/Shift 加选、拖动框选）：对齐、排开、合并。拖动时靠近别的边会自动贴住，按住 Ctrl 不贴。</li>
          <li>双击种植区块进去：改小区块行列、框选格子设成道路、保护区、空地，或拖到别的位置。</li>
          <li>右键有常用操作。Ctrl Z 撤销，Ctrl Y 重做，Ctrl S 保存。</li>
        </ul>
      </section>
    </template>
  </div>
</template>

<style scoped>
.edit-panel {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.panel-title .sub {
  margin-left: 8px;
}

.row-form {
  display: flex;
  align-items: center;
  gap: 5px;
  margin: 8px 0;
  font-size: var(--fs-sm);
  color: var(--ink-2);
  flex-wrap: wrap;
}

.lbl {
  width: 56px;
  flex-shrink: 0;
  color: var(--ink-2);
}

.lbl-top {
  font-size: var(--fs-sm);
  color: var(--ink-2);
  margin: 10px 0 6px;
}

.n {
  width: 64px;
}

.w {
  width: 110px;
}

.w2 {
  width: 76px;
}

.unit {
  width: 160px;
}

.unit-name {
  width: 80px;
}

.btn-row {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 10px;
}

.btn-row .el-button + .el-button {
  margin-left: 0;
}

.btn-row.tight {
  margin-top: 0;
}

.back {
  margin: 4px 0 6px;
}

.hint.warn {
  color: var(--clay);
}

.kinds {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 6px;
}

.kind {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 8px;
  border: 1px solid var(--line-strong);
  border-radius: var(--radius-sm);
  background: var(--surface);
  color: var(--ink);
  font-size: var(--fs-sm);
  cursor: pointer;
}

.kind i {
  width: 16px;
  height: 12px;
  border-radius: 3px;
  border: 1px solid rgba(0, 0, 0, 0.12);
}

.kind.plant i { background: #e7dcc2; }
.kind.road i { background: #d9cfb9; background-image: linear-gradient(90deg, transparent 45%, #f6f0e2 45%, #f6f0e2 55%, transparent 55%); }
.kind.guard i { background: repeating-linear-gradient(135deg, #d6e3c6 0 3px, #9db88f 3px 4px); }
.kind.blank i { background: #eee6d4; }

.kind:hover {
  border-color: var(--green-2);
}

.kind.on {
  border-color: var(--green);
  background: var(--green-wash);
  color: var(--forest);
  font-weight: 600;
}

.align {
  display: flex;
  gap: 4px;
  margin: 8px 0;
}

.align-btn {
  width: 28px;
  height: 26px;
  border: 1px solid var(--line-strong);
  border-radius: 6px;
  background: var(--surface);
  cursor: pointer;
  position: relative;
}

.align-btn:hover {
  border-color: var(--green-2);
}

/* 小图标：一个框里的小方块靠哪边 */
.align-btn i {
  position: absolute;
  inset: 5px;
  border: 1px dashed var(--ink-4);
  border-radius: 2px;
}

.align-btn i::after {
  content: '';
  position: absolute;
  width: 7px;
  height: 7px;
  background: var(--green);
  border-radius: 1px;
}

.align-btn.left i::after { left: 0; top: calc(50% - 3.5px); }
.align-btn.hcenter i::after { left: calc(50% - 3.5px); top: calc(50% - 3.5px); }
.align-btn.right i::after { right: 0; top: calc(50% - 3.5px); }
.align-btn.top i::after { top: 0; left: calc(50% - 3.5px); }
.align-btn.vcenter i::after { top: calc(50% - 3.5px); left: calc(50% - 3.5px); }
.align-btn.bottom i::after { bottom: 0; left: calc(50% - 3.5px); }

.norths {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 4px;
  margin: 8px 0 4px;
}

.north-btn {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
  padding: 5px 0;
  border: 1px solid var(--line-strong);
  border-radius: var(--radius-sm);
  background: var(--surface);
  font-size: 11px;
  color: var(--ink-2);
  cursor: pointer;
}

.north-btn i {
  font-style: normal;
  font-size: 14px;
  color: var(--clay);
  line-height: 1;
}

.north-btn.on {
  border-color: var(--green);
  background: var(--green-wash);
  color: var(--forest);
  font-weight: 600;
}

.tips ul {
  margin: 0;
  padding-left: 18px;
  font-size: var(--fs-sm);
  color: var(--ink-2);
  line-height: 1.8;
}

.n2 {
  width: 96px;
}

/* 一行两个数：标签窄一点，两个输入框一样宽，不折行 */
.row-form.pair {
  flex-wrap: nowrap;
}

.row-form.pair .lbl {
  width: auto;
  min-width: 14px;
  white-space: nowrap;
}

.row-form.pair .lbl + .w2 + .lbl {
  margin-left: 6px;
}

.row-form.pair .w2 {
  flex: 1 1 0;
  min-width: 0;
  width: auto;
}

.lbl.mid {
  width: auto;
  margin-left: 8px;
}

.lbl.short {
  width: 20px;
}

.dlg-text {
  margin-bottom: 12px;
  font-size: 13.5px;
  color: var(--ink-2);
  line-height: 1.7;
}

.clash-item {
  display: block;
  width: 100%;
  margin: 6px 0 0;
  padding: 6px 10px;
  border: 1px solid rgba(192, 86, 47, 0.35);
  border-radius: var(--radius-sm);
  background: rgba(192, 86, 47, 0.06);
  color: var(--clay);
  font-size: var(--fs-sm);
  text-align: left;
  cursor: pointer;
}

.clash-item:hover {
  background: rgba(192, 86, 47, 0.12);
}
</style>
