<script setup lang="ts">
// 给选中的小区块录入数据。左边选项目（能搜，最近录过的在最上面，按分类分组，★ 是重要项目），
// 右边填值（标准、单位、常见范围、调查日期、备注都在一张卡片里）。一次可以给很多个小区块录同一个值
import { computed, nextTick, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Plus, Search } from '@element-plus/icons-vue'

import { observationApi, type LatestTraitValue, type Plot, type TraitCategory, type TraitValue, type TraitView } from '@/shared/api'
import { TRAIT_CATEGORY } from '@/shared/labels'
import { emptyValue, missingValue, newUuid, normalizeValue, observedOnFor, today } from '@/shared/traitValue'
import { useAuthStore } from '@/stores/auth'
import { useCatalogStore } from '@/stores/catalog'
import TraitValueInput from './TraitValueInput.vue'
import CustomTraitDialog from './CustomTraitDialog.vue'

const props = defineProps<{
  trialId: number
  plots: Plot[]
  /** 打开时默认选中的项目 */
  initialTraitId?: number | null
  /** 育种的地块：选中的正好是整个区块（种的同一个品系）时，可以按整块录一个值 */
  wholeBlock?: { name: string } | null
  /** 小区块上种的品种（品系）名称，标题里显示 */
  nameOf?: (plotId: number) => string
}>()
const visible = defineModel<boolean>({ required: true })
const emit = defineEmits<{ saved: [] }>()

const auth = useAuthStore()
const catalog = useCatalogStore()

const traitId = ref<number | null>(null)
const value = ref<TraitValue>(emptyValue())
const observedOn = ref(today())
const note = ref('')
const search = ref('')
const saving = ref(false)
const customOpen = ref(false)
const listEl = ref<HTMLElement | null>(null)
// 同一次提交重试时用同一组编号，服务器据此去掉重复
let uuids = new Map<number, string>()

const U = computed(() => '小区块')
const trait = computed<TraitView | null>(() => catalog.traitById.get(traitId.value ?? -1) ?? null)
const plotCodes = computed(() => props.plots.map((p) => p.code || `${p.rowNo}-${p.colNo}`))

/** 选中的小区块种的是什么：周麦16 × 3、25-001 × 2 */
const lineSummary = computed(() => {
  if (!props.nameOf) return ''
  const counts = new Map<string, number>()
  for (const p of props.plots) {
    const n = props.nameOf(p.id)
    if (n && n !== '—') counts.set(n, (counts.get(n) ?? 0) + 1)
  }
  const list = [...counts.entries()].sort((a, b) => b[1] - a[1])
  const text = list.slice(0, 3).map(([n, c]) => (c > 1 ? `${n} × ${c}` : n)).join('、')
  return list.length > 3 ? `${text} 等 ${list.length} 个` : text
})

// ---------- 左边：项目列表 ----------

const RECENT_KEY = 'tudijiance.recentTraits'

function loadRecent(): number[] {
  try {
    const v = JSON.parse(localStorage.getItem(RECENT_KEY) || '[]') as unknown
    return Array.isArray(v) ? v.filter((x): x is number => typeof x === 'number') : []
  } catch {
    return []
  }
}

const recentIds = ref<number[]>(loadRecent())

function remember(id: number) {
  recentIds.value = [id, ...recentIds.value.filter((x) => x !== id)].slice(0, 6)
  try {
    localStorage.setItem(RECENT_KEY, JSON.stringify(recentIds.value))
  } catch {
    // 不记忆
  }
}

const matches = (t: TraitView, q: string) =>
  !q || t.trait.name.includes(q) || t.trait.code.toLowerCase().includes(q.toLowerCase())

interface Group {
  key: string
  label: string
  items: TraitView[]
}

