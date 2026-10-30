<script setup lang="ts">
// 统计分析：本年度按品种汇总（和对照比较、排位次），以及这块地历年的变化
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { EChartsCoreOption } from 'echarts/core'

import { statsApi, type MultiYear, type VarietySummary } from '@/shared/api'
import { useTrialPicker } from '@/shared/composables/useTrialPicker'
import { useCatalogStore } from '@/stores/catalog'
import { FONT, LIGHT, MAX_SERIES, assignSlots, axisStyle, formatNumber, formatPercent, tooltipStyle } from '@/shared/charts'
import EChart from '@/desktop/components/EChart.vue'

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
    grid: { left: 8, right: directLabels ? 110 : 24, top: 56, bottom: m.years.length > 8 ? 56 : 24, containLabel: true },
    legend: {
      top: 0,
      left: 0,
      icon: 'roundRect',
      itemWidth: 14,
      itemHeight: 4,
      textStyle: { color: t.textSecondary, fontFamily: FONT },
    },
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
      <h2>统计分析</h2>
    </div>

    <div class="filters">
      <el-select :model-value="picker.fieldId.value" placeholder="实验田" class="w180" @update:model-value="picker.selectField">
        <el-option v-for="f in picker.fields.value" :key="f.id" :value="f.id" :label="f.name" />
      </el-select>
      <el-select v-model="picker.trialId.value" placeholder="试验" class="w240" no-data-text="这块地还没有试验">
        <el-option v-for="tr in picker.trials.value" :key="tr.id" :value="tr.id" :label="`${tr.seasonYear} 年 · ${tr.name}`" />
      </el-select>
      <el-select v-model="traitId" placeholder="统计项目" filterable class="w220">
        <el-option v-for="x in catalog.statTraits" :key="x.trait.id" :value="x.trait.id" :label="x.trait.unit ? `${x.trait.name}（${x.trait.unit}）` : x.trait.name" />
      </el-select>
      <span class="hint">每个小区取最近一次的值，同一品种的各个重复按项目的汇总方法合并。</span>
    </div>

    <el-card shadow="never" class="block">
      <template #header>
        <div class="card-head">
          <b>{{ summary ? `${summary.seasonYear} 年 · ${summary.traitName}` : '本年度按品种汇总' }}</b>
          <span v-if="summary && summary.rows.length" class="muted">
            {{ summary.controlVariety ? `对照：${summary.controlVariety}` : '没有设对照品种，不计算比对照增减' }}
          </span>
        </div>
      </template>
      <div v-loading="loadingSummary">
        <el-empty v-if="!summary || summary.rows.length === 0" :image-size="60"
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
    </el-card>

    <el-card shadow="never" class="block">
      <template #header>
        <div class="card-head">
          <b>{{ picker.field.value?.name ?? '' }} 历年{{ multi?.traitName ?? '' }}变化</b>
          <span class="muted">每年一个试验；虚线是对照品种</span>
        </div>
      </template>
      <div v-loading="loadingMulti">
        <el-empty v-if="!multi || multi.years.length === 0" :image-size="60" description="还没有数据" />
        <template v-else>
          <div class="pick">
            <span class="muted">显示的品种（最多 {{ MAX_SERIES }} 个）：</span>
            <el-checkbox
              v-for="s in multi.series"
              :key="s.varietyId"
              :model-value="shown.includes(s.varietyId)"
              size="small"
              @update:model-value="(v: string | number | boolean) => toggleVariety(s.varietyId, !!v)"
            >
              {{ s.varietyName }}{{ s.control ? '（CK）' : '' }}
            </el-checkbox>
          </div>
          <div v-if="multi.years.length === 1" class="hint">目前只有 {{ multi.years[0] }} 年的数据，多录几年后这里能看到变化趋势。</div>
          <EChart :option="lineOption" height="360px" />
        </template>
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.filters {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  margin-bottom: 14px;
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

.card-head {
  display: flex;
  gap: 12px;
  align-items: baseline;
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
