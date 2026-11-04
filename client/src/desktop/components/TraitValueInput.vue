<script setup lang="ts">
// 按项目类型显示输入控件：能选的都做成按钮选择，只有数值和文字才需要打字
import { computed } from 'vue'

import type { TraitValue, TraitView } from '@/shared/api'
import { rangeText, rangeWarning } from '@/shared/quality'
import { today } from '@/shared/traitValue'

const props = defineProps<{ trait: TraitView }>()
const value = defineModel<TraitValue>({ required: true })

const def = computed(() => props.trait.trait)
const options = computed(() => props.trait.options)
const unit = computed(() => (def.value.unit && def.value.unit !== '级' ? def.value.unit : ''))
const step = computed(() => (def.value.precisionDigits ? 10 ** -def.value.precisionDigits : 1))
/** 超出常见范围时的提醒，只提醒、不拦着，真有这种情况照样能存 */
const warning = computed(() => rangeWarning(def.value, value.value.valueNum))
const range = computed(() => rangeText(def.value))

function setNum(v: number | null | undefined) {
  value.value = { ...value.value, valueNum: v ?? null }
}

function setText(v: string | null) {
  value.value = { ...value.value, valueText: v }
}

function setDate(v: string | null) {
  value.value = { ...value.value, valueDate: v }
}

const disabledFuture = (d: Date) => d.getTime() > Date.now()

/** 选项按钮的值转成文字，没选中时为 null */
function setChoice(v: string | number | boolean | undefined) {
  setText(v === undefined || v === '' ? null : String(v))
}

function setInputText(v: string) {
  setText(v || null)
}

const hasStandards = computed(() => options.value.some((o) => !!o.description))

const gradeValue = computed({
  get: () => (value.value.valueNum === null ? '' : String(value.value.valueNum)),
  set: (code: string) => setNum(code === '' ? null : Number(code)),
})
</script>

<template>
  <div class="trait-input">
    <template v-if="def.dataType === 'DATE'">
      <el-date-picker
        :model-value="value.valueDate"
        type="date"
        value-format="YYYY-MM-DD"
        :disabled-date="disabledFuture"
        placeholder="选择日期"
        @update:model-value="setDate"
      />
      <el-button text size="small" @click="setDate(today())">今天</el-button>
    </template>

    <!-- 分级、带档位的数值：有分级标准的做成大块的牌子，级别下面直接写标准，点哪块选哪级 -->
    <template v-else-if="def.dataType === 'GRADE' || (def.dataType === 'NUMBER' && options.length > 0)">
      <div v-if="hasStandards" class="grade-tiles">
        <button
          v-for="o in options"
          :key="o.id"
          type="button"
          class="grade-tile"
          :class="{ active: gradeValue === o.valueCode }"
          @click="gradeValue = o.valueCode"
        >
          <span class="grade-no">{{ def.dataType === 'GRADE' ? `${o.valueCode} 级` : o.label }}</span>
          <span v-if="def.dataType === 'GRADE' && o.label !== o.valueCode && !o.label.startsWith(o.valueCode)" class="grade-name">{{ o.label }}</span>
          <span class="grade-desc">{{ o.description || '—' }}</span>
        </button>
      </div>
      <el-radio-group v-else v-model="gradeValue" class="choices">
        <el-radio-button v-for="o in options" :key="o.id" :value="o.valueCode">{{ o.label }}</el-radio-button>
      </el-radio-group>
    </template>

    <template v-else-if="def.dataType === 'NUMBER'">
      <el-input-number
        :model-value="value.valueNum ?? undefined"
        :precision="def.precisionDigits ?? undefined"
        :step="step"
        :min="0"
        controls-position="right"
        class="number"
        @update:model-value="setNum"
      />
      <span v-if="unit" class="unit">{{ unit }}</span>
      <span v-if="warning" class="range-warn" role="status">{{ warning }}</span>
      <span v-else-if="range" class="range-hint">常见 {{ range }}</span>
    </template>

    <el-radio-group
      v-else-if="def.dataType === 'OPTION'"
      :model-value="value.valueText ?? ''"
      class="choices"
      @update:model-value="setChoice"
    >
      <el-radio-button v-for="o in options" :key="o.id" :value="o.valueCode">{{ o.label }}</el-radio-button>
    </el-radio-group>

    <!-- 农事操作：选类型，再填用量（可以不填） -->
    <div v-else-if="def.dataType === 'OPERATION'" class="operation">
      <el-radio-group
        v-if="options.length > 0"
        :model-value="value.valueText ?? ''"
        class="choices"
        @update:model-value="setChoice"
      >
        <el-radio-button v-for="o in options" :key="o.id" :value="o.valueCode">{{ o.label }}</el-radio-button>
      </el-radio-group>
      <el-input
        v-else
        :model-value="value.valueText ?? ''"
        maxlength="200"
        placeholder="做了什么"
        @update:model-value="setInputText"
      />
      <div v-if="def.unit" class="amount">
        <span class="muted">用量</span>
        <el-input-number
          :model-value="value.valueNum ?? undefined"
          :precision="def.precisionDigits ?? 2"
          :min="0"
          controls-position="right"
          class="number"
          placeholder="可不填"
          @update:model-value="setNum"
        />
        <span class="unit">{{ def.unit }}</span>
      </div>
    </div>

    <el-input
      v-else
      :model-value="value.valueText ?? ''"
      type="textarea"
      :rows="2"
      maxlength="500"
      show-word-limit
      @update:model-value="setInputText"
    />
  </div>