const groups = computed<Group[]>(() => {
  const q = search.value.trim()
  const out: Group[] = []
  const entry = new Set([...catalog.entryTraits.values()].flat().map((t) => t.trait.id))
  if (!q) {
    const recent = recentIds.value.map((id) => catalog.traitById.get(id)).filter((t): t is TraitView => !!t && entry.has(t.trait.id))
    if (recent.length) out.push({ key: 'recent', label: '最近录过', items: recent })
  }
  for (const [cat, items] of catalog.entryTraits) {
    const list = items.filter((t) => matches(t, q))
    if (list.length) out.push({ key: cat, label: TRAIT_CATEGORY[cat as TraitCategory], items: list })
  }
  return out
})

/** 按列表顺序排好的项目（不重复），保存后接着录下一项、上下键用 */
const ordered = computed(() => {
  const seen = new Set<number>()
  const out: TraitView[] = []
  for (const g of groups.value) {
    if (g.key === 'recent') continue
    for (const t of g.items) {
      if (!seen.has(t.trait.id)) {
        seen.add(t.trait.id)
        out.push(t)
      }
    }
  }
  return out
})

function pick(id: number) {
  traitId.value = id
}

function onSearchKey(e: KeyboardEvent) {
  if (e.key === 'Enter') {
    e.preventDefault()
    const first = ordered.value[0]
    if (first) pick(first.trait.id)
  } else if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
    e.preventDefault()
    step(e.key === 'ArrowDown' ? 1 : -1)
  }
}

function step(d: number) {
  const list = ordered.value
  if (!list.length) return
  const i = list.findIndex((t) => t.trait.id === traitId.value)
  const next = list[Math.min(list.length - 1, Math.max(0, i + d))]
  if (next) pick(next.trait.id)
}

watch(traitId, async () => {
  resetValue()
  await nextTick()
  listEl.value?.querySelector('.item.on')?.scrollIntoView({ block: 'nearest' })
})

// ---------- 以前录过的 ----------

/** 只选了一个小区块时，它每个项目最近一次的值 */
const latest = ref(new Map<number, LatestTraitValue>())
/** 选了几个小区块时，当前项目已经有值的有几个（数值、分级项目） */
const recorded = ref<{ count: number; mean: number | null } | null>(null)

async function loadLatest() {
  latest.value = new Map()
  if (props.plots.length !== 1) return
  try {
    const d = await observationApi.plotDetail(props.trialId, props.plots[0]!.id)
    latest.value = new Map(d.latest.filter((v) => !v.computed).map((v) => [v.traitId, v]))
  } catch {
    // 看不到以前的值不影响录入
  }
}

async function loadRecorded() {
  recorded.value = null
  const t = trait.value
  if (!t || props.plots.length < 2 || (t.trait.dataType !== 'NUMBER' && t.trait.dataType !== 'GRADE')) return
  const id = t.trait.id
  try {
    const list = await observationApi.traitValues(props.trialId, id)
    if (id !== traitId.value) return
    const ids = new Set(props.plots.map((p) => p.id))
    const vals = list.filter((v) => ids.has(v.plotId)).map((v) => v.value)
    recorded.value = { count: vals.length, mean: vals.length ? Math.round((vals.reduce((a, b) => a + b, 0) / vals.length) * 100) / 100 : null }
  } catch {
    recorded.value = null
  }
}

watch(traitId, loadRecorded)

const before = computed(() => {
  const t = trait.value
  if (!t) return ''
  if (props.plots.length === 1) {
    const v = latest.value.get(t.trait.id)
    return v ? `上次录的：${v.displayValue}${v.observedOn ? `（${v.observedOn}）` : ''}` : '这个项目还没录过'
  }
  const r = recorded.value
  if (!r) return ''
  if (r.count === 0) return `选中的 ${props.plots.length} 个${U.value}都还没录过`
  const unit = t.trait.unit && t.trait.unit !== '级' ? ` ${t.trait.unit}` : ''
  return `已经录过 ${r.count}/${props.plots.length} 个，平均 ${r.mean}${unit}；再录会记一条新的`
})

// ---------- 整块录一个总数 ----------

