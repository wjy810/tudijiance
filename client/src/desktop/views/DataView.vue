<script setup lang="ts">
// 数据查看：按试验、品种、小区、项目、日期筛选录入的数据。老师可以修改、删除，所有人都能看修改记录、导出表格
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Download, Refresh } from '@element-plus/icons-vue'

import { fieldApi, observationApi, type ObservationQuery, type ObservationView, type Plot, type TraitCategory } from '@/shared/api'
import { TRAIT_CATEGORY, replicateLabel } from '@/shared/labels'
import { useTrialPicker } from '@/shared/composables/useTrialPicker'
import { useAuthStore } from '@/stores/auth'
import { useCatalogStore } from '@/stores/catalog'
import ObservationEditDialog from '@/desktop/components/ObservationEditDialog.vue'
import HistoryDialog from '@/desktop/components/HistoryDialog.vue'

const auth = useAuthStore()
const catalog = useCatalogStore()
const picker = useTrialPicker()

const filters = reactive<{
  varietyId: number | null
  plotId: number | null
  category: TraitCategory | null
  traitId: number | null
  dates: [string, string] | null
}>({ varietyId: null, plotId: null, category: null, traitId: null, dates: null })

const plots = ref<Plot[]>([])
const rows = ref<ObservationView[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(50)
const loading = ref(false)
const exporting = ref(false)

const categories = computed(() => {
  const set = new Set(catalog.traits.map((t) => t.trait.category))
  return (Object.keys(TRAIT_CATEGORY) as TraitCategory[]).filter((c) => set.has(c))
})
const traitOptions = computed(() =>
  catalog.traits.filter((t) => !filters.category || t.trait.category === filters.category),
)
const trialPlots = computed(() => plots.value.filter((p) => p.kind === 'PLOT'))

function query(p: number, s: number): ObservationQuery | null {
  if (!picker.trialId.value) {
    return null
  }
  return {
    trialId: picker.trialId.value,
    varietyId: filters.varietyId ?? undefined,
    plotId: filters.plotId ?? undefined,
    category: filters.category ?? undefined,
    traitId: filters.traitId ?? undefined,
    from: filters.dates?.[0],
    to: filters.dates?.[1],
    page: p,
    size: s,
  }
}

async function load() {
  const q = query(page.value, size.value)
  if (!q) {
    rows.value = []
    total.value = 0
    return
  }
  loading.value = true
  try {
    const res = await observationApi.list(q)
    rows.value = res.items
    total.value = res.total
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    loading.value = false
  }
}

async function loadPlots() {
  plots.value = picker.fieldId.value ? (await fieldApi.detail(picker.fieldId.value)).plots : []
}

onMounted(async () => {
  try {
    await Promise.all([picker.init(), catalog.loadTraits(), catalog.loadVarieties()])
    await loadPlots()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
  await load()
})

async function onFieldChange(id: number | null) {
  filters.plotId = null
  try {
    await picker.selectField(id)
    await loadPlots()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

watch(() => filters.category, () => {
  if (filters.traitId && !traitOptions.value.some((t) => t.trait.id === filters.traitId)) {
    filters.traitId = null
  }
})

// 筛选条件变了回到第一页
watch([picker.trialId, () => ({ ...filters })], () => {
  page.value = 1
  load()
}, { deep: true })

function resetFilters() {
  Object.assign(filters, { varietyId: null, plotId: null, category: null, traitId: null, dates: null })
}

// ---------- 修改、删除、修改记录 ----------

const editOpen = ref(false)
const editing = ref<ObservationView | null>(null)
const historyOpen = ref(false)
const historyId = ref<number | null>(null)

function edit(row: ObservationView) {
  editing.value = row
  editOpen.value = true
}

function history(row: ObservationView) {
  historyId.value = row.id
  historyOpen.value = true
}

async function remove(row: ObservationView) {
  try {
    await ElMessageBox.confirm(`删除小区 ${row.plotCode ?? ''} ${row.observedOn} 的"${row.traitName}：${row.displayValue}"？`, '删除数据', {
      type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消',
    })
  } catch {
    return
  }
  try {
    await observationApi.remove(row.id)
    ElMessage.success('已删除')
    await load()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

// ---------- 导出 ----------

function csvCell(v: unknown): string {
  const s = v === null || v === undefined ? '' : String(v)
  return /[",\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s
}

/** 按当前筛选条件导出全部数据，Excel 可以直接打开 */
async function exportCsv() {
  exporting.value = true
  try {
    const all: ObservationView[] = []
    for (let p = 1; ; p++) {
      const q = query(p, 500)
      if (!q) {
        break
      }
      const res = await observationApi.list(q)
      all.push(...res.items)
      if (all.length >= res.total || res.items.length === 0) {
        break
      }
    }
    const header = ['调查日期', '小区', '品种', '重复', '分类', '项目', '值', '单位', '备注', '录入人', '录入时间', '修改人', '修改时间']
    const lines = all.map((o) => [
      o.observedOn, o.plotCode, o.varietyName, o.replicateNo ? replicateLabel(o.replicateNo) : '',
      TRAIT_CATEGORY[o.traitCategory], o.traitName, o.displayValue, o.unit, o.note, o.recordedBy,
      o.recordedAt.replace('T', ' ').slice(0, 19), o.updatedBy, o.updatedAt?.replace('T', ' ').slice(0, 19),
    ].map(csvCell).join(','))
    // 加 BOM，Excel 打开中文不乱码
    const blob = new Blob(['﻿' + [header.join(','), ...lines].join('\r\n')], { type: 'text/csv;charset=utf-8' })
    const a = document.createElement('a')
    a.href = URL.createObjectURL(blob)
    a.download = `${picker.trial.value?.name ?? '数据'}.csv`
    a.click()
    URL.revokeObjectURL(a.href)
    ElMessage.success(`已导出 ${all.length} 条`)
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    exporting.value = false
  }
}
</script>

<template>
  <div class="page">
    <div class="page-header">
      <h2>数据查看</h2>
      <div>
        <el-button :icon="Refresh" @click="load">刷新</el-button>
        <el-button :icon="Download" :loading="exporting" :disabled="total === 0" @click="exportCsv">导出表格</el-button>
      </div>
    </div>

    <div class="filters">
      <el-select :model-value="picker.fieldId.value" placeholder="实验田" class="w180" @update:model-value="onFieldChange">
        <el-option v-for="f in picker.fields.value" :key="f.id" :value="f.id" :label="f.name" />
      </el-select>
      <el-select v-model="picker.trialId.value" placeholder="试验" class="w240" no-data-text="这块地还没有试验">
        <el-option v-for="t in picker.trials.value" :key="t.id" :value="t.id" :label="`${t.seasonYear} 年 · ${t.name}`" />
      </el-select>
      <el-select v-model="filters.varietyId" placeholder="全部品种" clearable filterable class="w160">
        <el-option v-for="v in catalog.varieties" :key="v.id" :value="v.id" :label="v.name" />
      </el-select>
      <el-select v-model="filters.plotId" placeholder="全部小区" clearable filterable class="w140">
        <el-option v-for="p in trialPlots" :key="p.id" :value="p.id" :label="p.code || `${p.rowNo}-${p.colNo}`" />
      </el-select>
      <el-select v-model="filters.category" placeholder="全部分类" clearable class="w140">
        <el-option v-for="c in categories" :key="c" :value="c" :label="TRAIT_CATEGORY[c]" />
      </el-select>
      <el-select v-model="filters.traitId" placeholder="全部项目" clearable filterable class="w180">
        <el-option v-for="t in traitOptions" :key="t.trait.id" :value="t.trait.id" :label="t.trait.name" />
      </el-select>
      <div class="w260">
        <el-date-picker
          v-model="filters.dates"
          type="daterange"
          value-format="YYYY-MM-DD"
          start-placeholder="开始日期"
          end-placeholder="结束日期"
        />
      </div>
      <el-button text @click="resetFilters">清空条件</el-button>
    </div>

    <el-empty v-if="!picker.trialId.value && !loading" description="先选择实验田和试验" />
    <template v-else>
      <el-table v-loading="loading" :data="rows" border stripe size="small" class="table" empty-text="没有符合条件的数据">
        <el-table-column prop="observedOn" label="调查日期" width="100" />
        <el-table-column prop="plotCode" label="小区" width="80" />
        <el-table-column prop="varietyName" label="品种" min-width="110" show-overflow-tooltip />
        <el-table-column label="重复" width="56" align="center">
          <template #default="{ row }">{{ row.replicateNo ? replicateLabel(row.replicateNo) : '' }}</template>
        </el-table-column>
        <el-table-column label="分类" width="86">
          <template #default="{ row }">{{ TRAIT_CATEGORY[row.traitCategory as TraitCategory] }}</template>
        </el-table-column>
        <el-table-column label="项目" min-width="120" show-overflow-tooltip>
          <template #default="{ row }">
            {{ row.traitName }}
            <el-tag v-if="!row.standardTrait" size="small" type="info">自定义</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="displayValue" label="值" min-width="120" show-overflow-tooltip />
        <el-table-column prop="note" label="备注" min-width="100" show-overflow-tooltip />
        <el-table-column prop="recordedBy" label="录入人" width="80" show-overflow-tooltip />
        <el-table-column label="录入时间" width="136">
          <template #default="{ row }">{{ row.recordedAt.replace('T', ' ').slice(0, 16) }}</template>
        </el-table-column>
        <el-table-column label="修改" width="120" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.updatedBy" class="muted">{{ row.updatedBy }} {{ row.updatedAt?.slice(5, 16).replace('T', ' ') }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" :width="auth.isTeacher ? 156 : 70" fixed="right">
          <template #default="{ row }">
            <el-button link size="small" @click="history(row)">记录</el-button>
            <template v-if="auth.isTeacher">
              <el-button link type="primary" size="small" @click="edit(row)">修改</el-button>
              <el-button link type="danger" size="small" @click="remove(row)">删除</el-button>
            </template>
          </template>
        </el-table-column>
      </el-table>
      <div class="pager">
        <span class="muted">共 {{ total }} 条</span>
        <el-pagination
          v-model:current-page="page"
          v-model:page-size="size"
          :total="total"
          :page-sizes="[20, 50, 100, 200]"
          layout="sizes, prev, pager, next, jumper"
          @current-change="load"
          @size-change="() => { page = 1; load() }"
        />
      </div>
    </template>

    <ObservationEditDialog v-model="editOpen" :observation="editing" @saved="load" />
    <HistoryDialog v-model="historyOpen" :observation-id="historyId" />
  </div>
</template>

<style scoped>
.filters {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 12px;
}

.w140 {
  width: 140px;
}

.w160 {
  width: 160px;
}

.w180 {
  width: 180px;
}

.w240 {
  width: 240px;
}

.w260 {
  width: 260px;
}

.w260 :deep(.el-date-editor) {
  width: 100%;
}

.table {
  width: 100%;
}

.pager {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 12px;
}
</style>
