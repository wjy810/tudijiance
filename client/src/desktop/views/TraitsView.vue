<script setup lang="ts">
// 录入项目：标准项目（NY/T 1301—2025）和自定义项目。老师可以停用不用的项目、新增和删除自定义项目
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'

import { traitApi, type TraitCategory, type TraitView } from '@/shared/api'
import { AGG_METHOD, TRAIT_CATEGORY, TRAIT_DATA_TYPE } from '@/shared/labels'
import { useAuthStore } from '@/stores/auth'
import { useCatalogStore } from '@/stores/catalog'
import CustomTraitDialog from '@/desktop/components/CustomTraitDialog.vue'

const auth = useAuthStore()
const catalog = useCatalogStore()
const loading = ref(false)
const tab = ref<TraitCategory>('OPERATION')
const customOpen = ref(false)

const categories = computed(() => (Object.keys(TRAIT_CATEGORY) as TraitCategory[]))
const rows = computed(() => catalog.traits.filter((t) => t.trait.category === tab.value))
const counts = computed(() => {
  const map = new Map<TraitCategory, number>()
  for (const t of catalog.traits) {
    map.set(t.trait.category, (map.get(t.trait.category) ?? 0) + 1)
  }
  return map
})

async function load() {
  loading.value = true
  try {
    await catalog.loadTraits(true)
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    loading.value = false
  }
}

onMounted(load)

function optionsText(t: TraitView): string {
  return t.options.map((o) => (o.label === o.valueCode ? o.label : `${o.valueCode} ${o.label}`)).join('；')
}

async function toggle(t: TraitView, enabled: boolean) {
  try {
    await traitApi.setEnabled(t.trait.id, enabled)
    t.trait.enabled = enabled
    ElMessage.success(enabled ? `已启用"${t.trait.name}"` : `已停用"${t.trait.name}"，录入时不再显示`)
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

async function remove(t: TraitView) {
  try {
    await ElMessageBox.confirm(`删除自定义项目"${t.trait.name}"？已经录过数据的项目不能删，可以停用。`, '删除项目', {
      type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消',
    })
  } catch {
    return
  }
  try {
    await traitApi.removeCustom(t.trait.id)
    ElMessage.success('已删除')
    await load()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

async function onCreated() {
  await load()
  tab.value = 'CUSTOM'
}
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h2>录入项目</h2>
        <div class="hint">标准项目按 NY/T 1301—2025《农作物品种试验规范 小麦》；不够用时老师可以新增自定义项目。</div>
      </div>
      <el-button v-if="auth.isTeacher" type="primary" :icon="Plus" @click="customOpen = true">新增自定义项目</el-button>
    </div>

    <el-tabs v-model="tab">
      <el-tab-pane v-for="c in categories" :key="c" :name="c" :label="`${TRAIT_CATEGORY[c]}（${counts.get(c) ?? 0}）`" />
    </el-tabs>

    <el-table v-loading="loading" :data="rows" border stripe :empty-text="tab === 'CUSTOM' ? '还没有自定义项目' : '没有项目'">
      <el-table-column label="项目" min-width="140">
        <template #default="{ row }">
          {{ row.trait.name }}
          <el-tag v-if="row.trait.isComputed" size="small" type="info">自动计算</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="类型" width="72">
        <template #default="{ row }">{{ TRAIT_DATA_TYPE[row.trait.dataType as keyof typeof TRAIT_DATA_TYPE] }}</template>
      </el-table-column>
      <el-table-column label="单位" width="90">
        <template #default="{ row }">{{ row.trait.unit }}</template>
      </el-table-column>
      <el-table-column label="选项 / 分级" min-width="240" show-overflow-tooltip>
        <template #default="{ row }">{{ optionsText(row) }}</template>
      </el-table-column>
      <el-table-column label="调查时间 / 说明" min-width="200" show-overflow-tooltip>
        <template #default="{ row }">{{ row.trait.surveyTime }}</template>
      </el-table-column>
      <el-table-column label="汇总方法" width="90">
        <template #default="{ row }">{{ AGG_METHOD[row.trait.aggMethod] }}</template>
      </el-table-column>
      <el-table-column label="条款" width="70">
        <template #default="{ row }">{{ row.trait.stdClause }}</template>
      </el-table-column>
      <el-table-column label="启用" width="70" align="center">
        <template #default="{ row }">
          <el-switch
            :model-value="row.trait.enabled"
            :disabled="!auth.isTeacher"
            size="small"
            @update:model-value="(v: string | number | boolean) => toggle(row, !!v)"
          />
        </template>
      </el-table-column>
      <el-table-column v-if="auth.isTeacher && tab === 'CUSTOM'" label="" width="70">
        <template #default="{ row }">
          <el-button link type="danger" size="small" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <CustomTraitDialog v-model="customOpen" @created="onCreated" />
  </div>
</template>