/** 整块收的总数要按面积分到各小区块的项目（产量、株数这类越大块越多的） */
const SPLIT_CODES = new Set(['plot_yield', 'off_type_count'])
const splitWhole = ref(true)
const canSplit = computed(() => !!props.wholeBlock && props.plots.length > 1 && !!trait.value
  && SPLIT_CODES.has(trait.value.trait.code) && props.plots.every((p) => (p.lengthM ?? 0) > 0 && (p.widthM ?? 0) > 0))

/** 按面积分：各小区块的值，四舍五入后差的补到最后一块，合计等于整块的数 */
function splitByArea(total: number, digits: number): number[] {
  const areas = props.plots.map((p) => (p.lengthM ?? 0) * (p.widthM ?? 0))
  const sum = areas.reduce((a, b) => a + b, 0)
  const k = 10 ** digits
  const out = areas.map((a) => Math.round((total * a) / sum * k) / k)
  const diff = Math.round((total - out.reduce((a, b) => a + b, 0)) * k) / k
  out[out.length - 1] = Math.round((out[out.length - 1]! + diff) * k) / k
  return out
}

// ---------- 调查日期 ----------

function daysAgo(n: number): string {
  const d = new Date()
  d.setDate(d.getDate() - n)
  const pad = (x: number) => String(x).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

const DAY_PRESETS = [{ n: 0, label: '今天' }, { n: 1, label: '昨天' }, { n: 2, label: '前天' }]

// ---------- 打开、保存 ----------

watch(visible, async (open) => {
  if (!open) {
    return
  }
  search.value = ''
  observedOn.value = today()
  recentIds.value = loadRecent()
  try {
    await catalog.loadTraits()
  } catch (e) {
    ElMessage.error((e as Error).message)
    return
  }
  const initial = props.initialTraitId ? catalog.traitById.get(props.initialTraitId) : null
  const first = groups.value[0]?.items[0] ?? null
  traitId.value = initial?.trait.id ?? (ordered.value.some((t) => t.trait.id === traitId.value) ? traitId.value : first?.trait.id ?? null)
  resetValue()
  loadLatest()
  loadRecorded()
})

// 改了任何内容就当作新的一次录入
watch([value, observedOn, note, () => props.plots], () => {
  uuids = new Map()
}, { deep: true })

function resetValue() {
  value.value = emptyValue()
  note.value = ''
}

async function save(keepOpen: boolean) {
  const t = trait.value
  if (!t) {
    ElMessage.warning('请选择录入项目')
    return
  }
  const missing = missingValue(t, value.value)
  if (missing) {
    ElMessage.warning(missing)
    return
  }
  if (props.plots.length === 0) {
    ElMessage.warning(`没有选中${U.value}`)
    return
  }
  const v = normalizeValue(t.trait, value.value)
  for (const p of props.plots) {
    if (!uuids.has(p.id)) {
      uuids.set(p.id, newUuid())
    }
  }
  saving.value = true
  try {
    if (canSplit.value && splitWhole.value && v.valueNum != null) {
      const parts = splitByArea(v.valueNum, t.trait.precisionDigits ?? 2)
      const unit = t.trait.unit ?? ''
      const extra = `整块（${props.wholeBlock!.name}）${v.valueNum}${unit}，按面积分到 ${props.plots.length} 个小区块`
      for (const [i, p] of props.plots.entries()) {
        await observationApi.create({
          trialId: props.trialId,
          traitId: t.trait.id,
          items: [{ plotId: p.id, clientUuid: uuids.get(p.id)! }],
          observedOn: observedOnFor(t.trait, v, observedOn.value),
          note: [note.value.trim(), extra].filter(Boolean).join('；').slice(0, 200),
          ...v,
          valueNum: parts[i]!,
        })
      }
      ElMessage.success(`已按面积把整块的"${t.trait.name}"分到 ${props.plots.length} 个小区块`)
    } else {
      const created = await observationApi.create({
        trialId: props.trialId,
        traitId: t.trait.id,
        items: props.plots.map((p) => ({ plotId: p.id, clientUuid: uuids.get(p.id)! })),
        observedOn: observedOnFor(t.trait, v, observedOn.value),
        note: note.value.trim() || null,
        ...v,
      })
      ElMessage.success(`已录入 ${created.length} 个${U.value}的"${t.trait.name}"`)
    }
    uuids = new Map()
    remember(t.trait.id)
    emit('saved')
    if (keepOpen) {
      if (props.plots.length === 1) loadLatest()
      // 搜着录的：清掉搜索，接着整个列表的下一项
      search.value = ''
      nextTrait()
    } else {
      visible.value = false
    }
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    saving.value = false
  }
}

/** 保存后跳到列表里的下一个项目，方便按顺序连续录 */
function nextTrait() {
  const list = ordered.value
  const i = list.findIndex((t) => t.trait.id === traitId.value)
  if (i >= 0 && i < list.length - 1) {
    traitId.value = list[i + 1]!.trait.id
  } else {
    resetValue()
  }
}

function onKey(e: KeyboardEvent) {
  if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) {
    e.preventDefault()
    save(!e.shiftKey)
  }
}

