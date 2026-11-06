<script setup lang="ts">
// 新建或修改试验：一块实验田每年一个试验（也可以一年多个）
import { reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'

import { trialApi, type Trial, type TrialRequest, type TrialType } from '@/shared/api'
import { TRIAL_TYPE, TRIAL_TYPE_CHOICES, WHEAT_ZONES } from '@/shared/labels'

/** land：季里的地块，试验名称、年份跟着季和地块，只改类型、麦区、重复次数，不能删 */
const props = defineProps<{ fieldId: number; trial?: Trial | null; land?: boolean }>()
const visible = defineModel<boolean>({ required: true })
const emit = defineEmits<{ saved: [trial: Trial]; removed: [] }>()

function emptyForm(): TrialRequest {
  const year = new Date().getMonth() >= 8 ? new Date().getFullYear() + 1 : new Date().getFullYear()
  return {
    fieldId: props.fieldId, seasonYear: year, name: `${year - 1}—${year} 年度品种比较试验`,
    trialType: 'COMPARISON', wheatZone: null, replicateCount: 3, remark: null,
  }
}

const form = reactive<TrialRequest>(emptyForm())
const saving = ref(false)

watch(visible, (open) => {
  if (open) {
    Object.assign(form, props.trial ? { ...props.trial } : emptyForm())
  }
})

async function save() {
  if (!form.name.trim()) {
    ElMessage.warning('请填写试验名称')
    return
  }
  saving.value = true
  try {
    const saved = props.trial ? await trialApi.update(props.trial.id, form) : await trialApi.create(form)
    ElMessage.success('已保存')
    visible.value = false
    emit('saved', saved)
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    saving.value = false
  }
}

async function remove() {
  if (!props.trial) {
    return
  }
  try {
    await ElMessageBox.confirm(
      `删除"${props.trial.name}"会同时删除它的品种分配、对照关系和全部录入数据，不能恢复。确定删除？`,
      '删除试验', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  try {
    await trialApi.remove(props.trial.id)
    ElMessage.success('已删除')
    visible.value = false
    emit('removed')
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}
</script>

<template>
  <el-dialog v-model="visible" :title="land ? '试验信息' : trial ? '修改试验' : '新建试验'" width="560px">
    <el-form label-width="88px">
      <div v-if="land" class="hint land-note">这块地块这一季的试验：类型、麦区、重复次数用在记载本、统计和数据质量检查里。</div>
      <el-form-item v-if="!land" label="收获年份" required>
        <el-input-number v-model="form.seasonYear" :min="2000" :max="2100" />
        <span class="hint inline">按收获的年份算，2025 年秋播、2026 年收获的填 2026</span>
      </el-form-item>
      <el-form-item v-if="!land" label="试验名称" required>
        <el-input v-model="form.name" maxlength="100" />
      </el-form-item>
      <el-form-item label="试验类型">
        <el-radio-group v-model="form.trialType">
          <el-radio-button v-for="(label, key) in (land ? TRIAL_TYPE : TRIAL_TYPE_CHOICES)" :key="key" :value="key as TrialType">{{ label }}</el-radio-button>
        </el-radio-group>
      </el-form-item>
      <el-form-item label="麦区">
        <el-select v-model="form.wheatZone" clearable placeholder="可不选" class="full">
          <el-option v-for="z in WHEAT_ZONES" :key="z.code" :value="z.code" :label="`${z.code} ${z.name}`" />
        </el-select>
      </el-form-item>
      <el-form-item label="重复次数">
        <el-input-number v-model="form.replicateCount" :min="1" :max="20" />
        <span class="hint inline">区域试验、品比试验一般 3 次重复</span>
      </el-form-item>
      <el-form-item label="备注">
        <el-input v-model="form.remark" type="textarea" maxlength="500" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button v-if="trial && !land" type="danger" text class="left" @click="remove">删除试验</el-button>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="save">保存</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.inline {
  margin-left: 10px;
}

.full {
  width: 100%;
}

.left {
  float: left;
}

.land-note {
  margin: -4px 0 14px;
}
</style>
