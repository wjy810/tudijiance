<script setup lang="ts">
// 统计分析：本年度按品种汇总（和对照比较、排位次），以及这块地历年的变化
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { EChartsCoreOption } from 'echarts/core'

import { statsApi, type MultiYear, type ReportColumn, type TrialReport, type VarietySummary } from '@/shared/api'
import { useTrialPicker } from '@/shared/composables/useTrialPicker'
import { useCatalogStore } from '@/stores/catalog'
import { FONT, LIGHT, MAX_SERIES, assignSlots, axisStyle, formatNumber, formatPercent, tooltipStyle } from '@/shared/charts'
import EChart from '@/desktop/components/EChart.vue'
import { Download } from '@element-plus/icons-vue'

const catalog = useCatalogStore()
const picker = useTrialPicker()
const t = LIGHT

const traitId = ref<number | null>(null)
const summary = ref<VarietySummary | null>(null)
const multi = ref<MultiYear | null>(null)
const loadingSummary = ref(false)
const loadingMulti = ref(false)

const trait = computed(() => catalog.traitById.get(traitId.value ?? -1)?.trait ?? null)
const digits = computed(() => trait.value?.precisionDigits ?? 1)
const unit = computed(() => summary.value?.unit ?? trait.value?.unit ?? '')

onMounted(async () => {
  try {
    await Promise.all([picker.init(), catalog.loadTraits()])
    traitId.value = catalog.traits.find((x) => x.trait.code === 'yield_per_mu')?.trait.id
      ?? catalog.statTraits[0]?.trait.id ?? null
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
})

async function loadSummary() {
  if (!picker.trialId.value || !traitId.value) {
    summary.value = null
    return
  }
  loadingSummary.value = true
  try {
    summary.value = await statsApi.varietySummary(picker.trialId.value, traitId.value)
  } catch (e) {
    summary.value = null
    ElMessage.error((e as Error).message)
  } finally {
    loadingSummary.value = false
  }
}

async function loadMulti() {
  if (!picker.fieldId.value || !traitId.value) {
    multi.value = null
    return
  }
  loadingMulti.value = true
  try {
    multi.value = await statsApi.multiYear(picker.fieldId.value, traitId.value)
  } catch (e) {
    multi.value = null
    ElMessage.error((e as Error).message)
  } finally {
    loadingMulti.value = false
  }
}

watch([picker.trialId, traitId], loadSummary)
watch([picker.fieldId, traitId], loadMulti)

// ---------- 品种主要性状汇总表 ----------

const report = ref<TrialReport | null>(null)
const loadingReport = ref(false)
const exportingReport = ref(false)

async function loadReport() {
  if (!picker.trialId.value) {
    report.value = null
    return
  }
  loadingReport.value = true
  try {
    report.value = await statsApi.trialReport(picker.trialId.value)
  } catch (e) {
    report.value = null
    ElMessage.error((e as Error).message)
  } finally {
    loadingReport.value = false
  }
}

watch(picker.trialId, loadReport)

/** 当前试验还没有数据时（比如今年刚播种），建议看有数据的最近一年 */
const suggestTrial = computed(() => {
  const years = multi.value?.years ?? []
  if (years.length === 0) return null
  const latest = Math.max(...years)
  const trial = picker.trials.value.find((tr) => tr.seasonYear === latest)
  return trial && trial.id !== picker.trialId.value ? trial : null
})

async function exportReport() {
  if (!report.value) {
    return
  }
  exportingReport.value = true
  try {
    await statsApi.exportTrialReport(report.value.trialId, `${report.value.trialName}-品种主要性状汇总表.xlsx`)
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    exportingReport.value = false
  }
}

function columnLabel(c: ReportColumn): string {
  return c.unit && c.unit !== '级' ? `${c.name}（${c.unit}）` : c.name
}

function reportDigits(c: ReportColumn): number {
  return catalog.traitById.get(c.traitId)?.trait.precisionDigits ?? 1
}

function signed(v: number | null, suffix = ''): string {
  return v === null ? '' : `${v > 0 ? '+' : ''}${v}${suffix}`
}

// ---------- 本年度：按品种汇总 ----------

const barOption = computed<EChartsCoreOption>(() => {
  // 位次从高到低，最高的放最上面
  const rows = [...(summary.value?.rows ?? [])].reverse()
  return {
    animationDuration: 600,
    textStyle: { fontFamily: FONT },
    grid: { left: 8, right: 120, top: 8, bottom: 24, containLabel: true },
    tooltip: {
      trigger: 'item',
      ...tooltipStyle(t),
      formatter: (p: { dataIndex: number }) => {
        const r = rows[p.dataIndex]!
        const vs = r.control ? '对照品种' : r.vsCkPercent === null ? '' : `比对照 ${formatPercent(r.vsCkPercent)}`
        return `<b>${r.varietyName}</b><br>${formatNumber(r.value, digits.value)} ${unit.value}<br>`
          + `第 ${r.rank} 位 · ${r.plots} 个小区${vs ? '<br>' + vs : ''}`
      },
    },
    xAxis: { type: 'value', ...axisStyle(t), name: unit.value, nameLocation: 'end' },
    yAxis: {
      type: 'category',
      data: rows.map((r) => (r.control ? `${r.varietyName}（CK）` : r.varietyName)),
      ...axisStyle(t),
      axisLabel: { color: t.textSecondary, fontFamily: FONT },
      splitLine: { show: false },
    },
    series: [{
      type: 'bar',
      barMaxWidth: 20,
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
          return `${formatNumber(r.value, digits.value)}${pct ? '  ' + pct : ''}`
        },
      },
    }],
  }
})

