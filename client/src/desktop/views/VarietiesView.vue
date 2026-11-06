<script setup lang="ts">
// 品种：老师新增、修改、删除；学生只能看
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'

import { varietyApi, type ControlType, type Ecotype, type QualityType, type Variety, type VarietyRequest } from '@/shared/api'
import { CONTROL_TYPE, ECOTYPE, QUALITY_TYPE } from '@/shared/labels'
import { useAuthStore } from '@/stores/auth'
import { useCatalogStore } from '@/stores/catalog'

const auth = useAuthStore()
const catalog = useCatalogStore()
const keyword = ref('')
const loading = ref(false)

const rows = computed(() => {
  const k = keyword.value.trim()
  return k
    ? catalog.comparisonVarieties.filter((v) => v.name.includes(k) || v.code?.includes(k) || v.breeder?.includes(k))
    : catalog.comparisonVarieties
})

async function load(force = true) {
  loading.value = true
  try {
    await catalog.loadVarieties(force)
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    loading.value = false
  }
}

onMounted(() => load())

// ---------- 新增、修改 ----------

const dialogOpen = ref(false)
const editing = ref<Variety | null>(null)
const saving = ref(false)

function emptyForm(): VarietyRequest {
  return { name: '', code: null, breeder: null, ecotype: null, qualityType: null, controlType: null, remark: null }
}

const form = reactive<VarietyRequest>(emptyForm())

function open(v: Variety | null) {
  editing.value = v
  Object.assign(form, v ? { ...v } : emptyForm())
  dialogOpen.value = true
}

async function save() {
  if (!form.name.trim()) {
    ElMessage.warning('请填写品种名称')
    return
  }
  saving.value = true
  try {
    if (editing.value) {
      await varietyApi.update(editing.value.id, form)
    } else {
      await varietyApi.create(form)
    }
    ElMessage.success('已保存')
    dialogOpen.value = false
    await load()
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    saving.value = false
  }
}

async function remove(v: Variety) {
  try {
    await ElMessageBox.confirm(`删除品种"${v.name}"？已经种在小区里的品种不能删。`, '删除品种', {
      type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消',
    })
  } catch {
    return
  }
  try {
    await varietyApi.remove(v.id)
    ElMessage.success('已删除')
    await load()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h2>品种</h2>
        <div class="page-desc">试验用的品种，共 {{ catalog.comparisonVarieties.length }} 个。标为对照的品种在统计时作为比较基准。</div>
      </div>
      <div class="page-actions">
        <el-input v-model="keyword" placeholder="搜索名称、编号、选育单位" clearable class="search" />
        <el-button v-if="auth.isTeacher" type="primary" :icon="Plus" @click="open(null)">新增品种</el-button>
      </div>
    </div>

    <div class="panel table-panel">
    <el-table v-loading="loading" :data="rows" empty-text="还没有品种">
      <el-table-column label="品种名称" min-width="140">
        <template #default="{ row }"><b>{{ row.name }}</b></template>
      </el-table-column>
      <el-table-column prop="code" label="编号" width="110" />
      <el-table-column prop="breeder" label="选育单位" min-width="160" show-overflow-tooltip />
      <el-table-column label="冬春性" width="90">
        <template #default="{ row }">{{ row.ecotype ? ECOTYPE[row.ecotype as Ecotype] : '' }}</template>
      </el-table-column>
      <el-table-column label="品质类型" width="90">
        <template #default="{ row }">{{ row.qualityType ? QUALITY_TYPE[row.qualityType as QualityType] : '' }}</template>
      </el-table-column>
      <el-table-column label="对照" width="100">
        <template #default="{ row }">
          <el-tag v-if="row.controlType" size="small" type="danger" effect="plain">{{ CONTROL_TYPE[row.controlType as ControlType] }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="remark" label="备注" min-width="140" show-overflow-tooltip />
      <el-table-column v-if="auth.isTeacher" label="操作" width="110" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="open(row)">修改</el-button>
          <el-button link type="danger" size="small" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
    </div>

    <el-dialog v-model="dialogOpen" :title="editing ? '修改品种' : '新增品种'" width="520px">
      <el-form label-width="88px">
        <el-form-item label="品种名称" required>
          <el-input v-model="form.name" maxlength="100" />
        </el-form-item>
        <el-form-item label="编号">
          <el-input v-model="form.code" maxlength="50" placeholder="可不填" />
        </el-form-item>
        <el-form-item label="选育单位">
          <el-input v-model="form.breeder" maxlength="200" placeholder="可不填" />
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
        <el-form-item label="对照品种">
          <el-radio-group v-model="form.controlType">
            <el-radio-button :value="null">不是对照</el-radio-button>
            <el-radio-button v-for="(label, key) in CONTROL_TYPE" :key="key" :value="key">{{ label }}</el-radio-button>
          </el-radio-group>
          <div class="hint full">统计时，和丰产对照比较算出"比对照增减"。</div>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" maxlength="500" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogOpen = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.table-panel {
  padding: 4px 16px 12px;
}

.search {
  width: 240px;
}

.full {
  width: 100%;
}
</style>
