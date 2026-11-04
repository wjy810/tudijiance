<script setup lang="ts">
// 按项目类型填值：分级是带标准的大牌子，选项是大按钮，数字用自带键盘，日期能一点选"今天""昨天"
import { computed, ref } from 'vue'

import type { TraitValue, TraitView } from '@/shared/api'
import { rangeText, rangeWarning } from '@/shared/quality'
import { today } from '@/shared/traitValue'
import NumberPad from './NumberPad.vue'

const props = defineProps<{ trait: TraitView; modelValue: TraitValue }>()
const emit = defineEmits<{ 'update:modelValue': [value: TraitValue] }>()

const def = computed(() => props.trait.trait)
const options = computed(() => props.trait.options)
const hasStandards = computed(() => options.value.some((o) => o.description))
const unit = computed(() => (def.value.unit && def.value.unit !== '级' ? def.value.unit : null))

/** 超出常见范围只提醒一句，照样能存 */
const warning = computed(() => (def.value.dataType === 'NUMBER' ? rangeWarning(def.value, props.modelValue.valueNum) : null))
const range = computed(() => rangeText(def.value))

function set(patch: Partial<TraitValue>) {
  emit('update:modelValue', { ...props.modelValue, ...patch })
}

const grade = computed(() => (props.modelValue.valueNum === null ? null : String(props.modelValue.valueNum)))

// ---------- 日期 ----------
function shift(days: number): string {
  const d = new Date()
  d.setDate(d.getDate() - days)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}
const QUICK = [{ label: '今天', days: 0 }, { label: '昨天', days: 1 }, { label: '前天', days: 2 }]
const pickerOpen = ref(false)
const pickerValue = ref<string[]>([])
const minDate = new Date(new Date().getFullYear() - 5, 0, 1)
const maxDate = new Date()

function openPicker() {
  pickerValue.value = (props.modelValue.valueDate ?? today()).split('-')
  pickerOpen.value = true
}

function confirmPicker({ selectedValues }: { selectedValues: string[] }) {
  set({ valueDate: selectedValues.join('-') })
  pickerOpen.value = false
}
</script>

<template>
  <div class="editor">
    <!-- 日期 -->
    <template v-if="def.dataType === 'DATE'">
      <div class="date-big serif" @click="openPicker">{{ modelValue.valueDate ?? '选择日期' }}</div>
      <div class="chips">
        <button v-for="q in QUICK" :key="q.label" type="button" class="chip-btn"
          :class="{ on: modelValue.valueDate === shift(q.days) }" @click="set({ valueDate: shift(q.days) })">{{ q.label }}</button>
        <button type="button" class="chip-btn" @click="openPicker">其他日期</button>
      </div>
      <van-popup v-model:show="pickerOpen" position="bottom" round>
        <van-date-picker v-model="pickerValue" title="选择日期" :min-date="minDate" :max-date="maxDate"
          @confirm="confirmPicker" @cancel="pickerOpen = false" />
      </van-popup>
    </template>

    <!-- 分级、有档位的数值：带标准时做成大牌子 -->
    <template v-else-if="def.dataType === 'GRADE' || (def.dataType === 'NUMBER' && options.length > 0)">
      <div v-if="hasStandards" class="tiles">
        <button v-for="o in options" :key="o.id" type="button" class="tile" :class="{ on: grade === o.valueCode }"
          @click="set({ valueNum: Number(o.valueCode) })">
          <span class="tile-no serif">{{ def.dataType === 'GRADE' ? `${o.valueCode} 级` : o.label }}</span>
          <span class="tile-desc">{{ o.description || o.label }}</span>
        </button>
      </div>
      <div v-else class="buttons">
        <button v-for="o in options" :key="o.id" type="button" class="big-btn" :class="{ on: grade === o.valueCode }"
          @click="set({ valueNum: Number(o.valueCode) })">{{ o.label }}</button>
      </div>
    </template>

    <!-- 数值 -->
    <template v-else-if="def.dataType === 'NUMBER'">
      <NumberPad :model-value="modelValue.valueNum" :unit="unit"
        :precision="def.precisionDigits" autofocus @update:model-value="(v) => set({ valueNum: v })" />
      <div v-if="warning" class="range-warn" role="status">{{ warning }}</div>
      <div v-else-if="range" class="range-hint">常见范围 {{ range }}</div>
    </template>

    <!-- 选项、农事操作 -->
    <template v-else-if="def.dataType === 'OPTION' || def.dataType === 'OPERATION'">
      <div v-if="options.length" class="buttons">
        <button v-for="o in options" :key="o.id" type="button" class="big-btn" :class="{ on: modelValue.valueText === o.valueCode }"
          @click="set({ valueText: o.valueCode })">{{ o.label }}</button>
      </div>
      <van-field v-else :model-value="modelValue.valueText ?? ''" placeholder="做了什么" class="text"
        @update:model-value="(v: string) => set({ valueText: v })" />
      <div v-if="def.dataType === 'OPERATION' && def.unit" class="amount">
        <div class="amount-label">用量<span class="m-muted">（可不填）</span></div>
        <NumberPad :model-value="modelValue.valueNum" :unit="def.unit" :precision="def.precisionDigits ?? 2"
          @update:model-value="(v) => set({ valueNum: v })" />
      </div>
    </template>

    <!-- 文字 -->
    <van-field v-else :model-value="modelValue.valueText ?? ''" type="textarea" rows="3" autosize maxlength="500"
      show-word-limit placeholder="写下看到的情况" class="text" @update:model-value="(v: string) => set({ valueText: v })" />
  </div>
