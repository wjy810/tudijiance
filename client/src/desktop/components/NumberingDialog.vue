<script setup lang="ts">
// 给选中的小区批量编号，先预览再保存
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'

import { fieldApi, type Plot } from '@/shared/api'
import { NUMBERING_RULES, defaultOptions, generateCodes, type NumberingRule } from '@/shared/numbering'

const props = defineProps<{ fieldId: number; plots: Plot[] }>()
const visible = defineModel<boolean>({ required: true })
const emit = defineEmits<{ saved: [plots: Plot[]] }>()

const options = reactive(defaultOptions('ROW'))
const saving = ref(false)

watch(visible, (open) => {
  if (open) {
    Object.assign(options, defaultOptions(options.rule))
  }
})

function changeRule(rule: string | number | boolean | undefined) {
  Object.assign(options, defaultOptions(rule as NumberingRule))
}

const preview = computed(() => {
  const codes = generateCodes(props.plots.map((p) => ({ id: p.id, rowNo: p.rowNo, colNo: p.colNo })), options)
  return [...props.plots]
    .sort((a, b) => a.rowNo - b.rowNo || a.colNo - b.colNo)
    .map((p) => ({ plot: p, code: codes.get(p.id) ?? '' }))
})

const rows = computed(() => {
  const byRow = new Map<number, { plot: Plot; code: string }[]>()
  for (const item of preview.value) {
    const list = byRow.get(item.plot.rowNo) ?? []
    list.push(item)
    byRow.set(item.plot.rowNo, list)
  }
  return [...byRow.entries()]
})

async function save() {
  const codes: Record<number, string> = {}
  for (const { plot, code } of preview.value) {
    codes[plot.id] = code
  }
  saving.value = true
  try {
    const saved = await fieldApi.updateCodes(props.fieldId, codes)
    ElMessage.success(`已给 ${preview.value.length} 个小区编号`)
    visible.value = false
    emit('saved', saved)
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    saving.value = false
  }
}

async function clearCodes() {
  const codes: Record<number, string> = {}
  for (const p of props.plots) {
    codes[p.id] = ''
  }
  saving.value = true
  try {
    const saved = await fieldApi.updateCodes(props.fieldId, codes)
    ElMessage.success('已清除编号')
    visible.value = false
    emit('saved', saved)
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <el-dialog v-model="visible" title="批量编号" width="680px">
    <el-form label-width="80px">
      <el-form-item label="规则">
        <el-radio-group :model-value="options.rule" @update:model-value="changeRule">
          <el-radio-button v-for="r in NUMBERING_RULES" :key="r.value" :value="r.value">{{ r.label }}</el-radio-button>
        </el-radio-group>
        <div class="hint full">{{ NUMBERING_RULES.find((r) => r.value === options.rule)?.example }}</div>
      </el-form-item>
      <el-form-item v-if="options.rule !== 'ROW_COL'" label="方向">
        <el-radio-group v-model="options.direction">
          <el-radio-button value="ROWS">按行</el-radio-button>
          <el-radio-button value="COLUMNS">按列</el-radio-button>
        </el-radio-group>
      </el-form-item>
      <el-form-item label="前缀">
        <el-input v-model="options.prefix" maxlength="10" class="short" placeholder="可不填" />
      </el-form-item>
      <el-form-item v-if="options.rule !== 'ROW_COL'" label="起始号">
        <el-input-number v-model="options.start" :min="0" :max="99999" />
        <span class="label-gap">补零到</span>
        <el-input-number v-model="options.pad" :min="0" :max="6" />
        <span class="label-gap">位</span>
      </el-form-item>
    </el-form>
    <div class="section-title">预览（{{ preview.length }} 个小区）</div>
    <div class="preview">
      <div v-for="[rowNo, items] in rows" :key="rowNo" class="preview-row">
        <span class="row-no muted">第 {{ rowNo }} 行</span>
        <span v-for="i in items" :key="i.plot.id" class="code">{{ i.code }}</span>
      </div>
    </div>
    <template #footer>
      <el-button text type="danger" class="left" :loading="saving" @click="clearCodes">清除编号</el-button>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="save">保存编号</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.full {
  width: 100%;
}

.short {
  width: 140px;
}

.label-gap {
  margin: 0 8px;
  color: var(--app-text-secondary);
}

.section-title {
  font-weight: 600;
  margin: 4px 0 8px;
}

.preview {
  max-height: 240px;
  overflow: auto;
  background: #f7f9f7;
  border: 1px solid var(--app-border);
  border-radius: 6px;
  padding: 8px 10px;
}

.preview-row {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
  padding: 3px 0;
}

.row-no {
  width: 56px;
  font-size: 12px;
}

.code {
  background: #fff;
  border: 1px solid var(--app-border);
  border-radius: 4px;
  padding: 1px 6px;
  font-family: Consolas, 'Courier New', monospace;
}

.left {
  float: left;
}
</style>
