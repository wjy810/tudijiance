<script setup lang="ts">
// 填数字：大号显示框 + 自带的数字键盘（带小数点），不用调出系统输入法
import { computed, ref, watch } from 'vue'

const props = withDefaults(defineProps<{
  modelValue: number | null
  unit?: string | null
  /** 最多几位小数 */
  precision?: number | null
  placeholder?: string
  /** 一出来就弹出键盘 */
  autofocus?: boolean
  /** 键盘右下角按钮的文字 */
  doneText?: string
}>(), { unit: null, precision: null, placeholder: '点这里输入', autofocus: false, doneText: '完成' })

const emit = defineEmits<{ 'update:modelValue': [value: number | null]; done: [] }>()

const text = ref(props.modelValue === null ? '' : String(props.modelValue))
const show = ref(props.autofocus)

watch(() => props.modelValue, (v) => {
  if (v === null && text.value !== '') text.value = ''
  else if (v !== null && Number(text.value) !== v) text.value = String(v)
})

watch(text, (t, old) => {
  // 只允许一个小数点，小数位不超过 precision
  const parts = t.split('.')
  const digits = props.precision ?? 4
  if (parts.length > 2 || (parts[1] && parts[1].length > digits) || (digits === 0 && t.includes('.'))) {
    text.value = old
    return
  }
  const n = t === '' || t === '.' ? null : Number(t)
  emit('update:modelValue', n !== null && Number.isFinite(n) ? n : null)
})

const shown = computed(() => text.value || '')
const extraKey = computed(() => ((props.precision ?? 1) > 0 ? '.' : ''))

function done() {
  show.value = false
  emit('done')
}

defineExpose({ open: () => (show.value = true), clear: () => (text.value = '') })
</script>

<template>
  <div class="pad">
    <div class="display" :class="{ active: show, empty: !shown }" @click="show = true">
      <span class="value num">{{ shown || placeholder }}</span>
      <span v-if="unit" class="unit">{{ unit }}</span>
    </div>
    <van-number-keyboard
      v-model="text"
      :show="show"
      theme="custom"
      :extra-key="extraKey"
      :close-button-text="doneText"
      :maxlength="10"
      safe-area-inset-bottom
      @close="done"
      @blur="show = false"
    />
  </div>
</template>

<style scoped>
.display {
  display: flex;
  align-items: baseline;
  justify-content: center;
  gap: 8px;
  min-height: 66px;
  padding: 10px 16px;
  border-radius: 14px;
  border: 1.5px solid var(--line-strong);
  background: #fffefb;
}

.display.active {
  border-color: var(--green);
  box-shadow: 0 0 0 3px rgba(46, 107, 79, 0.15);
}

.value {
  font-family: var(--font-serif);
  font-size: 34px;
  font-weight: 700;
  color: var(--forest);
}

.display.empty .value {
  font-family: var(--font-sans);
  font-size: 16px;
  font-weight: normal;
  color: var(--ink-3);
}

.unit {
  font-size: 15px;
  color: var(--ink-2);
}
</style>