</template>

<style scoped>
.trait-input {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  width: 100%;
}

.choices {
  flex-wrap: wrap;
  row-gap: 6px;
}

.number {
  width: 180px;
}

.grade-tiles {
  width: 100%;
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(120px, 1fr));
  gap: 8px;
}

.grade-tile {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  padding: 12px 10px 10px;
  border-radius: 12px;
  border: 1px solid var(--line-strong);
  background: linear-gradient(180deg, #fffefb, var(--surface-2));
  box-shadow: 0 1px 0 rgba(255, 255, 255, 0.8) inset, 0 2px 6px -4px rgba(70, 52, 20, 0.3);
  font: inherit;
  color: var(--ink);
  cursor: pointer;
  text-align: center;
  transition: transform 0.12s, box-shadow 0.12s, background 0.12s;
}

.grade-tile:hover {
  border-color: #d9c48f;
  transform: translateY(-1px);
}

.grade-no {
  font-family: var(--font-serif);
  font-weight: 700;
  font-size: 18px;
  color: var(--forest);
}

.grade-name {
  font-size: 12.5px;
  font-weight: 600;
  color: var(--ink-2);
}

.grade-desc {
  font-size: 12px;
  line-height: 1.5;
  color: var(--ink-3);
}

.grade-tile.active {
  background: linear-gradient(180deg, #2b6449, #1e4836);
  border-color: #1c4231;
  box-shadow: 0 0 0 3px rgba(226, 182, 90, 0.35), 0 8px 18px -8px rgba(28, 66, 49, 0.7);
}

.grade-tile.active .grade-no {
  color: #f6e1a8;
}

.grade-tile.active .grade-name,
.grade-tile.active .grade-desc {
  color: rgba(251, 243, 223, 0.85);
}

.unit {
  color: var(--app-text-secondary);
}

.range-hint {
  font-size: 12px;
  color: var(--ink-3);
}

.range-warn {
  font-size: 12.5px;
  padding: 3px 9px;
  border-radius: 999px;
  color: #8a5a10;
  background: #fbefd2;
  border: 1px solid #ecd39a;
}

.operation {
  display: flex;
  flex-direction: column;
  gap: 10px;
  width: 100%;
}

.amount {
  display: flex;
  align-items: center;
  gap: 8px;
}
</style>
