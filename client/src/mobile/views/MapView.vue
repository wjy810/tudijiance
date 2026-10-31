<script setup lang="ts">
// 平面图：选小区、录入。点小区选中，点行号列号选整行整列，长按小区看详情；选好后点"录入"
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { showToast } from 'vant'

import type { Plot } from '@/shared/api'
import { PLOT_KIND, PLOT_STATUS, replicateLabel } from '@/shared/labels'
import { VARIETY_COLORS } from '@/shared/colors'
import { useFieldStore } from '../stores/field'
import { plotCode } from '../display'
import PlotPicker from '../components/PlotPicker.vue'
import type { PickerCell } from '../components/picker'
import EntrySheet from '../components/EntrySheet.vue'
import EmptyState from '@/desktop/components/EmptyState.vue'

const field = useFieldStore()
const router = useRouter()

const view = ref<'status' | 'variety'>('status')
const scale = ref(Number(localStorage.getItem('tudijiance.mobile.scale')) || 1)
watch(scale, (s) => localStorage.setItem('tudijiance.mobile.scale', String(s)))

const selected = ref(new Set<number>())
watch(() => field.trialId, () => (selected.value = new Set()))

const varietyColor = computed(() => {
  const ids = [...new Set((field.layout?.plantings ?? []).map((p) => p.varietyId).filter((v): v is number => !!v))].sort((a, b) => a - b)
  return new Map(ids.map((id, i) => [id, VARIETY_COLORS[i % VARIETY_COLORS.length]!]))
})

const cells = computed<PickerCell[]>(() => field.plots.map((p) => {
  const planting = field.plantingByPlot.get(p.id)
  const variety = planting?.varietyId ? field.varietyById.get(planting.varietyId) : undefined
  const st = field.statusByPlot.get(p.id)
  const isPlot = p.kind === 'PLOT'
  const fill = !isPlot ? PLOT_STATUS[p.kind as 'GUARD' | 'PATH'].color
    : view.value === 'variety' ? (planting?.varietyId ? varietyColor.value.get(planting.varietyId)! : '#f1ebdd')
      : PLOT_STATUS[st?.status ?? 'NONE'].color
  return {
    plotId: p.id, rowNo: p.rowNo, colNo: p.colNo, code: plotCode(p),
    sub: isPlot ? (variety?.name ?? '未分配') : PLOT_KIND[p.kind],
    third: isPlot && planting?.replicateNo ? `重复 ${replicateLabel(planting.replicateNo)}` : undefined,
    fill, selectable: isPlot && !!field.trial, ck: !!variety?.controlType, abnormal: st?.status === 'ABNORMAL',
    today: st?.enteredToday, unsynced: field.unsynced.has(p.id),
  }
}))

function toggle(id: number) {
  const next = new Set(selected.value)
  if (next.has(id)) next.delete(id)
  else next.add(id)
  selected.value = next
}

/** 整行、整列：都选上了就全取消，否则全选上 */
function line(kind: 'row' | 'col', index: number) {
  const ids = cells.value.filter((c) => c.selectable && (kind === 'row' ? c.rowNo : c.colNo) === index).map((c) => c.plotId)
  const all = ids.every((id) => selected.value.has(id))
  const next = new Set(selected.value)
  ids.forEach((id) => (all ? next.delete(id) : next.add(id)))
  selected.value = next
}

function selectAll() {
  selected.value = new Set(cells.value.filter((c) => c.selectable).map((c) => c.plotId))
}

/** 选上和已选小区同一品种的全部小区，例如一个品种的三个重复 */
function sameVariety() {
  const varieties = new Set([...selected.value].map((id) => field.plantingByPlot.get(id)?.varietyId).filter(Boolean))
  const ids = field.plots.filter((p) => varieties.has(field.plantingByPlot.get(p.id)?.varietyId)).map((p) => p.id)
  selected.value = new Set([...selected.value, ...ids])
  showToast(`选了 ${selected.value.size} 个小区`)
}

const selectedPlots = computed<Plot[]>(() => field.plots.filter((p) => selected.value.has(p.id)))

const entryOpen = ref(false)
function openEntry() {
  if (!field.trial) {
    showToast('这块地还没有试验，请老师先在电脑上建试验')
    return
  }
  entryOpen.value = true
}

function onSaved() {
  selected.value = new Set()
}

function hold(plotId: number) {
  if (field.trialId) {
    void router.push(`/plot/${field.trialId}/${plotId}`)
  }
}

// ---------- 换地、换试验 ----------
const fieldSheet = ref(false)
const trialSheet = ref(false)
const fieldActions = computed(() => field.fields.map((f) => ({ name: f.name, id: f.id, color: f.id === field.fieldId ? '#2e6b4f' : undefined })))
const trialActions = computed(() => field.trials.map((t) => ({ name: `${t.seasonYear} 年 · ${t.name}`, id: t.id, color: t.id === field.trialId ? '#2e6b4f' : undefined })))
</script>

