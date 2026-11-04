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
import { useModuleStore } from '@/stores/modules'
import ObservationEditDialog from '@/desktop/components/ObservationEditDialog.vue'
import HistoryDialog from '@/desktop/components/HistoryDialog.vue'
import CorrectionDialog from '@/desktop/components/CorrectionDialog.vue'

const auth = useAuthStore()
const modules = useModuleStore()
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

// 学生不能直接改，提交更正申请，老师审核通过后才改
const correctOpen = ref(false)
const correcting = ref<ObservationView[]>([])
const canCorrect = computed(() => !auth.isTeacher && auth.canRecord && modules.isOn('corrections'))

function requestCorrection(row: ObservationView) {
  correcting.value = [row]
  correctOpen.value = true
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

/** 按当前筛选条件导出全部数据（Excel，由服务器生成） */
async function exportExcel() {
  const q = query(1, 1)
  if (!q) {
    return
  }
  const { page: _page, size: _size, ...filters } = q
  exporting.value = true
  try {
    await observationApi.exportExcel(filters, `${picker.trial.value?.name ?? '数据'}.xlsx`)
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
      <div>
        <h2>数据查看</h2>
        <div class="page-desc">按条件找录入的数据，可以导出成 Excel 能打开的表格。{{ auth.isTeacher ? '老师可以修改和删除，' : '' }}每条数据都留有修改记录。</div>
      </div>
      <div class="page-actions">
        <el-button :icon="Refresh" @click="load">刷新</el-button>
        <el-button type="primary" :icon="Download" :loading="exporting" :disabled="total === 0" @click="exportExcel">导出 Excel</el-button>
      </div>
    </div>

    <div class="filter-bar panel">
      <el-select :model-value="picker.fieldId.value" placeholder="实验田" class="w160" @update:model-value="onFieldChange">
        <el-option v-for="f in picker.fields.value" :key="f.id" :value="f.id" :label="f.name" />
      </el-select>
      <el-select v-model="picker.trialId.value" placeholder="试验" class="w220" no-data-text="这块地还没有试验">
        <el-option v-for="t in picker.trials.value" :key="t.id" :value="t.id" :label="`${t.seasonYear} 年 · ${t.name}`" />
      </el-select>
      <el-divider direction="vertical" />
      <el-select v-model="filters.varietyId" placeholder="全部品种" clearable filterable class="w120">
        <el-option v-for="v in catalog.varieties" :key="v.id" :value="v.id" :label="v.name" />
      </el-select>
      <el-select v-model="filters.plotId" placeholder="全部小区" clearable filterable class="w110">
        <el-option v-for="p in trialPlots" :key="p.id" :value="p.id" :label="p.code || `${p.rowNo}-${p.colNo}`" />
      </el-select>
      <el-select v-model="filters.category" placeholder="全部分类" clearable class="w110">
        <el-option v-for="c in categories" :key="c" :value="c" :label="TRAIT_CATEGORY[c]" />
      </el-select>
      <el-select v-model="filters.traitId" placeholder="全部项目" clearable filterable class="w140">
        <el-option v-for="t in traitOptions" :key="t.trait.id" :value="t.trait.id" :label="t.trait.name" />
      </el-select>
      <div class="dates">
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

    <div class="panel table-panel">
      <el-empty v-if="!picker.trialId.value && !loading" description="先选择实验田和试验" />
      <template v-else>
        <el-table v-loading="loading" :data="rows" class="table" empty-text="没有符合条件的数据">
          <el-table-column prop="observedOn" label="调查日期" width="126" class-name="num" />
          <el-table-column label="小区" width="140">
            <template #default="{ row }">
              <div><b>{{ row.plotCode }}</b><span v-if="row.replicateNo" class="muted rep">重复 {{ replicateLabel(row.replicateNo) }}</span></div>
              <div class="muted small">{{ row.varietyName ?? '未分配品种' }}</div>
            </template>
          </el-table-column>
          <el-table-column label="项目" min-width="150">
            <template #default="{ row }">
              <div>{{ row.traitName }}</div>
              <div class="muted small">
                {{ TRAIT_CATEGORY[row.traitCategory as TraitCategory] }}
                <el-tag v-if="!row.standardTrait" size="small" type="info" class="tag">自定义</el-tag>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="结果" min-width="140">
            <template #default="{ row }">
              <span class="value">{{ row.displayValue }}</span>
              <div v-if="row.note" class="muted small">{{ row.note }}</div>
            </template>
          </el-table-column>
          <el-table-column label="录入" width="180">
            <template #default="{ row }">
              <div>{{ row.recordedBy }} <span class="muted small">{{ row.recordedAt.slice(5, 16).replace('T', ' ') }}</span></div>
              <div v-if="row.updatedBy" class="edited small">{{ row.updatedBy }} 改过 · {{ row.updatedAt?.slice(5, 16).replace('T', ' ') }}</div>
            </template>
          </el-table-column>
          <el-table-column label="" :width="auth.isTeacher || canCorrect ? 150 : 70" fixed="right" align="right">
            <template #default="{ row }">
              <el-button link size="small" @click="history(row)">记录</el-button>
              <el-button v-if="canCorrect" link type="primary" size="small" @click="requestCorrection(row)">申请更正</el-button>
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
    </div>

    <ObservationEditDialog v-model="editOpen" :observation="editing" @saved="load" />
    <HistoryDialog v-model="historyOpen" :observation-id="historyId" />
    <CorrectionDialog v-model="correctOpen" :observations="correcting" />
  </div>
</template>

<style scoped>
.w110 {
  width: 110px;
}

.w120 {
  width: 120px;
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

.w220 {
  width: 220px;
}

.dates {
  width: 230px;
}

.dates :deep(.el-date-editor) {
  width: 100%;
}

.table-panel {
  padding: 4px 16px 12px;
}

.table {
  width: 100%;
}

.small {
  font-size: 12px;
}

.rep {
  margin-left: 6px;
  font-size: 12px;
}

.tag {
  margin-left: 4px;
}

.value {
  font-weight: 600;
  color: var(--app-text);
}

.edited {
  color: #a2741c;
}

.pager {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 12px;
}
</style>
