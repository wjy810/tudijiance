<script setup lang="ts">
// 小区详情：基本信息、各项目最新的值（标准项目和自定义项目分开）、全部录入记录
import { computed, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { EditPen, Warning } from '@element-plus/icons-vue'

import { observationApi, type LatestTraitValue, type ObservationView, type PlotDetail, type TraitCategory } from '@/shared/api'
import { CONTROL_TYPE, PLOT_KIND, TRAIT_CATEGORY, replicateLabel } from '@/shared/labels'
import { useAuthStore } from '@/stores/auth'
import ObservationEditDialog from './ObservationEditDialog.vue'
import HistoryDialog from './HistoryDialog.vue'

const props = defineProps<{ trialId: number | null; plotId: number | null }>()
const visible = defineModel<boolean>({ required: true })
const emit = defineEmits<{
  /** 点了"录入数据"，可以带上默认项目 */
  entry: [plotId: number, traitId: number | null]
  /** 数据或异常标记有变化，平面图需要刷新 */
  changed: []
}>()

const auth = useAuthStore()
const detail = ref<PlotDetail | null>(null)
const loading = ref(false)
const tab = ref<'latest' | 'records'>('latest')

const editOpen = ref(false)
const editing = ref<ObservationView | null>(null)
const historyOpen = ref(false)
const historyId = ref<number | null>(null)

async function load() {
  if (!props.trialId || !props.plotId) {
    return
  }
  loading.value = true
  try {
    detail.value = await observationApi.plotDetail(props.trialId, props.plotId)
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    loading.value = false
  }
}

watch([visible, () => props.plotId, () => props.trialId], ([open]) => {
  if (open) {
    detail.value = null
    tab.value = 'latest'
    load()
  }
})

defineExpose({ reload: load })

const plot = computed(() => detail.value?.plot ?? null)
const title = computed(() => {
  const p = plot.value
  return p ? `小区 ${p.code || '未编号'}（第 ${p.rowNo} 行第 ${p.colNo} 列）` : '小区详情'
})
const area = computed(() => {
  const p = plot.value
  return p?.lengthM && p?.widthM ? (p.lengthM * p.widthM).toFixed(2) : null
})

function groupLatest(standard: boolean): { category: TraitCategory; items: LatestTraitValue[] }[] {
  const groups = new Map<TraitCategory, LatestTraitValue[]>()
  for (const v of detail.value?.latest ?? []) {
    if (v.standard !== standard) {
      continue
    }
    const list = groups.get(v.category) ?? []
    list.push(v)
    groups.set(v.category, list)
  }
  return [...groups.entries()].map(([category, items]) => ({ category, items }))
}

const standardGroups = computed(() => groupLatest(true))
const customGroups = computed(() => groupLatest(false))
const canEntry = computed(() => auth.canRecord && plot.value?.kind === 'PLOT')

async function toggleAbnormal() {
  const d = detail.value
  if (!d || !props.trialId) {
    return
  }
  let note: string | null = null
  if (!d.abnormal) {
    try {
      const res = await ElMessageBox.prompt('写一下是什么异常（可不填），例如：倒伏、缺苗、积水', '标记为异常', {
        inputPlaceholder: '异常情况', confirmButtonText: '标记', cancelButtonText: '取消',
        inputValidator: (v) => !v || v.length <= 200 || '不能超过 200 个字',
      })
      note = res.value?.trim() || null
    } catch {
      return
    }
  }
  try {
    await observationApi.setAbnormal(props.trialId, [d.plot.id], !d.abnormal, note)
    ElMessage.success(d.abnormal ? '已取消异常标记' : '已标记为异常')
    await load()
    emit('changed')
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

function edit(o: ObservationView) {
  editing.value = o
  editOpen.value = true
}

function history(o: ObservationView) {
  historyId.value = o.id
  historyOpen.value = true
}

async function remove(o: ObservationView) {
  try {
    await ElMessageBox.confirm(`删除 ${o.observedOn} 的"${o.traitName}：${o.displayValue}"？删除后会留下记录。`, '删除数据', {
      type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消',
    })
  } catch {
    return
  }
  try {
    await observationApi.remove(o.id)
    ElMessage.success('已删除')
    await load()
    emit('changed')
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

async function onEdited() {
  await load()
  emit('changed')
}
</script>

<template>
  <el-drawer v-model="visible" :title="title" size="520px" class="plot-drawer">
    <div v-loading="loading" class="body">
      <template v-if="detail && plot">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="类型">{{ PLOT_KIND[plot.kind] }}</el-descriptions-item>
          <el-descriptions-item label="尺寸">
            <template v-if="area">{{ plot.lengthM }} × {{ plot.widthM }} m（{{ area }} m²）</template>
            <span v-else class="muted">未填写</span>
          </el-descriptions-item>
          <el-descriptions-item label="品种">
            <template v-if="detail.variety">
              {{ detail.variety.name }}
              <el-tag v-if="detail.variety.controlType" size="small" type="danger" effect="plain">
                {{ CONTROL_TYPE[detail.variety.controlType] }}
              </el-tag>
            </template>
            <span v-else class="muted">未分配</span>
          </el-descriptions-item>
          <el-descriptions-item label="重复">
            {{ detail.replicateNo ? replicateLabel(detail.replicateNo) : '-' }}
          </el-descriptions-item>
          <el-descriptions-item label="种植行数">{{ plot.plantingRows ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="行距">{{ plot.rowSpacingCm ? plot.rowSpacingCm + ' cm' : '-' }}</el-descriptions-item>
          <el-descriptions-item label="状态" :span="2">
            <el-tag v-if="detail.abnormal" type="danger">异常{{ detail.abnormalNote ? '：' + detail.abnormalNote : '' }}</el-tag>
            <span v-else>正常</span>
          </el-descriptions-item>
        </el-descriptions>

        <div class="actions">
          <el-button v-if="canEntry" type="primary" :icon="EditPen" @click="emit('entry', plot.id, null)">录入数据</el-button>
          <el-button v-if="canEntry" :icon="Warning" @click="toggleAbnormal">
            {{ detail.abnormal ? '取消异常' : '标记异常' }}
          </el-button>
        </div>

        <el-tabs v-model="tab">
          <el-tab-pane label="最新数据" name="latest">
            <el-empty v-if="detail.latest.length === 0" description="还没有录入数据" :image-size="60" />
            <template v-if="standardGroups.length > 0">
              <div class="group-title">标准项目</div>
              <div v-for="g in standardGroups" :key="g.category" class="group">
                <div class="category muted">{{ TRAIT_CATEGORY[g.category] }}</div>
                <div v-for="v in g.items" :key="v.traitId" class="latest-row">
                  <span class="name">{{ v.traitName }}</span>
                  <span class="value">{{ v.displayValue }}</span>
                  <span class="date muted">{{ v.observedOn }}</span>
                  <el-button v-if="canEntry" link type="primary" size="small" @click="emit('entry', plot.id, v.traitId)">再录</el-button>
                </div>
              </div>
            </template>
            <template v-if="customGroups.length > 0">
              <div class="group-title">自定义项目</div>
              <div v-for="g in customGroups" :key="g.category" class="group">
                <div v-for="v in g.items" :key="v.traitId" class="latest-row">
                  <span class="name">{{ v.traitName }}</span>
                  <span class="value">{{ v.displayValue }}</span>
                  <span class="date muted">{{ v.observedOn }}</span>
                  <el-button v-if="canEntry" link type="primary" size="small" @click="emit('entry', plot.id, v.traitId)">再录</el-button>
                </div>
              </div>
            </template>
          </el-tab-pane>
          <el-tab-pane :label="`全部记录（${detail.records.length}）`" name="records">
            <el-table :data="detail.records" size="small" max-height="520" empty-text="还没有录入数据">
              <el-table-column prop="observedOn" label="日期" width="96" />
              <el-table-column prop="traitName" label="项目" min-width="96" show-overflow-tooltip />
              <el-table-column prop="displayValue" label="值" min-width="96" show-overflow-tooltip />
              <el-table-column prop="recordedBy" label="录入人" width="72" show-overflow-tooltip />
              <el-table-column label="" width="110" fixed="right">
                <template #default="{ row }">
                  <el-button link size="small" @click="history(row)">记录</el-button>
                  <template v-if="auth.isTeacher">
                    <el-button link type="primary" size="small" @click="edit(row)">改</el-button>
                    <el-button link type="danger" size="small" @click="remove(row)">删</el-button>
                  </template>
                </template>
              </el-table-column>
            </el-table>
            <div v-if="detail.records.length >= 200" class="hint">只显示最近 200 条，更多的到"数据查看"里按小区筛选。</div>
          </el-tab-pane>
        </el-tabs>
      </template>
    </div>

    <ObservationEditDialog v-model="editOpen" :observation="editing" @saved="onEdited" />
    <HistoryDialog v-model="historyOpen" :observation-id="historyId" />
  </el-drawer>
</template>

<style scoped>
.body {
  min-height: 200px;
}

.actions {
  display: flex;
  gap: 8px;
  margin: 14px 0 4px;
}

.group-title {
  font-weight: 600;
  margin: 8px 0 6px;
}

.group {
  margin-bottom: 10px;
}

.category {
  font-size: 12px;
  margin-bottom: 2px;
}

.latest-row {
  display: grid;
  grid-template-columns: 120px 1fr 84px 40px;
  gap: 8px;
  align-items: center;
  padding: 5px 0;
  border-bottom: 1px dashed var(--app-border);
}

.latest-row .name {
  color: var(--app-text-secondary);
}

.latest-row .value {
  font-weight: 500;
  word-break: break-all;
}

.latest-row .date {
  font-size: 12px;
}
</style>