<template>
  <div class="map">
    <header class="bar">
      <div class="where" @click="fieldSheet = true">
        <div class="field-name m-title">{{ field.field?.name ?? '选择实验田' }}<van-icon name="arrow-down" /></div>
        <div class="trial-name" @click.stop="trialSheet = true">{{ field.trial ? `${field.trial.seasonYear} 年 · ${field.trial.name}` : '还没有试验' }}<van-icon name="arrow-down" /></div>
      </div>
      <div class="tools">
        <van-button size="small" round icon="minus" :disabled="scale <= 0.7" @click="scale = Math.max(0.7, +(scale - 0.15).toFixed(2))" />
        <van-button size="small" round icon="plus" :disabled="scale >= 1.45" @click="scale = Math.min(1.45, +(scale + 0.15).toFixed(2))" />
      </div>
    </header>

    <div class="view-switch">
      <button type="button" :class="{ on: view === 'status' }" @click="view = 'status'">看状态</button>
      <button type="button" :class="{ on: view === 'variety' }" @click="view = 'variety'">看品种</button>
      <span class="legend"><i class="d today" />今天录过 <i class="d unsynced" />待上传</span>
    </div>

    <EmptyState v-if="field.error" art="field" title="打不开平面图" :desc="field.error" :size="120">
      <van-button round type="primary" @click="field.load()">再试一次</van-button>
    </EmptyState>
    <van-loading v-else-if="field.loading && field.plots.length === 0" class="loading" vertical>加载中…</van-loading>
    <PlotPicker
      v-else
      class="picker"
      :rows="field.field?.rowCount ?? 0"
      :cols="field.field?.colCount ?? 0"
      :cells="cells"
      :selected="selected"
      :scale="scale"
      @toggle="toggle"
      @line="line"
      @hold="hold"
    />

    <div class="action" :class="{ active: selected.size > 0 }">
      <template v-if="selected.size > 0">
        <div class="sel">已选 <b class="num">{{ selected.size }}</b> 个</div>
        <van-button size="small" round plain @click="selected = new Set()">清空</van-button>
        <van-button size="small" round plain @click="sameVariety">同品种</van-button>
        <van-button round class="btn-wheat go" icon="edit" @click="openEntry">录入</van-button>
      </template>
      <template v-else>
        <span class="tip">点小区选中，点行号、列号选整行整列，长按看详情</span>
        <van-button size="small" round plain type="primary" :disabled="!field.trial" @click="selectAll">全选</van-button>
      </template>
    </div>

    <EntrySheet v-model:show="entryOpen" :plots="selectedPlots" @saved="onSaved" />

    <van-action-sheet v-model:show="fieldSheet" :actions="fieldActions" cancel-text="取消" title="选择实验田"
      @select="(a: { id: number }) => { fieldSheet = false; field.selectField(a.id) }" />
    <van-action-sheet v-model:show="trialSheet" :actions="trialActions" cancel-text="取消" title="选择试验"
      @select="(a: { id: number }) => { trialSheet = false; field.selectTrial(a.id) }" />
  </div>
</template>

<style scoped>
.map {
  height: calc(100vh - 56px - env(safe-area-inset-bottom));
  display: flex;
  flex-direction: column;
}

.bar {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 14px 8px;
  background: var(--surface);
  border-bottom: 1px solid var(--line);
}

.where {
  flex: 1;
  min-width: 0;
}

.field-name {
  font-size: 18px;
  display: flex;
  align-items: center;
  gap: 4px;
}

.trial-name {
  margin-top: 2px;
  font-size: 12.5px;
  color: var(--ink-2);
  display: flex;
  align-items: center;
  gap: 3px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.tools {
  display: flex;
  gap: 6px;
}

.view-switch {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 14px;
}

.view-switch button {
  height: 30px;
  padding: 0 12px;
  border-radius: 15px;
  border: 1px solid var(--line-strong);
  background: var(--surface);
  font: inherit;
  font-size: 13px;
  color: var(--ink-2);
}

.view-switch button.on {
  background: var(--forest);
  border-color: var(--forest);
  color: #fbf3df;
}

.legend {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 11.5px;
  color: var(--ink-3);
}

.d {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  margin-left: 6px;
}

.d.today {
  background: var(--green);
}

.d.unsynced {
  background: #e08a2c;
}

.picker {
  flex: 1;
  min-height: 0;
  margin: 0 0 0 4px;
  padding-top: 0;
  background: #e9e1cd url('@/assets/art/soil.jpg') repeat;
  background-size: 256px;
  border-radius: 14px 0 0 0;
  padding: 0 0 80px;
}

.loading {
  margin-top: 80px;
}

.action {
  position: fixed;
  left: 10px;
  right: 10px;
  bottom: calc(64px + env(safe-area-inset-bottom));
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 8px 8px 14px;
  border-radius: 16px;
  background: rgba(255, 253, 248, 0.96);
  border: 1px solid var(--line-strong);
  box-shadow: var(--shadow-lift);
  z-index: 10;
}

.action.active {
  background: linear-gradient(180deg, #24513d, #1a3e2e);
  border-color: rgba(226, 182, 90, 0.35);
  color: #fbf3df;
}

.action.active .van-button--plain {
  background: rgba(255, 255, 255, 0.1);
  border-color: rgba(226, 182, 90, 0.35);
  color: #fbf3df;
}

.sel {
  flex: 1;
  font-size: 14px;
}

.sel b {
  font-family: var(--font-serif);
  font-size: 20px;
  color: var(--wheat-bright);
}

.go {
  padding: 0 18px;
}

.tip {
  flex: 1;
  font-size: 12.5px;
  color: var(--ink-3);
}
</style>