const barHeight = computed(() => `${Math.max(160, (summary.value?.rows.length ?? 0) * 34 + 48)}px`)

// ---------- 历年变化 ----------

const shown = ref<number[]>([])
const slots = ref(new Map<number, number>())

// 默认显示对照和排名靠前的品种，一共不超过 8 个
watch(multi, (m) => {
  if (!m) {
    shown.value = []
    return
  }
  const last = (s: MultiYear['series'][number]) => [...s.values].reverse().find((v) => v !== null) ?? -Infinity
  const ordered = [...m.series].sort((a, b) => Number(b.control) - Number(a.control) || last(b) - last(a))
  shown.value = ordered.slice(0, MAX_SERIES).map((s) => s.varietyId)
})

watch(shown, (ids) => {
  slots.value = assignSlots(slots.value, ids)
}, { immediate: true })

function toggleVariety(id: number, on: boolean) {
  if (on && shown.value.length >= MAX_SERIES) {
    ElMessage.warning(`最多同时显示 ${MAX_SERIES} 个品种，先取消一个`)
    return
  }
  shown.value = on ? [...shown.value, id] : shown.value.filter((x) => x !== id)
}

const lineOption = computed<EChartsCoreOption>(() => {
  const m = multi.value
  if (!m) {
    return {}
  }
  const series = m.series.filter((s) => shown.value.includes(s.varietyId))
  const directLabels = series.length <= 4
  return {
    animationDuration: 1200,
    animationEasing: 'cubicOut',
    textStyle: { fontFamily: FONT },
    // 图例用图表上方的品种标签（带颜色，可点选），不再画 ECharts 自带的图例
    grid: { left: 8, right: directLabels ? 110 : 24, top: 28, bottom: m.years.length > 8 ? 56 : 24, containLabel: true },
    tooltip: {
      trigger: 'axis',
      ...tooltipStyle(t),
      axisPointer: { type: 'line', lineStyle: { color: t.axis } },
      valueFormatter: (v: number | null) => (v === null ? '无数据' : `${formatNumber(v, digits.value)} ${unit.value}`),
    },
    xAxis: { type: 'category', data: m.years.map((y) => `${y}`), boundaryGap: false, ...axisStyle(t), splitLine: { show: false } },
    yAxis: { type: 'value', scale: true, name: unit.value, ...axisStyle(t) },
    dataZoom: m.years.length > 8 ? [{ type: 'slider', height: 18, bottom: 8 }, { type: 'inside' }] : [],
    series: series.map((s) => {
      const color = t.series[slots.value.get(s.varietyId) ?? 0]
      return {
        type: 'line',
        name: s.control ? `${s.varietyName}（CK）` : s.varietyName,
        data: s.values,
        connectNulls: true,
        lineStyle: { width: 2, color, type: s.control ? 'dashed' : 'solid', cap: 'round', join: 'round' },
        itemStyle: { color, borderColor: t.surface, borderWidth: 2 },
        symbol: 'circle',
        symbolSize: 8,
        emphasis: { focus: 'series' },
        endLabel: directLabels
          ? { show: true, color: t.textSecondary, fontFamily: FONT, formatter: '{a}' }
          : { show: false },
      }
    }),
  }
})
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h2>统计分析</h2>
        <div class="page-desc">每个小区取最近一次的值，同一品种的各个重复按项目的汇总方法合并，和对照品种比较。</div>
      </div>
    </div>

    <div class="filter-bar panel">
      <span class="muted">实验田</span>
      <el-select :model-value="picker.fieldId.value" placeholder="实验田" class="w180" @update:model-value="picker.selectField">
        <el-option v-for="f in picker.fields.value" :key="f.id" :value="f.id" :label="f.name" />
      </el-select>
      <span class="muted gap">试验</span>
      <el-select v-model="picker.trialId.value" placeholder="试验" class="w240" no-data-text="这块地还没有试验">
        <el-option v-for="tr in picker.trials.value" :key="tr.id" :value="tr.id" :label="`${tr.seasonYear} 年 · ${tr.name}`" />
      </el-select>
      <span class="muted gap">统计项目</span>
      <el-select v-model="traitId" placeholder="统计项目" filterable class="w220">
        <el-option v-for="x in catalog.statTraits" :key="x.trait.id" :value="x.trait.id" :label="x.trait.unit ? `${x.trait.name}（${x.trait.unit}）` : x.trait.name" />
      </el-select>
    </div>

    <section class="panel panel-pad block">
      <div class="panel-title report-title">
        <span>品种主要性状汇总表</span>
        <span class="sub">{{ report ? `${report.seasonYear} 年 · ${report.trialName}` : '' }}；各重复汇总后的值，按折合亩产位次排列</span>
        <span class="spacer" />
        <el-button size="small" :icon="Download" :disabled="!report || report.rows.length === 0" :loading="exportingReport" @click="exportReport">
          导出 Excel
        </el-button>
      </div>
      <div v-loading="loadingReport">
        <el-empty v-if="!report || report.columns.length === 0" :image-size="90"
          :description="picker.trialId.value ? '这个试验还没有可以汇总的数据' : '先选择试验'">
          <el-button v-if="suggestTrial" type="primary" @click="picker.trialId.value = suggestTrial.id">
            看 {{ suggestTrial.seasonYear }} 年的试验
          </el-button>
        </el-empty>
        <el-table v-else :data="report.rows" size="small" border class="report">
          <el-table-column label="位次" width="56" align="center">
            <template #default="{ row }">{{ row.yieldRank ?? '' }}</template>
          </el-table-column>
          <el-table-column label="品种" min-width="120" fixed>
            <template #default="{ row }">
              {{ row.varietyName }}
              <el-tag v-if="row.control" size="small" type="danger" effect="plain">CK</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="小区数" width="64" align="center" prop="plots" />
          <template v-for="(c, i) in report.columns" :key="c.code">
            <el-table-column :label="columnLabel(c)" min-width="96" align="right">
              <template #default="{ row }"><span class="num">{{ row.values[i] === null ? '' : formatNumber(row.values[i], reportDigits(c)) }}</span></template>
            </el-table-column>
            <el-table-column v-if="c.code === 'whole_growth_days'" label="比对照（天）" min-width="90" align="right">
              <template #default="{ row }"><span class="num">{{ signed(row.growthDaysVsCk) }}</span></template>
            </el-table-column>
          </template>
          <el-table-column v-if="report.hasYield" label="亩产比对照" min-width="96" align="right">
            <template #default="{ row }">
              <span class="num" :class="{ up: row.yieldVsCkPercent > 0, down: row.yieldVsCkPercent < 0 }">{{ formatPercent(row.yieldVsCkPercent) }}</span>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </section>

    <section class="panel panel-pad block">
      <div class="panel-title">
        {{ summary ? `${summary.seasonYear} 年各品种${summary.traitName}` : '本年度按品种汇总' }}
        <span v-if="summary && summary.rows.length" class="sub">
          {{ summary.controlVariety ? `对照品种：${summary.controlVariety}（灰色）` : '没有设对照品种，不计算比对照增减' }}
        </span>
      </div>
      <div v-loading="loadingSummary">
        <el-empty v-if="!summary || summary.rows.length === 0" :image-size="90"
          :description="picker.trialId.value ? '这个项目还没有数据' : '先选择试验'" />
        <div v-else class="summary">
          <EChart :option="barOption" :height="barHeight" class="bar" />
          <el-table :data="summary.rows" size="small" border class="table">
            <el-table-column prop="rank" label="位次" width="56" align="center" />
            <el-table-column label="品种" min-width="120">
              <template #default="{ row }">
                {{ row.varietyName }}
                <el-tag v-if="row.control" size="small" type="danger" effect="plain">CK</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="plots" label="小区数" width="64" align="center" />
            <el-table-column :label="unit ? `值（${unit}）` : '值'" width="110" align="right">
              <template #default="{ row }"><span class="num">{{ formatNumber(row.value, digits) }}</span></template>
            </el-table-column>
            <el-table-column label="比对照" width="86" align="right">
              <template #default="{ row }">
                <span class="num" :class="{ up: row.vsCkPercent > 0, down: row.vsCkPercent < 0 }">{{ formatPercent(row.vsCkPercent) }}</span>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </div>
    </section>

    <section class="panel panel-pad block">
      <div class="panel-title">
        {{ picker.field.value?.name ?? '' }} 历年{{ multi?.traitName ?? '' }}变化
        <span class="sub">每年一个试验；虚线是对照品种；点品种标签显示或隐藏</span>
      </div>
      <div v-loading="loadingMulti">
        <el-empty v-if="!multi || multi.years.length === 0" :image-size="90" description="还没有数据" />
        <template v-else>
          <div class="chips">
            <button
              v-for="s in multi.series"
              :key="s.varietyId"
              type="button"
              class="chip"
              :class="{ off: !shown.includes(s.varietyId) }"
              @click="toggleVariety(s.varietyId, !shown.includes(s.varietyId))"
            >
              <span
                class="chip-key"
                :class="{ dashed: s.control }"
                :style="{ borderColor: shown.includes(s.varietyId) ? LIGHT.series[slots.get(s.varietyId) ?? 0] : '#c9cec8' }"
              />
              {{ s.varietyName }}{{ s.control ? '（CK）' : '' }}
            </button>
            <span class="muted small">最多同时显示 {{ MAX_SERIES }} 个</span>
          </div>
          <div v-if="multi.years.length === 1" class="hint">目前只有 {{ multi.years[0] }} 年的数据，多录几年后这里能看到变化趋势。</div>
          <EChart :option="lineOption" height="360px" />
        </template>
      </div>
    </section>
  </div>
