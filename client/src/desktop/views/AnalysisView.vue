<script setup lang="ts">
// 自助分析：自己选数据范围（实验田、年份、品种、重复、小区）、分组字段和指标，选一种图，马上出图；
// 图可以保存、导出图片和 Excel。也可以从平面图上框选小区后点"做图表"进来
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Close, Delete, Download, DocumentAdd, Picture, Plus, Search, Select } from '@element-plus/icons-vue'

import {
  analysisApi, fieldApi, trialApi, type AnalysisAgg, type AnalysisDimension, type AnalysisResult, type Field,
  type SavedChart, type TraitCategory, type TraitView, type Trial,
} from '@/shared/api'
import { saveFile } from '@/shared/api/http'
import {
  AGG_LABEL, CHART_TYPES, DIMENSION_LABEL, PRESETS, buildOption, chartProblem, dimensionRole, emptyScope, toQuery,
  type ChartConfig, type ChartType, type Preset,
} from '@/shared/analysis'
import { LIGHT, formatNumber } from '@/shared/charts'
import { TRAIT_CATEGORY } from '@/shared/labels'
import { useAuthStore } from '@/stores/auth'
import { useCatalogStore } from '@/stores/catalog'
import EChart from '@/desktop/components/EChart.vue'
import EmptyState from '@/desktop/components/EmptyState.vue'
import ChartTypeIcon from '@/desktop/components/ChartTypeIcon.vue'

const route = useRoute()
const auth = useAuthStore()
const catalog = useCatalogStore()

const fields = ref<Field[]>([])
const trials = ref<Trial[]>([])
const saved = ref<SavedChart<ChartConfig>[]>([])
const result = ref<AnalysisResult | null>(null)
const loading = ref(false)
const queryError = ref<string | null>(null)
const showTable = ref(false)
const chartRef = ref<InstanceType<typeof EChart> | null>(null)

const config = reactive<ChartConfig>({
  chartType: 'hbar',
  scope: emptyScope(),
  dimensions: [{ type: 'VARIETY' }],
  measures: [],
  sorted: true,
  showValues: true,
})
const chartName = ref('新建图表')
const chartId = ref<number | null>(null)

// ---------- 初始化 ----------

