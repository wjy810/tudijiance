<script setup lang="ts">
// 批量改小区块的类型和尺寸。只改填了的项，不填的保持原样
import { reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'

import { fieldApi, type Plot, type PlotKind, type PlotPatch } from '@/shared/api'
import { PLOT_KIND } from '@/shared/labels'

const props = defineProps<{
  fieldId: number
  plots: Plot[]
  /** 按区块布局管理的地：小区块大小由布局决定，这里不改 */
  layoutSaved?: boolean
}>()
const visible = defineModel<boolean>({ required: true })
const emit = defineEmits<{ saved: [plots: Plot[]] }>()

const form = reactive<{
  kind: PlotKind | null
  lengthM: number | undefined
  widthM: number | undefined
  plantingRows: number | undefined
  rowSpacingCm: number | undefined
}>({ kind: null, lengthM: undefined, widthM: undefined, plantingRows: undefined, rowSpacingCm: undefined })
const saving = ref(false)

/** 选中的小区块这一项都一样时显示出来，不一样时留空 */
function common<T>(pick: (p: Plot) => T | null): T | undefined {
  const values = new Set(props.plots.map(pick))
  return values.size === 1 ? ([...values][0] ?? undefined) : undefined
}

watch(visible, (open) => {
  if (open) {
    form.kind = common((p) => p.kind) ?? null
    form.lengthM = common((p) => p.lengthM)
    form.widthM = common((p) => p.widthM)
    form.plantingRows = common((p) => p.plantingRows)
    form.rowSpacingCm = common((p) => p.rowSpacingCm)
  }
})

async function save() {
  const patch: PlotPatch = { plotIds: props.plots.map((p) => p.id) }
  if (form.kind) patch.kind = form.kind
  if (form.lengthM && !props.layoutSaved) patch.lengthM = form.lengthM
  if (form.widthM && !props.layoutSaved) patch.widthM = form.widthM
  if (form.plantingRows) patch.plantingRows = form.plantingRows
  if (form.rowSpacingCm) patch.rowSpacingCm = form.rowSpacingCm
  if (Object.keys(patch).length === 1) {
    ElMessage.warning('没有要修改的内容')
    return
  }
  saving.value = true
  try {
    const saved = await fieldApi.patchPlots(props.fieldId, patch)
    ElMessage.success(`已修改 ${props.plots.length} 个小区块`)
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
  <el-dialog v-model="visible" title="小区块设置" width="520px">
    <div class="hint top">给选中的 {{ plots.length }} 个小区块设置。留空的项不修改。</div>
    <el-form label-width="88px">
      <el-form-item label="类型">
        <el-radio-group v-model="form.kind">
          <el-radio-button v-for="(label, key) in PLOT_KIND" :key="key" :value="key as PlotKind">{{ label }}</el-radio-button>
        </el-radio-group>
        <div class="hint full">保护区、道路、空地不种试验品种，也不录入数据。</div>
      </el-form-item>
      <el-form-item v-if="layoutSaved" label="长 × 宽">
        <span class="muted">
          {{ form.lengthM && form.widthM ? `${form.lengthM} m × ${form.widthM} m，` : '' }}由区块布局决定，在“编辑地块”里改
        </span>
      </el-form-item>
      <el-form-item v-else label="长 × 宽">
        <el-input-number v-model="form.lengthM" :min="0.1" :max="200" :precision="2" :controls="false" class="dim" />
        <span class="sep">×</span>
        <el-input-number v-model="form.widthM" :min="0.1" :max="200" :precision="2" :controls="false" class="dim" />
        <span class="sep">m</span>
        <span v-if="form.lengthM && form.widthM" class="muted">面积 {{ (form.lengthM * form.widthM).toFixed(2) }} m²</span>
      </el-form-item>
      <el-form-item label="种植行数">
        <el-input-number v-model="form.plantingRows" :min="1" :max="100" :controls="false" class="dim" />
        <span class="sep">行</span>
      </el-form-item>
      <el-form-item label="行距">
        <el-input-number v-model="form.rowSpacingCm" :min="1" :max="200" :precision="1" :controls="false" class="dim" />
        <span class="sep">cm</span>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="save">保存</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.top {
  margin-bottom: 12px;
}

.full {
  width: 100%;
}

.dim {
  width: 110px;
}

.sep {
  margin: 0 8px;
  color: var(--app-text-secondary);
}
</style>
