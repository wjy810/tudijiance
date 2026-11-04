<script setup lang="ts">
// 录入：选项目 → 填值 → 保存。数据先存在手机上，有网就马上上传。
// 选了多个小区时可以"同一个值"一次录完（例如同一天施肥），数值项目也可以"逐个小区"一个个录（例如株高）
import { computed, ref, watch } from 'vue'
import { showToast } from 'vant'

import type { Plot, TraitValue, TraitView } from '@/shared/api'
import { TRAIT_CATEGORY, TRAIT_DATA_TYPE, replicateLabel } from '@/shared/labels'
import { emptyValue, missingValue, newUuid, normalizeValue, observedOnFor, today } from '@/shared/traitValue'
import { useAuthStore } from '@/stores/auth'
import { OFFLINE_EDITION } from '@/shared/edition'
import { useFieldStore } from '../stores/field'
import { useSyncStore } from '../stores/sync'
import { displayValue, plotCode } from '../display'
import ValueEditor from './ValueEditor.vue'
import NumberPad from './NumberPad.vue'

const show = defineModel<boolean>('show', { required: true })
const props = defineProps<{ plots: Plot[] }>()
const emit = defineEmits<{ saved: [count: number] }>()

const auth = useAuthStore()
const field = useFieldStore()
const sync = useSyncStore()

type Step = 'trait' | 'value'
const step = ref<Step>('trait')
const trait = ref<TraitView | null>(null)
const value = ref<TraitValue>(emptyValue())
const observedOn = ref(today())
const note = ref('')
const mode = ref<'same' | 'each'>('same')
const keyword = ref('')
const saving = ref(false)

// 小区按行、列排好，"逐个小区"时按这个顺序走
const ordered = computed(() => [...props.plots].sort((a, b) => a.rowNo - b.rowNo || a.colNo - b.colNo))

watch(show, (open) => {
  if (open) {
    step.value = 'trait'
    trait.value = null
    keyword.value = ''
    note.value = ''
    observedOn.value = today()
  }
})

// ---------- 选项目 ----------

const categories = computed(() => [...field.entryGroups.entries()].map(([c, items]) => ({ key: c, label: TRAIT_CATEGORY[c], items })))
const activeTab = ref(0)
const searchHits = computed(() => {
  const kw = keyword.value.trim()
  if (!kw) return []
  return [...field.entryGroups.values()].flat().filter((t) => t.trait.name.includes(kw))
})

function traitMeta(t: TraitView): string {
  const type = TRAIT_DATA_TYPE[t.trait.dataType]
  return t.trait.unit && t.trait.unit !== '级' ? `${type} · ${t.trait.unit}` : type
}

/** 数值项目、选了多个小区时才有"逐个小区" */
const canEach = computed(() => !!trait.value && props.plots.length > 1
  && trait.value.trait.dataType === 'NUMBER' && trait.value.options.length === 0)

function pick(t: TraitView) {
  trait.value = t
  value.value = emptyValue()
  if (t.trait.dataType === 'DATE') {
    value.value = { ...emptyValue(), valueDate: today() }
  }
  mode.value = 'same'
  eachValues.value = new Map()
  eachIndex.value = 0
  step.value = 'value'
}

// ---------- 逐个小区 ----------

const eachValues = ref(new Map<number, number | null>())
const eachIndex = ref(0)
const current = computed(() => ordered.value[eachIndex.value] ?? null)
const filledCount = computed(() => [...eachValues.value.values()].filter((v) => v !== null && v !== undefined).length)

function setEach(v: number | null) {
  if (!current.value) return
  eachValues.value.set(current.value.id, v)
  eachValues.value = new Map(eachValues.value)
}

function nextPlot() {
  if (eachIndex.value < ordered.value.length - 1) {
    eachIndex.value++
  } else {
    showToast(`${filledCount.value} 个小区填好了，点"保存"`)
  }
}

function plotInfo(p: Plot): string {
  const planting = field.plantingByPlot.get(p.id)
  const variety = planting?.varietyId ? field.varietyById.get(planting.varietyId)?.name : null
  const rep = planting?.replicateNo ? `重复 ${replicateLabel(planting.replicateNo)}` : ''
  return [variety ?? '未分配品种', rep].filter(Boolean).join(' · ')
}

// ---------- 保存 ----------