onMounted(async () => {
  try {
    await Promise.all([catalog.loadTraits(), catalog.loadVarieties()])
    fields.value = await fieldApi.list()
    saved.value = await analysisApi.charts<ChartConfig>()
    const q = route.query
    const fieldId = Number(q.fieldId) || fields.value[0]?.id || null
    config.scope.fieldId = fieldId
    await loadTrials()
    if (q.plots) {
      // 从平面图带过来的小区：那一年的试验，这些小区
      config.scope.plotIds = String(q.plots).split(',').map(Number).filter(Boolean)
      const trial = trials.value.find((t) => t.id === Number(q.trialId))
      config.scope.years = trial ? [trial.seasonYear] : []
      chartName.value = `平面图上选的 ${config.scope.plotIds.length} 个小区`
    }
    const yieldTrait = traitByCode('yield_per_mu')
    config.measures = yieldTrait ? [{ traitId: yieldTrait.trait.id, agg: 'AVG' }] : [{ agg: 'COUNT' }]
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
})

async function loadTrials() {
  trials.value = config.scope.fieldId ? await trialApi.list(config.scope.fieldId) : []
}

watch(() => config.scope.fieldId, async (id, old) => {
  if (old === undefined) return
  config.scope.years = []
  config.scope.plotIds = []
  await loadTrials()
})

const traitByCode = (code: string) => catalog.traits.find((t) => t.trait.code === code)

// ---------- 范围 ----------

const years = computed(() => [...new Set(trials.value.map((t) => t.seasonYear))].sort((a, b) => a - b))
const maxReplicate = computed(() => Math.max(3, ...trials.value.map((t) => t.replicateCount ?? 0)))

function toggle<T>(list: T[], v: T): T[] {
  return list.includes(v) ? list.filter((x) => x !== v) : [...list, v]
}

// ---------- 字段 ----------

const keyword = ref('')
const CATEGORICAL = new Set(['GRADE', 'OPTION', 'OPERATION'])

interface FieldItem {
  key: string
  label: string
  hint?: string
}

const dimensionItems = computed(() => {
  const base: (FieldItem & { type: AnalysisDimension; traitId?: number })[] = (Object.keys(DIMENSION_LABEL) as (keyof typeof DIMENSION_LABEL)[])
    .map((d) => ({ key: d, label: DIMENSION_LABEL[d], type: d }))
  const traits = catalog.traits
    .filter((t) => t.trait.enabled && CATEGORICAL.has(t.trait.dataType))
    .map((t) => ({ key: `t${t.trait.id}`, label: t.trait.name, hint: '按取值分组', type: 'TRAIT' as AnalysisDimension, traitId: t.trait.id }))
  return [...base, ...traits].filter((x) => !keyword.value || x.label.includes(keyword.value))
})

const measureGroups = computed(() => {
  const groups = new Map<TraitCategory, TraitView[]>()
  for (const t of catalog.statTraits) {
    if (keyword.value && !t.trait.name.includes(keyword.value)) continue
    groups.set(t.trait.category, [...(groups.get(t.trait.category) ?? []), t])
  }
  return [...groups.entries()].map(([c, items]) => ({ label: TRAIT_CATEGORY[c], items }))
})

function dimensionLabel(d: ChartConfig['dimensions'][number]): string {
  return d.type === 'TRAIT' ? catalog.traitById.get(d.traitId ?? -1)?.trait.name ?? '项目' : DIMENSION_LABEL[d.type]
}

function measureLabel(m: ChartConfig['measures'][number]): string {
  if (!m.traitId) return '小区数'
  const t = catalog.traitById.get(m.traitId)?.trait
  return `${t?.name ?? '项目'} · ${AGG_LABEL[m.agg]}`
}

function addDimension(item: { type: AnalysisDimension; traitId?: number }) {
  if (config.dimensions.some((d) => d.type === item.type && (d.traitId ?? null) === (item.traitId ?? null))) return
  if (config.dimensions.length >= 2) {
    ElMessage.info('最多两个分组：第一个做横轴，第二个分颜色')
    return
  }
  config.dimensions.push({ type: item.type, traitId: item.traitId ?? null })
}

function addMeasure(traitId: number | null) {
  if (config.measures.length >= 8) {
    ElMessage.info('最多 8 个指标')
    return
  }
  config.measures.push(traitId ? { traitId, agg: 'AVG' } : { agg: 'COUNT' })
}

const AGGS: AnalysisAgg[] = ['AVG', 'MAX', 'MIN', 'MEDIAN', 'SUM', 'CV', 'COUNT']

// ---------- 模板 ----------

function applyPreset(p: Preset) {
  const id = (code?: string) => (code ? traitByCode(code)?.trait.id ?? null : null)
  const measures = p.measures.map((m) => ({ traitId: id(m.trait), agg: m.agg })).filter((m) => m.agg === 'COUNT' || m.traitId)
  const dimensions = p.dimensions.map((d) => ({ type: d.type, traitId: id(d.trait) }))
  if (dimensions.some((d) => d.type === 'TRAIT' && !d.traitId)) {
    ElMessage.warning('录入项目里没有这个项目')
    return
  }
  Object.assign(config, { chartType: p.chartType, dimensions, measures, sorted: !!p.sorted })
  // 看变化的模板默认看全部年份
  if (p.dimensions.some((d) => d.type === 'YEAR')) config.scope.years = []
  chartName.value = p.title
  chartId.value = null
}

// ---------- 查询 ----------

const problem = computed(() => chartProblem(config))

/** 当前设置对应的查询；结果记下是哪个查询的，设置变了、新结果还没回来时不拿旧结果画图 */
const queryKey = computed(() => JSON.stringify(toQuery(config)))
const resultKey = ref('')

let timer: number | undefined
watch(queryKey, () => {
  window.clearTimeout(timer)
  timer = window.setTimeout(run, 250)
})

async function run() {
  if (config.measures.length === 0 || !config.scope.fieldId) {
    result.value = null
    return
  }
  const key = queryKey.value
  loading.value = true
  queryError.value = null
  try {
    const r = await analysisApi.query(toQuery(config))
    // 等待期间设置又变了：丢掉这个结果，等下一次查询
    if (key === queryKey.value) {
      result.value = r
      resultKey.value = key
    }
  } catch (e) {
    result.value = null
    queryError.value = (e as Error).message
  } finally {
    loading.value = false
  }
}

const fresh = computed(() => !!result.value && resultKey.value === queryKey.value)
const option = computed(() => (fresh.value && !problem.value ? buildOption(result.value!, config, LIGHT) : null))
const hasData = computed(() => (result.value?.rows.length ?? 0) > 0)

function cellText(v: string | number | null, col: number): string {
  if (v === null || v === undefined) return '—'
  if (result.value?.columns[col]?.kind === 'MEASURE' && typeof v === 'number') return formatNumber(v, 2)
  return String(v)
}

// ---------- 保存、导出 ----------

async function save(asNew: boolean) {
  const name = chartName.value.trim() || '未命名图表'
  const snapshot: ChartConfig = JSON.parse(JSON.stringify(config))
  try {
    if (chartId.value && !asNew) {
      await analysisApi.updateChart(chartId.value, name, snapshot)
    } else {
      chartId.value = (await analysisApi.createChart(name, snapshot)).id
    }
    saved.value = await analysisApi.charts<ChartConfig>()
    ElMessage.success(`已保存"${name}"`)
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

function open(c: SavedChart<ChartConfig>) {
  Object.assign(config, JSON.parse(JSON.stringify(c.config)))
  chartName.value = c.name
  chartId.value = c.id
  loadTrials()
}

async function removeSaved(c: SavedChart<ChartConfig>) {
  try {
    await ElMessageBox.confirm(`删除图表"${c.name}"？`, '删除图表', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' })
  } catch {
    return
  }
  try {
    await analysisApi.removeChart(c.id)
    if (chartId.value === c.id) chartId.value = null
    saved.value = await analysisApi.charts<ChartConfig>()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

const canEdit = (c: SavedChart<ChartConfig>) => auth.isTeacher || c.createdBy === auth.user?.id

function exportImage() {
  const url = chartRef.value?.toDataURL()
  if (url) saveFile(url, `${chartName.value || '图表'}.png`)
}

async function exportExcel() {
  try {
    await analysisApi.exportExcel(toQuery(config), `${chartName.value || '分析结果'}.xlsx`)
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

function setChartType(t: ChartType) {
  config.chartType = t
}
</script>

<template>
  <div class="analysis">
    <!-- 左：数据范围和字段 -->
    <aside class="left panel">
      <div class="block">
        <div class="block-title">数据范围</div>
        <el-select v-model="config.scope.fieldId" placeholder="实验田" class="full">
          <el-option v-for="f in fields" :key="f.id" :value="f.id" :label="f.name" />
        </el-select>

        <div class="sub-title">年份<span class="muted">{{ config.scope.years.length ? `选了 ${config.scope.years.length} 年` : '全部' }}</span></div>
        <div class="chips">
          <button v-for="y in years" :key="y" type="button" class="pick" :class="{ on: config.scope.years.includes(y) }"
            @click="config.scope.years = toggle(config.scope.years, y)">{{ y }}</button>
          <span v-if="years.length === 0" class="muted small">这块地还没有试验</span>
        </div>

        <div class="sub-title">品种<span class="muted">{{ config.scope.varietyIds.length ? `选了 ${config.scope.varietyIds.length} 个` : '全部' }}</span></div>
        <el-select v-model="config.scope.varietyIds" multiple collapse-tags collapse-tags-tooltip filterable clearable placeholder="全部品种" class="full">
          <el-option v-for="v in catalog.varieties" :key="v.id" :value="v.id" :label="v.name" />
        </el-select>

        <div class="sub-title">重复<span class="muted">{{ config.scope.replicates.length ? '' : '全部' }}</span></div>
        <div class="chips">
          <button v-for="r in maxReplicate" :key="r" type="button" class="pick" :class="{ on: config.scope.replicates.includes(r) }"
            @click="config.scope.replicates = toggle(config.scope.replicates, r)">{{ r }}</button>
        </div>

        <div v-if="config.scope.plotIds.length" class="plots-note">
          <el-icon><Select /></el-icon>只看平面图上选的 <b>{{ config.scope.plotIds.length }}</b> 个小区
          <el-button link size="small" @click="config.scope.plotIds = []">清除</el-button>
        </div>
      </div>

      <div class="block grow">
        <div class="block-title">字段<span class="muted">点一下加到图里</span></div>
        <el-input v-model="keyword" :prefix-icon="Search" clearable size="small" placeholder="找字段" class="full" />
        <div class="field-scroll">
          <div class="group-title">分组</div>
          <button v-for="d in dimensionItems" :key="d.key" type="button" class="field-item dim" @click="addDimension(d)">
            <span class="badge">分</span>{{ d.label }}<span v-if="d.hint" class="muted small">{{ d.hint }}</span>
          </button>
          <div class="group-title">指标</div>
          <button type="button" class="field-item" @click="addMeasure(null)"><span class="badge m">数</span>小区数</button>
          <template v-for="g in measureGroups" :key="g.label">
            <div class="group-sub">{{ g.label }}</div>
            <button v-for="t in g.items" :key="t.trait.id" type="button" class="field-item" @click="addMeasure(t.trait.id)">
              <span class="badge m">#</span>{{ t.trait.name }}<span v-if="t.trait.unit && t.trait.unit !== '级'" class="muted small">{{ t.trait.unit }}</span>
            </button>
          </template>
        </div>
      </div>
    </aside>

    <!-- 中：图 -->
    <main class="center">
      <div class="title-row">
        <el-input v-model="chartName" class="chart-name" maxlength="100" />
        <div class="actions">
          <el-button :icon="DocumentAdd" @click="save(false)">{{ chartId ? '保存' : '保存图表' }}</el-button>
          <el-button v-if="chartId" text @click="save(true)">另存为</el-button>
          <el-button :icon="Picture" :disabled="!option || config.chartType === 'table'" @click="exportImage">导出图片</el-button>
          <el-button :icon="Download" :disabled="!hasData" @click="exportExcel">导出 Excel</el-button>
        </div>
      </div>

      <div class="types panel">
        <button v-for="ct in CHART_TYPES" :key="ct.value" type="button" class="type" :class="{ on: config.chartType === ct.value }"
          :title="ct.hint" @click="setChartType(ct.value)">
          <ChartTypeIcon :type="ct.value" />
          <span>{{ ct.label }}</span>
        </button>
      </div>

      <div class="slots panel">
        <div class="slot">
          <span class="slot-label">分组</span>
          <span v-for="(d, i) in config.dimensions" :key="i" class="token dim">
            <i>{{ dimensionRole(config.chartType, i) }}</i>{{ dimensionLabel(d) }}
            <el-icon class="x" @click="config.dimensions.splice(i, 1)"><Close /></el-icon>
          </span>
          <span v-if="config.dimensions.length === 0" class="muted small">不分组：每个小区一个点（适合散点图、数据表）</span>
        </div>
        <div class="slot">
          <span class="slot-label">指标</span>
          <span v-for="(m, i) in config.measures" :key="i" class="token">
            <el-dropdown v-if="m.traitId" trigger="click" @command="(a: AnalysisAgg) => (m.agg = a)">
              <span class="agg">{{ measureLabel(m) }} ▾</span>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item v-for="a in AGGS" :key="a" :command="a">{{ AGG_LABEL[a] }}</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
            <span v-else>{{ measureLabel(m) }}</span>
            <el-icon class="x" @click="config.measures.splice(i, 1)"><Close /></el-icon>
          </span>
          <span v-if="config.measures.length === 0" class="muted small">从左边点一个指标</span>
          <div class="switches">
            <el-checkbox v-model="config.sorted" size="small">从大到小</el-checkbox>
            <el-checkbox v-model="config.showValues" size="small">显示数值</el-checkbox>
          </div>
        </div>
      </div>

      <section class="chart panel" v-loading="loading">
        <EmptyState v-if="queryError" art="chart" title="查不出来" :desc="queryError" />
        <EmptyState v-else-if="problem" art="chart" title="还差一步" :desc="problem" />
        <EmptyState v-else-if="result && !hasData" art="chart" title="这个范围里没有数据" desc="换个年份、品种，或者看看是不是还没录这项数据。" />
        <template v-else-if="result && fresh">
          <div class="chart-meta">
            <span>{{ result.records }} 条小区记录</span>
            <el-button link size="small" @click="showTable = !showTable">{{ showTable || config.chartType === 'table' ? '收起数据' : '查看数据' }}</el-button>
          </div>
          <EChart v-if="option && config.chartType !== 'table'" ref="chartRef" :option="option" height="440px" />
          <el-table v-if="showTable || config.chartType === 'table'" :data="result.rows" size="small" max-height="420" class="data-table">
            <el-table-column v-for="(c, i) in result.columns" :key="c.key" :label="c.unit && c.unit !== '级' ? `${c.label}（${c.unit}）` : c.label"
              :align="c.kind === 'MEASURE' ? 'right' : 'left'" min-width="110">
              <template #default="{ row }"><span :class="{ num: c.kind === 'MEASURE' }">{{ cellText(row[i], i) }}</span></template>
            </el-table-column>
          </el-table>
        </template>
      </section>
    </main>

    <!-- 右：常用分析和保存的图表 -->
    <aside class="right">
      <section class="panel panel-pad">
        <div class="panel-title">常用分析<span class="sub">点一下直接出图</span></div>
        <button v-for="p in PRESETS" :key="p.key" type="button" class="preset" @click="applyPreset(p)">
          <span class="preset-icon"><ChartTypeIcon :type="p.chartType" :size="20" /></span>
          <span class="preset-text"><b>{{ p.title }}</b><span>{{ p.desc }}</span></span>
        </button>
      </section>

      <section class="panel panel-pad">
        <div class="panel-title">保存的图表<span class="sub">{{ saved.length }} 张</span></div>
        <div v-if="saved.length === 0" class="muted small">做好的图点"保存图表"，下次直接打开。</div>
        <div v-for="c in saved" :key="c.id" class="saved" :class="{ on: c.id === chartId }" @click="open(c)">
          <span class="preset-icon small"><ChartTypeIcon :type="c.config.chartType" :size="16" /></span>
          <span class="saved-text">
            <b>{{ c.name }}</b>
            <span>{{ c.createdByName ?? '' }} · {{ c.updatedAt.slice(0, 10) }}</span>
          </span>
          <el-button v-if="canEdit(c)" link :icon="Delete" class="del" @click.stop="removeSaved(c)" />
        </div>
      </section>

      <div class="hint tip"><el-icon><Plus /></el-icon> 在平面图上框选小区后点"做图表"，可以只看这些小区。</div>
    </aside>
  </div>
</template>

<style scoped>
.analysis {
  display: grid;
  grid-template-columns: 250px minmax(0, 1fr) 270px;
  gap: 16px;
  padding: 22px 24px;
  height: 100%;
  box-sizing: border-box;
}

.left {
  display: flex;
  flex-direction: column;
  min-height: 0;
  overflow: hidden;
}

.block {
  padding: 16px 16px 12px;
  border-bottom: 1px solid var(--line);
}

.block.grow {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  border-bottom: none;
}

.block-title {
  font-family: var(--font-serif);
  font-weight: 700;
  font-size: 15px;
  color: var(--forest);
  margin-bottom: 10px;
  display: flex;
  justify-content: space-between;
  align-items: baseline;
}

.block-title .muted {
  font-family: var(--font-sans);
  font-weight: normal;
  font-size: 11.5px;
}

.full {
  width: 100%;
}

.sub-title {
  margin: 12px 0 6px;
  font-size: 12.5px;
  color: var(--ink-2);
  display: flex;
  justify-content: space-between;
}

.small {
  font-size: 11.5px;
}

.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
}

.pick {
  height: 26px;
  min-width: 34px;
  padding: 0 9px;
  border-radius: 13px;
  border: 1px solid var(--line-strong);
  background: #fffefb;
  color: var(--ink-2);
  font: inherit;
  font-size: 12.5px;
  font-variant-numeric: tabular-nums;
  cursor: pointer;
}

.pick.on {
  background: var(--forest);
  border-color: var(--forest);
  color: #fbf3df;
}

.plots-note {
  margin-top: 12px;
  display: flex;
  align-items: center;
  gap: 5px;
  padding: 7px 10px;
  border-radius: 9px;
  background: var(--wheat-soft);
  font-size: 12.5px;
  color: #6b5216;
}

.field-scroll {
  flex: 1;
  overflow: auto;
  margin: 8px -6px 0;
  padding: 0 6px;
}

.group-title {
  margin: 10px 0 4px;
  font-size: 11px;
  letter-spacing: 3px;
  color: var(--wheat);
}

.group-sub {
  margin: 8px 0 2px;
  font-size: 11.5px;
  color: var(--ink-3);
}

.field-item {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 5px 7px;
  margin: 1px 0;
  border: 1px solid transparent;
  border-radius: 7px;
  background: none;
  font: inherit;
  font-size: 13px;
  color: var(--ink);
  text-align: left;
  cursor: pointer;
}

.field-item:hover {
  background: var(--surface-2);
  border-color: var(--line);
}

.field-item .muted {
  margin-left: auto;
}

.badge {
  width: 18px;
  height: 18px;
  border-radius: 5px;
  font-size: 10.5px;
  font-weight: 700;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  background: var(--sky-soft);
  color: var(--sky);
  flex-shrink: 0;
}

.badge.m {
  background: var(--green-soft);
  color: var(--green);
}

/* 中间 */
.center {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 12px;
  overflow: auto;
}

.title-row {
  display: flex;
  align-items: center;
  gap: 12px;
}

.chart-name {
  flex: 1;
}

.chart-name :deep(.el-input__wrapper) {
  background: transparent;
  box-shadow: none;
  padding: 0;
}

.chart-name :deep(.el-input__inner) {
  font-family: var(--font-serif);
  font-size: 24px;
  font-weight: 700;
  color: var(--forest);
  height: 40px;
}

.chart-name :deep(.el-input__wrapper.is-focus) {
  box-shadow: 0 1px 0 var(--wheat);
}

.actions {
  display: flex;
  gap: 6px;
}

.actions .el-button + .el-button {
  margin-left: 0;
}

.types {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  padding: 6px;
}

.type {
  flex: 1;
  min-width: 56px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 3px;
  padding: 8px 4px 6px;
  border: 1px solid transparent;
  border-radius: 10px;
  background: none;
  color: var(--ink-2);
  font: inherit;
  font-size: 12px;
  cursor: pointer;
}

.type:hover {
  background: var(--surface-2);
}

.type.on {
  background: var(--green-soft);
  border-color: #c9dccf;
  color: var(--forest);
  font-weight: 600;
}

.slots {
  padding: 10px 14px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.slot {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  min-height: 30px;
}

.slot-label {
  width: 40px;
  font-size: 12.5px;
  color: var(--ink-3);
}

.token {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 28px;
  padding: 0 6px 0 10px;
  border-radius: 14px;
  background: var(--green-soft);
  border: 1px solid #c9dccf;
  color: var(--forest);
  font-size: 13px;
}

.token.dim {
  background: var(--sky-soft);
  border-color: #c7dbe5;
  color: #31586d;
}

.token i {
  font-style: normal;
  font-size: 11px;
  padding: 0 5px;
  border-radius: 6px;
  background: rgba(255, 255, 255, 0.7);
}

.agg {
  cursor: pointer;
  color: inherit;
}

.x {
  cursor: pointer;
  opacity: 0.55;
}

.x:hover {
  opacity: 1;
}

.switches {
  margin-left: auto;
  display: flex;
  gap: 12px;
}

.chart {
  padding: 14px 16px;
  min-height: 480px;
}

.chart-meta {
  display: flex;
  justify-content: space-between;
  font-size: 12px;
  color: var(--ink-3);
  margin-bottom: 4px;
}

.data-table {
  margin-top: 10px;
}

/* 右边 */
.right {
  display: flex;
  flex-direction: column;
  gap: 14px;
  overflow: auto;
}

.preset {
  width: 100%;
  display: flex;
  gap: 10px;
  align-items: flex-start;
  padding: 9px 8px;
  margin: 0 0 2px;
  border: 1px solid transparent;
  border-radius: 10px;
  background: none;
  font: inherit;
  text-align: left;
  cursor: pointer;
}

.preset:hover {
  background: #fbf3df;
  border-color: #ead5a8;
}

.preset-icon {
  width: 34px;
  height: 34px;
  border-radius: 9px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--wheat-soft);
  color: var(--wheat);
  flex-shrink: 0;
}

.preset-icon.small {
  width: 28px;
  height: 28px;
}

.preset-text,
.saved-text {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.preset-text b,
.saved-text b {
  font-size: 13.5px;
  color: var(--forest);
}

.preset-text span,
.saved-text span {
  font-size: 12px;
  color: var(--ink-3);
  line-height: 1.5;
}

.saved {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px;
  margin: 0 -8px;
  border-radius: 10px;
  cursor: pointer;
}

.saved:hover,
.saved.on {
  background: var(--surface-2);
}

.saved-text {
  flex: 1;
}

.saved-text b {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.del {
  opacity: 0.5;
}

.saved:hover .del {
  opacity: 1;
}

.tip {
  display: flex;
  gap: 6px;
  padding: 0 4px;
}

/* 窗口窄的时候（例如 1440 宽以下），右边的常用分析、保存的图表移到图的下面 */
@media (max-width: 1500px) {
  .analysis {
    grid-template-columns: 236px minmax(0, 1fr);
    height: auto;
    min-height: 100%;
  }

  .left {
    max-height: calc(100vh - 44px - var(--topbar-h, 0px));
    position: sticky;
    top: 22px;
  }

  .right {
    grid-column: 2;
    display: grid;
    grid-template-columns: minmax(0, 1.4fr) minmax(0, 1fr);
    align-items: start;
    overflow: visible;
  }

  .tip {
    grid-column: 1 / -1;
  }
}
</style>