async function onCustomCreated(created: TraitView) {
  await catalog.loadTraits(true)
  traitId.value = created.trait.id
}

const unitText = computed(() => (trait.value?.trait.unit && trait.value.trait.unit !== '级' ? trait.value.trait.unit : ''))
</script>

<template>
  <el-dialog v-model="visible" width="900px" top="5vh" :close-on-click-modal="false" class="entry-dialog" @keydown="onKey">
    <template #header>
      <div class="head">
        <span class="head-title">录入数据</span>
        <span class="head-target">
          给 <b>{{ plots.length }}</b> 个{{ U }}<template v-if="wholeBlock">（整个{{ wholeBlock.name }}）</template>
          <span class="codes">
            <span v-for="c in plotCodes.slice(0, 8)" :key="c" class="code">{{ c }}</span>
            <span v-if="plotCodes.length > 8" class="more">等</span>
          </span>
          <span v-if="lineSummary" class="lines">· {{ lineSummary }}</span>
        </span>
      </div>
    </template>

    <div class="body">
      <!-- 左：选项目 -->
      <aside class="picker">
        <el-input
          v-model="search" :prefix-icon="Search" clearable placeholder="搜项目：株高、千粒重…"
          class="search" @keydown="onSearchKey"
        />
        <div ref="listEl" class="list">
          <template v-for="g in groups" :key="g.key">
            <div class="group" :class="{ recent: g.key === 'recent' }">{{ g.label }}</div>
            <button
              v-for="t in g.items"
              :key="`${g.key}-${t.trait.id}`"
              type="button"
              class="item"
              :class="{ on: t.trait.id === traitId }"
              @click="pick(t.trait.id)"
            >
              <span class="star" :class="{ off: !t.trait.important }">★</span>
              <span class="name">{{ t.trait.name }}</span>
              <span v-if="plots.length === 1 && latest.has(t.trait.id)" class="done" title="这个小区块已经录过">✓</span>
              <span v-else-if="t.trait.unit && t.trait.unit !== '级'" class="u">{{ t.trait.unit }}</span>
            </button>
          </template>
          <div v-if="groups.length === 0" class="empty">没找到这个项目</div>
        </div>
        <el-button v-if="auth.isTeacher" text type="primary" size="small" :icon="Plus" class="custom" @click="customOpen = true">
          新增自定义项目
        </el-button>
      </aside>

      <!-- 右：填值 -->
      <section v-if="trait" class="card">
        <div class="card-head">
          <div>
            <div class="cat">{{ TRAIT_CATEGORY[trait.trait.category] }}<span v-if="trait.trait.important" class="imp">★ 重要项目</span></div>
            <h3>{{ trait.trait.name }}<span v-if="unitText" class="unit">{{ unitText }}</span></h3>
          </div>
          <div v-if="before" class="before">{{ before }}</div>
        </div>

        <div v-if="trait.trait.surveyTime || trait.trait.stdClause || trait.trait.description" class="std">
          <div v-if="trait.trait.surveyTime"><b>{{ trait.trait.isStandard ? '什么时候调查' : '说明' }}</b>{{ trait.trait.surveyTime }}</div>
          <div v-if="trait.trait.description"><b>怎么记</b>{{ trait.trait.description }}</div>
          <div v-if="trait.trait.stdClause" class="clause">标准条款 {{ trait.trait.stdClause }}</div>
        </div>

        <div class="value">
          <TraitValueInput v-model="value" :trait="trait" />
        </div>

        <div v-if="canSplit" class="whole">
          <el-checkbox v-model="splitWhole">这是整个{{ wholeBlock!.name }}一起收的总数</el-checkbox>
          <span class="hint">
            {{ splitWhole
              ? `按面积分到 ${plots.length} 个小区块，合计等于这个数，折亩产不变`
              : `不勾：每个小区块都记这个数` }}
          </span>
        </div>

        <div class="meta">
          <div v-if="trait.trait.dataType !== 'DATE'" class="meta-row">
            <span class="lbl">调查日期</span>
            <div class="days">
              <button
                v-for="d in DAY_PRESETS" :key="d.n" type="button" class="day" :class="{ on: observedOn === daysAgo(d.n) }"
                @click="observedOn = daysAgo(d.n)"
              >
                {{ d.label }}
              </button>
            </div>
            <el-date-picker
              v-model="observedOn" type="date" value-format="YYYY-MM-DD" :clearable="false" size="default" class="date"
              :disabled-date="(d: Date) => d.getTime() > Date.now()"
            />
          </div>
          <div class="meta-row">
            <span class="lbl">备注</span>
            <el-input v-model="note" maxlength="200" placeholder="可不填，例如：东头几行倒伏" />
          </div>
        </div>
      </section>
      <section v-else class="card empty-card">
        <div class="hint">左边选一个项目</div>
      </section>
    </div>

    <template #footer>
      <div class="foot">
        <span class="keys"><kbd>Ctrl</kbd> + <kbd>Enter</kbd> 保存并录下一项</span>
        <el-button @click="visible = false">关闭</el-button>
        <el-button :loading="saving" :disabled="!trait" @click="save(true)">保存，接着录下一项</el-button>
        <el-button type="primary" :loading="saving" :disabled="!trait" @click="save(false)">保存</el-button>
      </div>
    </template>

    <CustomTraitDialog v-model="customOpen" @created="onCustomCreated" />
  </el-dialog>