</template>

<style scoped>
.gap {
  margin-left: 10px;
}

.report-title .spacer {
  flex: 1;
}

.small {
  font-size: 12px;
}

.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  margin-bottom: 8px;
}

.chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  border: 1px solid var(--app-border);
  background: #fff;
  border-radius: 999px;
  padding: 4px 12px 4px 10px;
  font-size: 13px;
  color: var(--app-text);
  cursor: pointer;
  font-family: inherit;
}

.chip:hover {
  border-color: var(--el-color-primary-light-5);
}

.chip.off {
  color: var(--app-text-muted);
  background: #f7f8f6;
}

.chip-key {
  width: 16px;
  height: 0;
  border-top: 3px solid;
  border-radius: 2px;
}

.chip-key.dashed {
  border-top-style: dashed;
}

.w180 {
  width: 180px;
}

.w220 {
  width: 220px;
}

.w240 {
  width: 240px;
}

.block {
  margin-bottom: 16px;
}


.summary {
  display: grid;
  grid-template-columns: minmax(0, 3fr) minmax(320px, 2fr);
  gap: 16px;
  align-items: start;
}

.num {
  font-variant-numeric: tabular-nums;
}

.up {
  color: #006300;
}

.down {
  color: #c92a2a;
}

.pick {
  display: flex;
  flex-wrap: wrap;
  gap: 2px 4px;
  align-items: center;
  margin-bottom: 8px;
}
</style>