async function save(again: boolean) {
  const t = trait.value
  const uid = auth.user?.id
  if (!t || !uid || !field.trial || !field.fieldId) return
  const base = {
    userId: uid, fieldId: field.fieldId, trialId: field.trial.id, traitId: t.trait.id, traitName: t.trait.name,
    note: note.value.trim() || null,
  }
  saving.value = true
  try {
    let count = 0
    if (mode.value === 'each') {
      for (const p of ordered.value) {
        const n = eachValues.value.get(p.id)
        if (n === null || n === undefined) continue
        const v: TraitValue = { valueNum: n, valueText: null, valueDate: null }
        await sync.record({
          ...base, plots: [{ plotId: p.id, code: plotCode(p), clientUuid: newUuid() }], value: v,
          displayValue: displayValue(t, v), observedOn: observedOn.value,
        })
        count++
      }
      if (count === 0) {
        showToast('还没有填任何小区')
        return
      }
    } else {
      const v = normalizeValue(t.trait, value.value)
      const problem = missingValue(t, v)
      if (problem) {
        showToast(problem)
        return
      }
      await sync.record({
        ...base, plots: ordered.value.map((p) => ({ plotId: p.id, code: plotCode(p), clientUuid: newUuid() })), value: v,
        displayValue: displayValue(t, v), observedOn: observedOnFor(t.trait, v, observedOn.value),
      })
      count = ordered.value.length
    }
    field.rememberTrait(t.trait.id)
    await field.refreshUnsynced()
    if (OFFLINE_EDITION) await field.loadTrial()
    showToast({ type: 'success', message: OFFLINE_EDITION ? `已存本机：${count} 个小区` : sync.online ? `已保存 ${count} 个小区` : `已存到手机上，联网后自动上传` })
    emit('saved', count)
    if (again) {
      step.value = 'trait'
      trait.value = null
    } else {
      show.value = false
    }
  } catch (e) {
    showToast({ message: `保存失败：${(e as Error).message}`, wordBreak: 'break-all' })
  } finally {
    saving.value = false
  }
}

// ---------- 调查日期 ----------
const dateOpen = ref(false)
const datePick = ref<string[]>([])
const minDate = new Date(new Date().getFullYear() - 5, 0, 1)
const maxDate = new Date()
function openDate() {
  datePick.value = observedOn.value.split('-')
  dateOpen.value = true
}
</script>

<template>
  <van-popup v-model:show="show" position="bottom" round closeable :style="{ height: '94%' }" class="sheet" teleport="body">
    <div class="wrap">
      <header class="head">
        <div class="head-title m-title">
          <template v-if="step === 'trait'">录入 · 选项目</template>
          <template v-else>{{ trait?.trait.name }}</template>
        </div>
        <div class="plots">
          给 <b>{{ plots.length }}</b> 个小区：
          <span v-for="p in ordered.slice(0, 6)" :key="p.id" class="plot-chip">{{ plotCode(p) }}</span>
          <span v-if="plots.length > 6" class="m-muted">等</span>
        </div>
      </header>

      <!-- 选项目 -->
      <div v-if="step === 'trait'" class="body">
        <van-search v-model="keyword" placeholder="找项目，例如：株高" shape="round" class="search" />
        <template v-if="keyword.trim()">
          <div class="grid pad">
            <button v-for="t in searchHits" :key="t.trait.id" type="button" class="trait" @click="pick(t)">
              <b>{{ t.trait.name }}</b><span>{{ traitMeta(t) }}</span>
            </button>
          </div>
          <div v-if="searchHits.length === 0" class="m-muted none">没有找到</div>
        </template>
        <template v-else>
          <div v-if="field.recentTraits.length" class="recent pad">
            <div class="sub-title">最近录过</div>
            <div class="recent-list">
              <button v-for="t in field.recentTraits" :key="t.trait.id" type="button" class="recent-btn" @click="pick(t)">{{ t.trait.name }}</button>
            </div>
          </div>
          <van-tabs v-model:active="activeTab" swipeable shrink sticky :offset-top="0" class="tabs">
            <van-tab v-for="c in categories" :key="c.key" :title="c.label">
              <div class="grid pad">
                <button v-for="t in c.items" :key="t.trait.id" type="button" class="trait" :class="{ important: t.trait.important }" @click="pick(t)">
                  <i v-if="t.trait.important" class="star" aria-label="重要">★</i>
                  <b>{{ t.trait.name }}</b><span>{{ traitMeta(t) }}</span>
                </button>
              </div>
            </van-tab>
          </van-tabs>
        </template>
      </div>

      <!-- 填值 -->
      <div v-else-if="trait" class="body pad">
        <div class="trait-head">
          <van-button size="small" icon="arrow-left" plain round @click="step = 'trait'">换项目</van-button>
          <span v-if="trait.trait.surveyTime" class="m-muted hint">{{ trait.trait.surveyTime }}</span>
        </div>
        <div v-if="trait.trait.description" class="book-note">{{ trait.trait.description }}</div>

        <van-radio-group v-if="canEach" v-model="mode" direction="horizontal" class="mode">
          <van-radio name="same">所有小区同一个值</van-radio>
          <van-radio name="each">逐个小区录</van-radio>
        </van-radio-group>

        <template v-if="mode === 'same'">
          <ValueEditor v-model="value" :trait="trait" />
        </template>
        <template v-else-if="current">
          <div class="each-card m-card">
            <div class="each-progress num">{{ eachIndex + 1 }} / {{ ordered.length }}</div>
            <div class="each-code serif">{{ plotCode(current) }}</div>
            <div class="m-muted">{{ plotInfo(current) }}</div>
          </div>
          <NumberPad :key="current.id" :model-value="eachValues.get(current.id) ?? null"
            :unit="trait.trait.unit" :precision="trait.trait.precisionDigits" autofocus done-text="下一个"
            @update:model-value="setEach" @done="nextPlot" />
          <div class="each-list">
            <button v-for="(p, i) in ordered" :key="p.id" type="button" class="each-chip"
              :class="{ now: i === eachIndex, filled: eachValues.get(p.id) != null }" @click="eachIndex = i">
              <b>{{ plotCode(p) }}</b><span class="num">{{ eachValues.get(p.id) ?? '—' }}</span>
            </button>
          </div>
        </template>

        <van-cell-group inset class="meta">
          <van-cell v-if="trait.trait.dataType !== 'DATE'" title="调查日期" :value="observedOn" is-link @click="openDate" />
          <van-field v-model="note" label="备注" placeholder="可不填" maxlength="500" />
        </van-cell-group>
      </div>

      <footer v-if="step === 'value'" class="foot">
        <van-button round plain type="primary" :loading="saving" @click="save(true)">保存，再录一项</van-button>
        <van-button round class="btn-wheat" :loading="saving" @click="save(false)">
          {{ mode === 'each' ? `保存 ${filledCount} 个值` : '保存' }}
        </van-button>
      </footer>
    </div>

    <van-popup v-model:show="dateOpen" position="bottom" round teleport="body">
      <van-date-picker v-model="datePick" title="调查日期" :min-date="minDate" :max-date="maxDate"
        @confirm="({ selectedValues }: { selectedValues: string[] }) => { observedOn = selectedValues.join('-'); dateOpen = false }"
        @cancel="dateOpen = false" />
    </van-popup>
  </van-popup>