</template>

<style scoped>
.editor {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.range-hint {
  text-align: center;
  font-size: 13px;
  color: var(--ink-3);
}

.range-warn {
  text-align: center;
  font-size: 14px;
  padding: 8px 12px;
  border-radius: 12px;
  color: #8a5a10;
  background: #fbefd2;
  border: 1px solid #ecd39a;
}

.date-big {
  text-align: center;
  font-size: 30px;
  font-weight: 700;
  color: var(--forest);
  padding: 14px;
  border-radius: 14px;
  border: 1.5px solid var(--line-strong);
  background: #fffefb;
}

.chips {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.chip-btn {
  flex: 1;
  min-width: 70px;
  height: 42px;
  border-radius: 21px;
  border: 1px solid var(--line-strong);
  background: var(--surface);
  font: inherit;
  font-size: 15px;
  color: var(--ink);
}

.chip-btn.on {
  background: var(--forest);
  border-color: var(--forest);
  color: #fbf3df;
}

.tiles {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.tile {
  display: flex;
  align-items: center;
  gap: 14px;
  min-height: 60px;
  padding: 10px 14px;
  border-radius: 14px;
  border: 1.5px solid var(--line-strong);
  background: linear-gradient(180deg, #fffefb, var(--surface-2));
  font: inherit;
  text-align: left;
  color: var(--ink);
}

.tile-no {
  flex-shrink: 0;
  min-width: 56px;
  font-size: 20px;
  font-weight: 700;
  color: var(--forest);
}

.tile-desc {
  font-size: 13.5px;
  line-height: 1.5;
  color: var(--ink-2);
}

.tile.on {
  background: linear-gradient(180deg, #2b6449, #1e4836);
  border-color: #1c4231;
  box-shadow: 0 0 0 3px rgba(226, 182, 90, 0.4);
}

.tile.on .tile-no {
  color: #f6e1a8;
}

.tile.on .tile-desc {
  color: rgba(251, 243, 223, 0.88);
}

.buttons {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.big-btn {
  min-height: 54px;
  padding: 8px;
  border-radius: 14px;
  border: 1.5px solid var(--line-strong);
  background: linear-gradient(180deg, #fffefb, var(--surface-2));
  font: inherit;
  font-size: 16px;
  font-weight: 600;
  color: var(--forest);
}

.big-btn.on {
  background: linear-gradient(180deg, #2b6449, #1e4836);
  border-color: #1c4231;
  color: #fbf3df;
  box-shadow: 0 0 0 3px rgba(226, 182, 90, 0.4);
}

.text {
  border-radius: 14px;
  border: 1px solid var(--line-strong);
}

.amount-label {
  margin-bottom: 6px;
  font-size: 14px;
  color: var(--ink-2);
}
</style>
