<script setup lang="ts">
// 改品系的名称和描述属性。改名后旧名记成曾用名，编号不变
import { reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'

import { lineApi, type LineRow, type LineUpdateRequest } from '@/shared/api'
import { CONTROL_TYPE, ECOTYPE, QUALITY_TYPE } from '@/shared/labels'
import { useBreedingStore } from '@/stores/breeding'

const props = defineProps<{ line: LineRow | null }>()
const visible = defineModel<boolean>({ required: true })
const emit = defineEmits<{ saved: [row: LineRow] }>()

const store = useBreedingStore()
const form = reactive<LineUpdateRequest>({
  name: '', breeder: null, ecotype: null, qualityType: null, remark: null, varietyCode: null, controlType: null,
})
const saving = ref(false)

watch(visible, (open) => {
  if (!open || !props.line) return
  const l = props.line
  Object.assign(form, {
    name: l.name, breeder: l.breeder, ecotype: l.ecotype, qualityType: l.qualityType, remark: l.remark,
    varietyCode: l.varietyCode, controlType: l.controlType,
  })
})

async function save() {
  if (!props.line) return
  if (!form.name.trim()) {
    ElMessage.warning('请填写名称')
    return
  }
  saving.value = true
  try {
    const row = await lineApi.update(props.line.id, { ...form, name: form.name.trim() })
    ElMessage.success('已保存')
    visible.value = false
    await store.reload()
    emit('saved', row)
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <el-dialog v-model="visible" title="修改品种（品系）" width="580px">
    <el-form label-width="88px">
      <el-form-item label="编号">
        <b class="mono">{{ line?.code }}</b>
        <span class="hint">编号建好后不变</span>
      </el-form-item>
      <el-form-item label="名称" required>
        <el-input v-model="form.name" maxlength="100" placeholder="例如进了品比、定了代号或审定名" />
        <div class="hint full">改名后旧名留作曾用名，按名称、曾用名、编号都能搜到。名称里不能有“/”和“*”。</div>
      </el-form-item>
      <el-form-item label="统一编号">
        <el-input v-model="form.varietyCode" maxlength="50" placeholder="可不填；标准允许实名或密码编号" />
      </el-form-item>
      <el-form-item label="来源单位">
        <el-input v-model="form.breeder" maxlength="200" placeholder="选育单位、引种单位" />
      </el-form-item>
      <el-form-item label="冬春性">
        <el-radio-group v-model="form.ecotype">
          <el-radio-button v-for="(label, key) in ECOTYPE" :key="key" :value="key">{{ label }}</el-radio-button>
        </el-radio-group>
        <el-button v-if="form.ecotype" text size="small" @click="form.ecotype = null">清除</el-button>
      </el-form-item>
      <el-form-item label="品质类型">
        <el-radio-group v-model="form.qualityType">
          <el-radio-button v-for="(label, key) in QUALITY_TYPE" :key="key" :value="key">{{ label }}</el-radio-button>
        </el-radio-group>
        <el-button v-if="form.qualityType" text size="small" @click="form.qualityType = null">清除</el-button>
      </el-form-item>
      <el-form-item label="对照">
        <el-radio-group v-model="form.controlType">
          <el-radio-button :value="null">不是对照</el-radio-button>
          <el-radio-button v-for="(label, key) in CONTROL_TYPE" :key="key" :value="key">{{ label }}</el-radio-button>
        </el-radio-group>
        <div class="hint full">统计时和丰产对照比较，算出"比对照增减"。</div>
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
.mono {
  font-family: var(--font-mono);
  margin-right: 10px;
}

.full {
  width: 100%;
}
</style>
