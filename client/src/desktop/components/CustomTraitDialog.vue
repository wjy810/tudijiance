<script setup lang="ts">
// 新增自定义录入项目，例如"小区负责人""田间积水情况"
import { reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'

import { traitApi, type CustomTraitRequest, type TraitView } from '@/shared/api'

const visible = defineModel<boolean>({ required: true })
const props = defineProps<{
  /** 导入表格时按某个表头新建：预先填好名称、类型、单位、选项，表头记成这个项目的别名 */
  preset?: {
    header: string
    name: string
    dataType: CustomTraitRequest['dataType']
    unit: string | null
    precisionDigits: number | null
    options: string[]
  } | null
}>()
const emit = defineEmits<{ created: [trait: TraitView] }>()

const TYPES: { value: CustomTraitRequest['dataType']; label: string; hint: string }[] = [
  { value: 'OPTION', label: '选项', hint: '从几个选项里选一个，录入最快' },
  { value: 'NUMBER', label: '数值', hint: '例如株高、穗数' },
  { value: 'DATE', label: '日期', hint: '例如某个时期的日期' },
  { value: 'TEXT', label: '文字', hint: '随手记录的文字' },
]

const form = reactive({
  name: '',
  dataType: 'OPTION' as CustomTraitRequest['dataType'],
  unit: '',
  precisionDigits: 1,
  options: ['', ''] as string[],
  description: '',
  important: false,
  minValue: null as number | null,
  maxValue: null as number | null,
})
const saving = ref(false)

watch(visible, (open) => {
  if (!open) {
    return
  }
  const p = props.preset
  Object.assign(form, p
    ? {
        name: p.name, dataType: p.dataType, unit: p.unit ?? '', precisionDigits: p.precisionDigits ?? 1,
        options: p.options.length >= 2 ? [...p.options] : ['', ''], description: '', important: false,
        minValue: null, maxValue: null,
      }
    : {
        name: '', dataType: 'OPTION', unit: '', precisionDigits: 1, options: ['', ''], description: '', important: false,
        minValue: null, maxValue: null,
      })
})

async function save() {
  const name = form.name.trim()
  if (!name) {
    ElMessage.warning('请填写项目名称')
    return
  }
  const options = form.options.map((o) => o.trim()).filter(Boolean)
  if (form.dataType === 'OPTION' && options.length < 2) {
    ElMessage.warning('选项至少要有 2 个')
    return
  }
  const isNum = form.dataType === 'NUMBER'
  if (isNum && form.minValue != null && form.maxValue != null && form.minValue > form.maxValue) {
    ElMessage.warning('常见范围的最小值不能大于最大值')
    return
  }
  saving.value = true
  try {
    const created = await traitApi.createCustom({
      name,
      dataType: form.dataType,
      unit: form.dataType === 'NUMBER' ? form.unit.trim() || null : null,
      precisionDigits: form.dataType === 'NUMBER' ? form.precisionDigits : null,
      options: form.dataType === 'OPTION' ? options : [],
      description: form.description.trim() || null,
      aliases: props.preset?.header ? [props.preset.header] : [],
      important: form.important,
      minValue: isNum ? form.minValue ?? null : null,
      maxValue: isNum ? form.maxValue ?? null : null,
    })
    ElMessage.success(`已新增项目"${name}"`)
    visible.value = false
    emit('created', created)
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <el-dialog v-model="visible" :title="preset ? '按表头新建录入项目' : '新增自定义项目'" width="520px" append-to-body>
    <div v-if="preset" class="preset-hint">
      表格里的"<b>{{ preset.header }}</b>"还没有对应的录入项目。已按表头和数据猜好了类型，确认后新建，
      这个表头会记下来，下次导入自动认出。
    </div>
    <el-form label-width="80px">
      <el-form-item label="名称" required>
        <el-input v-model="form.name" maxlength="50" placeholder="例如：田间积水情况" />
      </el-form-item>
      <el-form-item label="类型">
        <el-radio-group v-model="form.dataType">
          <el-radio-button v-for="t in TYPES" :key="t.value" :value="t.value">{{ t.label }}</el-radio-button>
        </el-radio-group>
        <div class="hint full">{{ TYPES.find((t) => t.value === form.dataType)?.hint }}</div>
      </el-form-item>
      <template v-if="form.dataType === 'OPTION'">
        <el-form-item v-for="(_, i) in form.options" :key="i" :label="i === 0 ? '选项' : ''">
          <div class="option-row">
            <el-input v-model="form.options[i]" maxlength="16" :placeholder="`选项 ${i + 1}`" />
            <el-button v-if="form.options.length > 2" text @click="form.options.splice(i, 1)">删除</el-button>
          </div>
        </el-form-item>
        <el-form-item label="">
          <el-button text type="primary" :disabled="form.options.length >= 20" @click="form.options.push('')">
            + 再加一个选项
          </el-button>
        </el-form-item>
      </template>
      <template v-if="form.dataType === 'NUMBER'">
        <el-form-item label="单位">
          <el-input v-model="form.unit" maxlength="20" placeholder="例如：cm" class="short" />
        </el-form-item>
        <el-form-item label="小数位数">
          <el-input-number v-model="form.precisionDigits" :min="0" :max="4" />
        </el-form-item>
        <el-form-item label="常见范围">
          <div class="range">
            <el-input-number v-model="form.minValue" :value-on-clear="null" controls-position="right" placeholder="最小" class="range-num" />
            <span class="muted">至</span>
            <el-input-number v-model="form.maxValue" :value-on-clear="null" controls-position="right" placeholder="最大" class="range-num" />
          </div>
          <div class="hint full">可不填。录的值超出这个范围时会提醒“确认没录错？”，数据质量检查也会列出来。</div>
        </el-form-item>
      </template>
      <el-form-item label="说明">
        <el-input v-model="form.description" type="textarea" maxlength="200" placeholder="怎么调查、什么时候调查（可不填）" />
      </el-form-item>
      <el-form-item label="重要">
        <el-checkbox v-model="form.important">标为重要项目</el-checkbox>
        <div class="hint full">重要项目录入时排在前面、标 ★；数据质量检查会提醒哪些小区还没录。</div>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="save">保存</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.preset-hint {
  margin-bottom: 14px;
  padding: 10px 12px;
  border-radius: 10px;
  background: var(--wheat-soft);
  border: 1px solid #ead5a8;
  color: #6b5216;
  font-size: 13px;
  line-height: 1.7;
}

.full {
  width: 100%;
}

.option-row {
  display: flex;
  gap: 8px;
  width: 100%;
}

.short {
  width: 160px;
}

.range {
  display: flex;
  align-items: center;
  gap: 8px;
}

.range-num {
  width: 130px;
}
</style>
