<script setup lang="ts">
// 编辑地块的属性面板：按选中的东西显示——整块地（单位、区块行列）、一行或一列区块（高、宽、插入、删除）、
// 一个或几个区块（名称、类型、合并、拆分、小区行列、特殊大小）、区块里的小区（行列、行高列宽、合并拆分）
import { computed, reactive, ref, watch } from 'vue'
import { ElMessageBox } from 'element-plus'
import {
  ArrowLeft, Bottom, CopyDocument, Delete, Grid, Plus, Right, Scissor, Top,
} from '@element-plus/icons-vue'

import {
  BLOCK_KIND, UNIT_PRESETS, alignBlockFit, anchors, blockRect, blockSlot, blockSpan, evenInnerTracks, fieldSize, fmt,
  innerSizes, insertTrack, keyOf, mergeBlocks, mergeInner, mergeAt, parseKey, removeTrack, removedPlots, renameBlock,
  resizeBlockGrid, resizeInner, round, setBlockFit, setBlockKind, setInnerTrackSize, setTrackSize, setUnit, splitBlock,
  splitInner, type Align, type BlockKind, type FieldLayout, type LayoutBlock, type Unit,
} from '@/shared/fieldLayout'

const props = defineProps<{
  layout: FieldLayout
  selectedBlocks: string[]
  selectedCells: string[]
  focus: string | null
  track: { axis: 'row' | 'col'; index: number } | null
  /** 画面现在转了多少度：设"屏幕上方为北"时用 */
  rotation?: number
  /** 小区 id → 编号，提示会去掉哪些小区时用 */
  plotCode: (id: number) => string
}>()

const emit = defineEmits<{
  apply: [layout: FieldLayout]
  'update:selectedBlocks': [ids: string[]]
  'update:selectedCells': [keys: string[]]
  'update:track': [t: { axis: 'row' | 'col'; index: number } | null]
  enter: [id: string]
  leave: []
}>()

const u = computed(() => props.layout.unit)
const blocks = computed(() => props.selectedBlocks.map((id) => props.layout.blocks.find((b) => b.id === id)).filter((b) => !!b))
const one = computed(() => (blocks.value.length === 1 ? blocks.value[0]! : null))
const focusBlock = computed(() => props.layout.blocks.find((b) => b.id === props.focus) ?? null)