</template>

<style scoped>
.head {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.head-title {
  font-family: var(--font-serif);
  font-size: var(--fs-lg);
  font-weight: 700;
  color: var(--forest);
}

.head-target {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  font-size: var(--fs-sm);
  color: var(--ink-2);
}

.head-target b {
  color: var(--forest);
}

.codes {
  display: inline-flex;
  flex-wrap: wrap;
  gap: 4px;
}

.code {
  padding: 0 6px;
  border-radius: 4px;
  background: var(--surface-3);
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--ink-2);
}

.more,
.lines {
  color: var(--ink-3);
}

.body {
  display: grid;
  grid-template-columns: 250px minmax(0, 1fr);
  gap: 16px;
  min-height: 440px;
}

/* ---------- 左：项目 ---------- */

.picker {
  display: flex;
  flex-direction: column;
  min-height: 0;
  border-right: 1px solid var(--line);
  padding-right: 12px;
}

.search {
  margin-bottom: 8px;
}

.list {
  flex: 1;
  max-height: 460px;
  overflow-y: auto;
  margin: 0 -4px;
  padding: 0 4px;
}

.group {
  position: sticky;
  top: 0;
  z-index: 1;
  padding: 8px 6px 4px;
  background: var(--surface);
  font-size: 11.5px;
  font-weight: 700;
  letter-spacing: 0.04em;
  color: var(--ink-3);
}

.group.recent {
  color: var(--wheat-ink);
}

