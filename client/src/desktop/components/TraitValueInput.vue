<script setup lang="ts">
// 按项目类型显示输入控件：能选的都做成按钮选择，只有数值和文字才需要打字
import { computed } from 'vue'

import type { TraitValue, TraitView } from '@/shared/api'
import { today } from '@/shared/traitValue'

const props = defineProps<{ trait: TraitView }>()
const value = defineModel<TraitValue>({ required: true })

const def = computed(() => props.trait.trait)
const options = computed(() => props.trait.options)
const unit = computed(() => (def.value.unit && def.value.unit !== '级' ? def.value.unit : ''))
const step = computed(() => (def.value.precisionDigits ? 10 ** -def.value.precisionDigits : 1))

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

    <!-- 分级、带档位的数值：按钮选择；有分级标准的，标准直接列在下面，点哪一条也能选 -->
    <template v-else-if="def.dataType === 'GRADE' || (def.dataType === 'NUMBER' && options.length > 0)">
      <el-radio-group v-model="gradeValue" class="choices">
        <el-radio-button v-for="o in options" :key="o.id" :value="o.valueCode">{{ o.label }}</el-radio-button>
      </el-radio-group>
      <div v-if="hasStandards" class="standards">
        <div class="std-title">分级标准</div>
        <button
          v-for="o in options"
          :key="o.id"
          type="button"
          class="std-row"
          :class="{ active: gradeValue === o.valueCode }"
          @click="gradeValue = o.valueCode"
        >
          <span class="std-label">{{ o.label }}</span>
          <span class="std-desc">{{ o.description || '—' }}</span>
        </button>
      </div>
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

.standards {
  width: 100%;
  border: 1px solid var(--app-border);
  border-radius: 8px;
  overflow: hidden;
  margin-top: 4px;
}

.std-title {
  font-size: 12px;
  color: var(--app-text-secondary);
  background: #f6f8f5;
  padding: 6px 12px;
}

.std-row {
  display: grid;
  grid-template-columns: 96px 1fr;
  gap: 10px;
  width: 100%;
  text-align: left;
  border: none;
  border-top: 1px solid var(--app-border);
  background: #fff;
  padding: 7px 12px;
  font-size: 13px;
  line-height: 1.5;
  color: var(--app-text);
  cursor: pointer;
  font-family: inherit;
}

.std-row:hover {
  background: #f6faf7;
}

.std-row.active {
  background: var(--app-accent-soft);
}

.std-row.active .std-label {
  color: var(--app-accent);
  font-weight: 600;
}

.std-desc {
  color: var(--app-text-secondary);
}

.unit {
  color: var(--app-text-secondary);
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