/** 会去掉小区时先问一下 */
async function confirmDrop(next: FieldLayout, what: string): Promise<boolean> {
  const gone = removedPlots(props.layout, next)
  if (gone.length === 0) return true
  const codes = gone.slice(0, 8).map(props.plotCode).join('、')
  try {
    await ElMessageBox.confirm(
      `${what}会去掉 ${gone.length} 个已有的小区（${codes}${gone.length > 8 ? ' 等' : ''}）。保存时，已经分配了品种或录了数据的小区不能去掉。`,
      '会去掉小区', { type: 'warning', confirmButtonText: '继续', cancelButtonText: '取消' },
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
  emit('apply', setUnit(props.layout, next, convert.value))
}

const size = computed(() => fieldSize(props.layout))

async function setGrid(rows: number | undefined, cols: number | undefined) {
  if (!rows || !cols) return
  await commit(resizeBlockGrid(props.layout, rows, cols), '减少区块行列')
}

function setDefaultInner(rows: number | undefined, cols: number | undefined) {
  if (!rows || !cols) return
  emit('apply', { ...props.layout, defaultInner: { rows, cols } })
}

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

/** 现在屏幕上方是北：图纸上方顺时针转 −rotation 度是北 */
function screenUpIsNorth() {
  setNorth(-(props.rotation ?? 0))
}

// ---------- 一行（列）区块 ----------

const trackSize = computed(() => {
  const t = props.track
  if (!t || props.focus) return null
  return (t.axis === 'row' ? props.layout.rows : props.layout.cols)[t.index] ?? null
})

const trackName = computed(() => {
  const t = props.track
  if (!t) return ''
  return t.axis === 'row' ? `第 ${t.index + 1} 行` : `第 ${t.index + 1} 列`
})

function setTrack(v: number | undefined) {
  if (!props.track || !v || v <= 0) return
  emit('apply', setTrackSize(props.layout, props.track.axis, props.track.index, v))
}

function insertAt(before: boolean) {
  const t = props.track
  if (!t) return
  const index = before ? t.index : t.index + 1
  emit('apply', insertTrack(props.layout, t.axis, index))
  emit('update:track', { axis: t.axis, index })
}

async function removeCurrentTrack() {
  const t = props.track
  if (!t) return
  const next = removeTrack(props.layout, t.axis, t.index)
  if (next === props.layout) return
  if (await confirmDrop(next, `删除${trackName.value}区块`)) {
    emit('apply', next)
    emit('update:track', null)
    emit('update:selectedBlocks', [])
  }
}

// ---------- 区块 ----------

const mergeable = computed(() => {
  if (blocks.value.length < 2) return null
  const rs = blocks.value.map((b) => blockSpan(props.layout, b))
  const a = { r: Math.min(...rs.map((s) => s.r)), c: Math.min(...rs.map((s) => s.c)) }
  const b = { r: Math.max(...rs.map((s) => s.r + s.rs - 1)), c: Math.max(...rs.map((s) => s.c + s.cs - 1)) }
  return { a, b, result: mergeBlocks(props.layout, a, b) }
})

async function merge() {
  const m = mergeable.value
  if (!m) return
  if (await confirmDrop(m.result.layout, '合并这几个区块')) {
    emit('apply', m.result.layout)
    const anchor = m.result.layout.blocks.find((b) => b.r === m.a.r && b.c === m.a.c)
    emit('update:selectedBlocks', anchor ? [anchor.id] : [])
  }
}

const isMerged = computed(() => {
  const b = one.value
  if (!b) return false
  const s = blockSpan(props.layout, b)
  return s.rs * s.cs > 1
})

function split() {
  if (one.value) emit('apply', splitBlock(props.layout, one.value.id))
}

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

/** 选中区块所在的行高、列宽（没合并时可以直接改） */
const oneTracks = computed(() => {
  const b = one.value
  if (!b) return null
  const s = blockSpan(props.layout, b)
  return { span: s, w: s.cs === 1 ? props.layout.cols[s.c]! : null, h: s.rs === 1 ? props.layout.rows[s.r]! : null }
})

function setOneTrack(axis: 'row' | 'col', v: number | undefined) {
  const t = oneTracks.value
  if (!t || !v || v <= 0) return
  emit('apply', setTrackSize(props.layout, axis, axis === 'row' ? t.span.r : t.span.c, v))
}

const innerCountOfSelection = computed(() => {
  const withInner = blocks.value.filter((b) => b.inner)
  if (withInner.length === 0) return null
  const r = new Set(withInner.map((b) => b.inner!.rows.length))
  const c = new Set(withInner.map((b) => b.inner!.cols.length))
  return { rows: r.size === 1 ? [...r][0]! : undefined, cols: c.size === 1 ? [...c][0]! : undefined, n: withInner.length }
})

async function setInnerCounts(rows: number | undefined, cols: number | undefined) {
  let next = props.layout
  for (const b of blocks.value) {
    if (!b.inner) continue
    next = resizeInner(next, b.id, rows ?? b.inner.rows.length, cols ?? b.inner.cols.length)
  }
  await commit(next, '减少小区行列')
}

const plotsIn = (b: LayoutBlock) => (b.inner ? anchors(b.inner.rows.length, b.inner.cols.length, b.inner.merges).length : 0)

// ---------- 特殊区块 ----------

const fitInfo = computed(() => {
  const b = one.value
  if (!b) return null
  return { slot: blockSlot(props.layout, b), rect: blockRect(props.layout, b), special: !!b.fit }
})

function setFitSize(w: number | undefined, h: number | undefined) {
  const b = one.value
  const f = fitInfo.value
  if (!b || !f) return
  emit('apply', setBlockFit(props.layout, b.id, w ?? f.rect.w, h ?? f.rect.h))
}

function makeSpecial() {
  const b = one.value
  const f = fitInfo.value
  if (!b || !f) return
  // 默认四边各留出 10% 当道路
  emit('apply', setBlockFit(props.layout, b.id, round(f.slot.w * 0.8, 3), round(f.slot.h * 0.8, 3)))
}

function resetFit() {
  const b = one.value
  const f = fitInfo.value
  if (b && f) emit('apply', setBlockFit(props.layout, b.id, f.slot.w, f.slot.h))
}

function align(a: Align) {
  if (one.value) emit('apply', alignBlockFit(props.layout, one.value.id, a))
}

function setFitPos(x: number | undefined, y: number | undefined) {
  const b = one.value
  if (!b?.fit) return
  emit('apply', setBlockFit(props.layout, b.id, b.fit.w, b.fit.h, { x: x ?? b.fit.x, y: y ?? b.fit.y }))
}

// ---------- 区块里的小区 ----------

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
  await commit(resizeInner(props.layout, b.id, rows, cols), '减少小区行列')
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
  if (await confirmDrop(m.result.layout, '合并这几个小区')) {
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

const KINDS: BlockKind[] = ['PLANT', 'ROAD', 'GUARD', 'BLANK']
</script>

<template>
  <div class="edit-panel">
    <!-- 区块里 -->
    <template v-if="focusBlock">
      <section class="panel panel-pad">
        <div class="panel-title">
          {{ focusBlock.name }}<span class="sub">区块里的小区</span>
        </div>
        <el-button size="small" :icon="ArrowLeft" class="back" @click="emit('leave')">回到整块地</el-button>
        <div class="row-form">
          <span class="lbl">小区</span>
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
        <div class="hint">区块 {{ fmt(blockRect(layout, focusBlock).w, u) }} × {{ fmt(blockRect(layout, focusBlock).h, u) }}，小区按比例分。</div>
        <div class="btn-row">
          <el-button size="small" @click="evenTracks('row')">各行一样高</el-button>
          <el-button size="small" @click="evenTracks('col')">各列一样宽</el-button>
        </div>
      </section>

      <section v-if="track && innerTrackSize != null" class="panel panel-pad">
        <div class="panel-title">{{ trackName }}小区</div>
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
          选中的小区<span class="sub">{{ selectedCells.length }} 个</span>
        </div>
        <div v-if="selectedCells.length === 0" class="hint">
          点小区选中；拖动框选几个；点上面、左边的行号列号选整行整列、改行高列宽。
        </div>
        <div class="btn-row">
          <el-button v-if="cellMerge" type="primary" size="small" :icon="CopyDocument" @click="mergeCells">
            合并成一个小区
          </el-button>
          <el-button v-if="splittableCell" size="small" :icon="Scissor" @click="splitCell">拆开</el-button>
        </div>
        <div v-if="cellMerge && cellMerge.result.dropped.length" class="hint warn">
          合并后留下左上角的小区，其余 {{ cellMerge.result.dropped.length }} 个小区去掉。
        </div>
      </section>
    </template>

    <!-- 整块地：选了一行或一列区块 -->
    <template v-else-if="track && trackSize != null">
      <section class="panel panel-pad">
        <div class="panel-title">{{ trackName }}区块</div>
        <div class="row-form">
          <span class="lbl">{{ track.axis === 'row' ? '高' : '宽' }}</span>
          <el-input-number
            :model-value="round(trackSize, 3)" :min="0.001" :precision="3" :controls="false" size="small" class="w"
            @change="setTrack"
          />
          <span>{{ u.symbol }}</span>
        </div>
        <div class="btn-row">
          <el-button size="small" :icon="track.axis === 'row' ? Top : ArrowLeft" @click="insertAt(true)">
            {{ track.axis === 'row' ? '上面' : '左边' }}插一{{ track.axis === 'row' ? '行' : '列' }}
          </el-button>
          <el-button size="small" :icon="track.axis === 'row' ? Bottom : Right" @click="insertAt(false)">
            {{ track.axis === 'row' ? '下面' : '右边' }}插一{{ track.axis === 'row' ? '行' : '列' }}
          </el-button>
          <el-button
            size="small" type="danger" plain :icon="Delete"
            :disabled="(track.axis === 'row' ? layout.rows.length : layout.cols.length) <= 1"
            @click="removeCurrentTrack"
          >
            删除
          </el-button>
        </div>
        <div class="hint">插入的是普通种植区块，小区按“新区块默认”的行列分。</div>
      </section>
    </template>

    <!-- 整块地：选了区块 -->
    <template v-else-if="blocks.length > 0">
      <section class="panel panel-pad">
        <div class="panel-title">
          {{ one ? one.name : `选了 ${blocks.length} 个区块` }}
          <span v-if="one" class="sub">第 {{ blockSpan(layout, one).r + 1 }} 行第 {{ blockSpan(layout, one).c + 1 }} 列</span>
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
          <el-button v-if="mergeable" type="primary" size="small" :icon="CopyDocument" @click="merge">合并</el-button>
          <el-button v-if="isMerged" size="small" :icon="Scissor" @click="split">拆开</el-button>
          <el-button v-if="one?.kind === 'PLANT'" size="small" :icon="Grid" @click="emit('enter', one.id)">进入区块改小区</el-button>
        </div>
        <div v-if="mergeable" class="hint" :class="{ warn: !mergeable.result.joined }">
          {{ mergeable.result.joined
            ? '合并后小区都保留，拼成一个大区块。'
            : `小区行列对不上，合并后只留左上角区块的小区，其它 ${mergeable.result.dropped.length} 个已有小区去掉。` }}
        </div>
      </section>

      <section v-if="one && oneTracks" class="panel panel-pad">
        <div class="panel-title">大小<span class="sub">{{ u.name }}</span></div>
        <div class="row-form">
          <span class="lbl">这一列宽</span>
          <el-input-number
            v-if="oneTracks.w != null" :model-value="round(oneTracks.w, 3)" :min="0.001" :precision="3" :controls="false"
            size="small" class="w" @change="(v: number | undefined) => setOneTrack('col', v)"
          />
          <span v-else class="muted">合并了 {{ oneTracks.span.cs }} 列，点上面的标尺分别改</span>
        </div>
        <div class="row-form">
          <span class="lbl">这一行高</span>
          <el-input-number
            v-if="oneTracks.h != null" :model-value="round(oneTracks.h, 3)" :min="0.001" :precision="3" :controls="false"
            size="small" class="w" @change="(v: number | undefined) => setOneTrack('row', v)"
          />
          <span v-else class="muted">合并了 {{ oneTracks.span.rs }} 行，点左边的标尺分别改</span>
        </div>
        <div class="hint">改的是整列（整行）区块，同一列（行）的区块一起变。</div>
      </section>

      <section v-if="one && fitInfo" class="panel panel-pad special">
        <div class="panel-title">特殊区块<span class="sub">{{ fitInfo.special ? '已设' : '没设' }}</span></div>
        <div class="hint">
          把这个区块设得比原来的位置小，剩下的地方变成道路；区块可以在原来的位置里拖动。
          原来 {{ fmt(fitInfo.slot.w, u) }} × {{ fmt(fitInfo.slot.h, u) }}。
        </div>
        <template v-if="fitInfo.special && one.fit">
          <div class="row-form">
            <span class="lbl">宽 × 高</span>
            <el-input-number
              :model-value="round(one.fit.w, 3)" :min="0.001" :max="fitInfo.slot.w" :precision="3" :controls="false" size="small" class="w2"
              @change="(v: number | undefined) => setFitSize(v, undefined)"
            />
            <span>×</span>
            <el-input-number
              :model-value="round(one.fit.h, 3)" :min="0.001" :max="fitInfo.slot.h" :precision="3" :controls="false" size="small" class="w2"
              @change="(v: number | undefined) => setFitSize(undefined, v)"
            />
          </div>
          <div class="row-form">
            <span class="lbl">离左、上</span>
            <el-input-number
              :model-value="round(one.fit.x, 3)" :min="0" :max="fitInfo.slot.w - one.fit.w" :precision="3" :controls="false" size="small" class="w2"
              @change="(v: number | undefined) => setFitPos(v, undefined)"
            />
            <span>,</span>
            <el-input-number
              :model-value="round(one.fit.y, 3)" :min="0" :max="fitInfo.slot.h - one.fit.h" :precision="3" :controls="false" size="small" class="w2"
              @change="(v: number | undefined) => setFitPos(undefined, v)"
            />
          </div>
          <div class="align">
            <el-tooltip v-for="a in ([['left', '靠左'], ['hcenter', '左右居中'], ['right', '靠右'], ['top', '靠上'], ['vcenter', '上下居中'], ['bottom', '靠下']] as [Align, string][])" :key="a[0]" :content="a[1]" placement="top">
              <button class="align-btn" :class="a[0]" :aria-label="a[1]" @click="align(a[0])"><i /></button>
            </el-tooltip>
          </div>
          <div class="hint">选中后直接在图上拖动；四周道路的宽度跟着变。</div>
          <el-button size="small" @click="resetFit">恢复填满</el-button>
        </template>
        <el-button v-else size="small" :icon="Plus" @click="makeSpecial">设成特殊区块</el-button>
      </section>

      <section v-if="innerCountOfSelection" class="panel panel-pad">
        <div class="panel-title">
          区块里的小区<span class="sub">{{ innerCountOfSelection.n > 1 ? `${innerCountOfSelection.n} 个区块一起改` : `${plotsIn(one!)} 个` }}</span>
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
      </section>
    </template>

    <!-- 整块地：什么都没选 -->
    <template v-else>
      <section class="panel panel-pad">
        <div class="panel-title">整块地<span class="sub">{{ fmt(size.w, u) }} × {{ fmt(size.h, u) }}</span></div>
        <div class="row-form">
          <span class="lbl">区块</span>
          <el-input-number
            :model-value="layout.rows.length" :min="1" :max="50" size="small" controls-position="right" class="n"
            @change="(v: number | undefined) => setGrid(v, layout.cols.length)"
          />
          <span>行 ×</span>
          <el-input-number
            :model-value="layout.cols.length" :min="1" :max="50" size="small" controls-position="right" class="n"
            @change="(v: number | undefined) => setGrid(layout.rows.length, v)"
          />
          <span>列</span>
        </div>
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
          <span>个小区</span>
        </div>
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
        <el-button size="small" @click="screenUpIsNorth">以现在屏幕上方为北</el-button>
        <div class="hint">先在自由视角里把画面转到和实地一样（屏幕上方对着北），再点这个。</div>
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
          <li>点区块选中，Ctrl/Shift 加选，拖动框选几个，再“合并”。</li>
          <li>点上面、左边标尺上的尺寸，改一整列宽、一整行高，或插入、删除。</li>
          <li>区块类型可以改成道路、保护区、空地。</li>
          <li>双击区块进去改里面的小区。</li>
          <li>Ctrl + Z 撤销，Ctrl + Y 重做。改完点“保存布局”。</li>
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
</style>