.item {
  display: flex;
  align-items: center;
  gap: 6px;
  width: 100%;
  padding: 6px 8px;
  border: none;
  border-radius: 7px;
  background: none;
  font: inherit;
  font-size: var(--fs-sm);
  color: var(--ink);
  text-align: left;
  cursor: pointer;
}

.item:hover {
  background: var(--surface-3);
}

.item.on {
  background: var(--forest);
  color: #fbf3df;
}

.star {
  width: 12px;
  font-size: 11px;
  color: var(--wheat);
}

.star.off {
  visibility: hidden;
}

.name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.u {
  font-size: 11px;
  color: var(--ink-3);
}

.done {
  font-size: 12px;
  font-weight: 700;
  color: var(--green);
}

.item.on .u,
.item.on .done {
  color: rgba(251, 243, 223, 0.75);
}

.empty {
  padding: 20px 8px;
  color: var(--ink-3);
  font-size: var(--fs-sm);
}

.custom {
  align-self: flex-start;
  margin-top: 6px;
}

/* ---------- 右：填值 ---------- */

.card {
  display: flex;
  flex-direction: column;
  gap: 14px;
  min-width: 0;
}

.empty-card {
  align-items: center;
  justify-content: center;
}

.card-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-end;
  gap: 12px;
  flex-wrap: wrap;
}

.cat {
  font-size: var(--fs-xs);
  color: var(--ink-3);
  display: flex;
  gap: 8px;
}

.imp {
  color: var(--wheat-ink);
  font-weight: 600;
}

.card-head h3 {
  margin: 2px 0 0;
  font-family: var(--font-serif);
  font-size: 24px;
  color: var(--forest);
  display: flex;
  align-items: baseline;
  gap: 8px;
}

.card-head .unit {
  padding: 1px 8px;
  border-radius: 999px;
  background: var(--green-wash);
  font-family: var(--font-sans, inherit);
  font-size: 13px;
  font-weight: 600;
  color: var(--green);
}

.before {
  padding: 4px 10px;
  border-radius: 8px;
  background: var(--surface-2);
  font-size: 12.5px;
  color: var(--ink-2);
}

.std {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 10px 12px;
  border-left: 3px solid var(--wheat);
  border-radius: 0 8px 8px 0;
  background: var(--wheat-wash);
  font-size: 12.5px;
  line-height: 1.6;
  color: var(--ink-2);
}

.std b {
  margin-right: 8px;
  color: var(--wheat-ink);
}

.clause {
  font-size: 11.5px;
  color: var(--ink-3);
}

.value {
  padding: 18px 16px;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: linear-gradient(180deg, #fffefa, var(--surface-2));
}

.value :deep(.number) {
  width: 220px;
}

.value :deep(.number .el-input__inner) {
  font-size: 20px;
  font-weight: 600;
  height: 44px;
}

.value :deep(.el-input-number) {
  line-height: 44px;
}

.value :deep(.unit) {
  font-size: 15px;
}

.whole {
  padding: 8px 12px;
  border-radius: var(--radius-sm);
  background: var(--wheat-wash);
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.meta {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.meta-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.lbl {
  width: 60px;
  flex-shrink: 0;
  font-size: var(--fs-sm);
  color: var(--ink-2);
}

.days {
  display: inline-flex;
  border: 1px solid var(--line-strong);
  border-radius: 8px;
  overflow: hidden;
}

.day {
  padding: 5px 12px;
  border: none;
  background: var(--surface);
  font: inherit;
  font-size: var(--fs-sm);
  color: var(--ink-2);
  cursor: pointer;
}

.day + .day {
  border-left: 1px solid var(--line-strong);
}

.day.on {
  background: var(--green);
  color: #fff;
}

.date {
  width: 150px;
}

.foot {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
}

.foot .el-button + .el-button {
  margin-left: 0;
}

.keys {
  margin-right: auto;
  font-size: 12px;
  color: var(--ink-3);
}
</style>