</template>

<style scoped>
.wrap {
  height: 100%;
  display: flex;
  flex-direction: column;
}

.head {
  padding: 18px 18px 10px;
  border-bottom: 1px solid var(--line);
}

.head-title {
  font-size: 19px;
  padding-right: 32px;
}

.plots {
  margin-top: 6px;
  font-size: 13px;
  color: var(--ink-2);
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  align-items: center;
}

.plots b {
  color: var(--forest);
}

.plot-chip {
  padding: 1px 7px;
  border-radius: 8px;
  background: var(--surface-2);
  border: 1px solid var(--line);
  font-size: 12px;
}

.body {
  flex: 1;
  overflow-y: auto;
  -webkit-overflow-scrolling: touch;
}

.pad {
  padding: 12px 14px;
}

.search {
  padding: 10px 14px 4px;
  background: transparent;
}

.sub-title {
  font-size: 13px;
  color: var(--ink-3);
  margin-bottom: 8px;
}

.recent-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.recent-btn {
  height: 38px;
  padding: 0 14px;
  border-radius: 19px;
  border: 1px solid #ead5a8;
  background: var(--wheat-soft);
  color: #6b5216;
  font: inherit;
  font-size: 14px;
  font-weight: 600;
}

.grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.trait {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 3px;
  min-height: 62px;
  padding: 10px 12px;
  border-radius: 14px;
  border: 1px solid var(--line-strong);
  background: linear-gradient(180deg, #fffefb, var(--surface-2));
  font: inherit;
  text-align: left;
}

.trait b {
  font-size: 15.5px;
  color: var(--forest);
}

.trait span {
  font-size: 12px;
  color: var(--ink-3);
}

.none {
  text-align: center;
  padding: 30px;
}

.trait-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
}

.hint {
  flex: 1;
  font-size: 12.5px;
  line-height: 1.5;
}

.mode {
  margin-bottom: 14px;
  padding: 10px 12px;
  border-radius: 12px;
  background: var(--surface-2);
  border: 1px solid var(--line);
}

.each-card {
  text-align: center;
  padding: 14px;
  margin-bottom: 12px;
}

.each-progress {
  font-size: 12.5px;
  color: var(--wheat);
}

.each-code {
  font-size: 34px;
  font-weight: 700;
  color: var(--forest);
}

.each-list {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 12px;
}

.each-chip {
  display: flex;
  flex-direction: column;
  align-items: center;
  min-width: 56px;
  padding: 5px 6px;
  border-radius: 10px;
  border: 1px solid var(--line);
  background: var(--surface);
  font: inherit;
  font-size: 12px;
  color: var(--ink-3);
}

.each-chip b {
  color: var(--ink);
}

.each-chip.filled {
  background: var(--green-soft);
  border-color: #c9dccf;
  color: var(--green);
}

.each-chip.now {
  border-color: var(--wheat);
  box-shadow: 0 0 0 2px rgba(226, 182, 90, 0.5);
}

.meta {
  margin: 16px 0 0;
  border: 1px solid var(--line);
}

.foot {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
  padding: 10px 14px calc(10px + env(safe-area-inset-bottom));
  border-top: 1px solid var(--line);
  background: var(--surface);
}

.book-note {
  margin: -4px 0 10px;
  padding: 6px 10px;
  border-radius: 8px;
  background: var(--wheat-wash);
  color: var(--wheat-ink);
  font-size: 12.5px;
  line-height: 1.5;
}

.trait {
  position: relative;
}

.trait .star {
  position: absolute;
  top: 6px;
  right: 9px;
  font-style: normal;
  font-size: 13px;
  color: var(--wheat);
}
</style>
