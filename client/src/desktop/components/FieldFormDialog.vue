<script setup lang="ts">
// 新建或修改实验田
import { reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'

import { fieldApi, type Field, type FieldDetail, type FieldRequest } from '@/shared/api'

const props = defineProps<{ field?: Field | null }>()
const visible = defineModel<boolean>({ required: true })
const emit = defineEmits<{ saved: [detail: FieldDetail] }>()

function emptyForm(): FieldRequest {
  return {
    name: '', location: null, latitude: null, longitude: null, altitudeM: null, areaMu: null,
    rowCount: 6, colCount: 10, soilType: null, irrigated: true, previousCrop: null, remark: null,
    plotLengthM: 6.67, plotWidthM: 2,
  }
}

const form = reactive<FieldRequest>(emptyForm())
const saving = ref(false)

watch(visible, (open) => {
  if (open) {
    Object.assign(form, props.field ? { ...props.field, plotLengthM: null, plotWidthM: null } : emptyForm())
  }
})

async function save() {
  if (!form.name.trim()) {
    ElMessage.warning('请填写实验田名称')
    return
  }
  saving.value = true
  try {
    const detail = props.field ? await fieldApi.update(props.field.id, form) : await fieldApi.create(form)
    ElMessage.success('已保存')
    visible.value = false
    emit('saved', detail)
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <el-dialog v-model="visible" :title="field ? '修改实验田' : '新建实验田'" width="560px">
    <el-form label-width="96px">
      <el-form-item label="名称" required>
        <el-input v-model="form.name" maxlength="100" placeholder="例如：东区品种试验田" />
      </el-form-item>
      <el-form-item label="网格" required>
        <el-input-number v-model="form.rowCount" :min="1" :max="100" />
        <span class="sep">行 ×</span>
        <el-input-number v-model="form.colCount" :min="1" :max="100" />
        <span class="sep">列</span>
      </el-form-item>
      <div v-if="field" class="hint grid-hint">
        缩小网格会删掉网格外的小区；已经分配了品种的小区不能删。
      </div>
      <el-form-item :label="field ? '新小区尺寸' : '小区尺寸'">
        <span class="sep first">长</span>
        <el-input-number v-model="form.plotLengthM" :min="0.1" :max="200" :precision="2" :controls="false" class="dim" />
        <span class="sep">m × 宽</span>
        <el-input-number v-model="form.plotWidthM" :min="0.1" :max="200" :precision="2" :controls="false" class="dim" />
        <span class="sep">m</span>
      </el-form-item>
      <div class="hint grid-hint">
        {{ field ? '扩大网格时新生成的小区用这个尺寸，不填就不设。' : '标准小区面积 13.33 m²（例如 6.67 m × 2 m）。' }}
        单个小区的尺寸以后在平面图上选中后可以再改。折合亩产按小区面积计算。
      </div>
      <el-form-item label="地点">
        <el-input v-model="form.location" maxlength="200" />
      </el-form-item>
      <el-form-item label="经纬度">
        <el-input-number v-model="form.latitude" :precision="6" :controls="false" placeholder="纬度" />
        <span class="sep" />
        <el-input-number v-model="form.longitude" :precision="6" :controls="false" placeholder="经度" />
      </el-form-item>
      <el-form-item label="海拔（m）">
        <el-input-number v-model="form.altitudeM" :precision="1" :controls="false" />
      </el-form-item>
      <el-form-item label="面积（亩）">
        <el-input-number v-model="form.areaMu" :precision="2" :min="0" :controls="false" />
      </el-form-item>
      <el-form-item label="土质">
        <el-input v-model="form.soilType" maxlength="50" placeholder="例如：壤土" />
      </el-form-item>
      <el-form-item label="水地/旱地">
        <el-radio-group v-model="form.irrigated">
          <el-radio :value="true">水地</el-radio>
          <el-radio :value="false">旱地</el-radio>
        </el-radio-group>
      </el-form-item>
      <el-form-item label="前茬作物">
        <el-input v-model="form.previousCrop" maxlength="50" placeholder="例如：玉米" />
      </el-form-item>
      <el-form-item label="备注">
        <el-input v-model="form.remark" type="textarea" maxlength="500" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="save">保存</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.sep {
  margin: 0 8px;
  color: var(--app-text-secondary);
}

.sep.first {
  margin-left: 0;
}

.dim {
  width: 100px;
}

.grid-hint {
  margin: -10px 0 12px 96px;
}
</style>
