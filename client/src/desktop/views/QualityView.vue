<script setup lang="ts">
// 数据质量：按标准第 9 章和常见的录入错误检查一个试验的数据。
// 上面是概况（错误、提醒、重要项目完整度、产量误差变异系数），中间是问题清单（按类别分组，可以去平面图看、
// 老师核实"确认没错"），下面是每个重要项目录了多少小区
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Check, CircleCheck, MapLocation, Refresh, RefreshLeft } from '@element-plus/icons-vue'

import { qualityApi, type QualityIssue, type QualityKind, type QualityReport, type TraitCategory } from '@/shared/api'
import { TRAIT_CATEGORY } from '@/shared/labels'
import { QUALITY_KIND, QUALITY_LEVEL } from '@/shared/quality'
import { useTrialPicker } from '@/shared/composables/useTrialPicker'
import { useAuthStore } from '@/stores/auth'
import PageHeader from '@/desktop/components/PageHeader.vue'
import SectionCard from '@/desktop/components/SectionCard.vue'
import StatCard from '@/desktop/components/StatCard.vue'
import EmptyState from '@/desktop/components/EmptyState.vue'

const router = useRouter()
const auth = useAuthStore()
const picker = useTrialPicker()
const report = ref<QualityReport | null>(null)
const loading = ref(false)
const loadError = ref('')
const tab = ref<'open' | 'acked'>('open')

async function load() {
  if (!picker.trialId.value) {
    report.value = null
    return
  }
  loading.value = true
  loadError.value = ''
  try {
    report.value = await qualityApi.check(picker.trialId.value)
  } catch (e) {
    loadError.value = (e as Error).message
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  await picker.init()
  await load()
})
watch(picker.trialId, load)

const open = computed(() => report.value?.issues.filter((i) => !i.acked) ?? [])
const acked = computed(() => report.value?.issues.filter((i) => i.acked) ?? [])
const shown = computed(() => (tab.value === 'open' ? open.value : acked.value))

/** 按类别分组，类别按里面最严重的级别排 */
const groups = computed(() => {
  const order = ['ERROR', 'WARN', 'INFO']
  const map = new Map<QualityKind, QualityIssue[]>()
  for (const i of shown.value) map.set(i.kind, [...(map.get(i.kind) ?? []), i])
  return [...map.entries()]
    .map(([kind, items]) => ({ kind, items, worst: Math.min(...items.map((i) => order.indexOf(i.level))) }))
    .sort((a, b) => a.worst - b.worst || b.items.length - a.items.length)
})

const completenessGroups = computed(() => {
  const map = new Map<TraitCategory, NonNullable<typeof report.value>['completeness']>()
  for (const c of report.value?.completeness ?? []) map.set(c.category, [...(map.get(c.category) ?? []), c])
  return [...map.entries()].map(([category, items]) => ({ category, items }))
})

const startedCount = computed(() => report.value?.completeness.filter((c) => c.started).length ?? 0)

function openPlot(issue: QualityIssue, plotId?: number) {
  const id = plotId ?? issue.plotIds[0]
  if (!picker.fieldId.value) return
  router.push({ path: `/fields/${picker.fieldId.value}`, query: { trial: picker.trialId.value ?? undefined, plot: id } })
}

async function ack(issue: QualityIssue) {
  let note: string | null = null
  try {
    const res = await ElMessageBox.prompt(`“${issue.title}”核实过、确认没错？可以写一下原因，例如：倒伏小区确实矮。`, '确认没错', {
      inputPlaceholder: '原因（可不填）', confirmButtonText: '确认没错', cancelButtonText: '取消',
      inputValidator: (v) => !v || v.length <= 200 || '不能超过 200 个字',
    })
    note = res.value?.trim() || null
  } catch {
    return
  }
  try {
    await qualityApi.ack(picker.trialId.value!, issue.key, note)
    ElMessage.success('已核实，不再算进待处理')
    await load()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

async function unack(issue: QualityIssue) {
  try {
    await qualityApi.unack(picker.trialId.value!, issue.key)
    ElMessage.success('已取消核实')
    await load()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

const checkedText = computed(() => (report.value ? report.value.checkedAt.slice(11, 16) : ''))
</script>

<template>
  <div class="page quality">
    <PageHeader title="数据质量" desc="按标准第 9 章和常见的录入错误检查数据：重要项目漏录、超出常见范围、日期先后不对、产量误差变异系数等。发现的问题可以去平面图改；确认没错的，老师核实后不再提醒。">
      <template #actions>
        <span v-if="report" class="checked faint">{{ checkedText }} 检查</span>
        <el-button :icon="Refresh" :loading="loading" @click="load">重新检查</el-button>
      </template>
    </PageHeader>

    <div class="filter-bar panel">
      <span class="filter-label">实验田</span>
      <el-select v-model="picker.fieldId.value" placeholder="选择实验田" style="width: 200px">
        <el-option v-for="f in picker.fields.value" :key="f.id" :value="f.id" :label="f.name" />
      </el-select>
      <span class="filter-label">试验</span>
      <el-select v-model="picker.trialId.value" placeholder="还没有试验" style="width: 280px" :disabled="picker.trials.value.length === 0">
        <el-option v-for="t in picker.trials.value" :key="t.id" :value="t.id" :label="`${t.seasonYear} 年 · ${t.name}`" />
      </el-select>
    </div>

    <SectionCard v-if="loadError">
      <EmptyState art="data" title="没检查成" :desc="`${loadError}。检查一下网络或服务器。`">
        <el-button type="primary" @click="load">再试一次</el-button>
      </EmptyState>
    </SectionCard>
    <div v-else-if="loading && !report" class="skeleton" style="height: 360px" />
    <SectionCard v-else-if="!picker.trialId.value">
      <EmptyState art="field" title="还没有试验" desc="在平面图上新建试验、分配品种后，这里检查数据" />
    </SectionCard>

    <template v-else-if="report">
      <section class="stats stat-row panel" v-loading="loading">
        <StatCard bare label="错误" :value="report.summary.errors" unit="个" tone="clay" />
        <StatCard bare label="提醒" :value="report.summary.warnings" unit="个" tone="wheat" />
        <StatCard bare label="提示" :value="report.summary.infos" unit="个" tone="sky" />
        <StatCard
          bare label="重要项目完整度"
          :value="report.summary.completenessPercent ?? '—'"
          :unit="report.summary.completenessPercent != null ? '%' : ''"
          tone="green"
        />
        <StatCard
          bare label="产量误差变异系数"
          :value="report.summary.yieldCv ?? '—'"
          :unit="report.summary.yieldCv != null ? '%' : ''"
          :tone="report.summary.yieldCv != null && report.summary.yieldCv >= 15 ? 'clay' : 'green'"
        />
      </section>

      <SectionCard flush>
        <template #title>
          <div class="tabs" role="tablist">
            <button role="tab" :class="{ on: tab === 'open' }" @click="tab = 'open'">待处理 <b class="num">{{ open.length }}</b></button>
            <button role="tab" :class="{ on: tab === 'acked' }" @click="tab = 'acked'">已核实 <b class="num">{{ acked.length }}</b></button>
          </div>
        </template>
        <EmptyState
          v-if="shown.length === 0"
          :art="tab === 'open' ? 'done' : 'data'"
          :size="130"
          :title="tab === 'open' ? '没发现问题' : '还没有核实过的问题'"
          :desc="tab === 'open' ? `这个试验已种植 ${report.summary.planted} 个小区，目前的数据都检查过了。录了新数据后再检查一次。` : '老师核实、确认没错的问题会放在这里'"
        />
        <div v-else class="groups">
          <div v-for="g in groups" :key="g.kind" class="group">
            <div class="group-head">
              <b>{{ QUALITY_KIND[g.kind].label }}</b>
              <span class="faint">{{ QUALITY_KIND[g.kind].desc }}</span>
              <span class="count num">{{ g.items.length }}</span>
            </div>
            <div v-for="i in g.items" :key="i.key" class="issue" :class="i.level.toLowerCase()">
              <span class="level chip" :class="QUALITY_LEVEL[i.level].tone">{{ QUALITY_LEVEL[i.level].label }}</span>
              <div class="issue-body">
                <div class="issue-title">{{ i.title }}</div>
                <div class="issue-detail">{{ i.detail }}</div>
                <div v-if="i.plotCodes.length" class="plots">
                  <button
                    v-for="(code, n) in i.plotCodes.slice(0, 12)" :key="code" type="button" class="plot-chip num"
                    :title="`去平面图看小区 ${code}`" @click="openPlot(i, i.plotIds[n])"
                  >{{ code }}</button>
                  <span v-if="i.plotCodes.length > 12" class="faint">等 {{ i.plotCodes.length }} 个</span>
                </div>
                <div v-if="i.acked" class="ack-note">
                  <el-icon><CircleCheck /></el-icon>{{ i.ackedBy }} 核实过{{ i.ackNote ? '：' + i.ackNote : '' }}
                </div>
              </div>
              <div class="issue-actions">
                <el-button v-if="i.plotIds.length" link type="primary" :icon="MapLocation" @click="openPlot(i)">去平面图</el-button>
                <template v-if="auth.isTeacher">
                  <el-button v-if="!i.acked" link :icon="Check" @click="ack(i)">确认没错</el-button>
                  <el-button v-else link :icon="RefreshLeft" @click="unack(i)">取消核实</el-button>
                </template>
              </div>
            </div>
          </div>
        </div>
      </SectionCard>

      <SectionCard title="重要项目录了多少" :sub="`${startedCount} 个已经开始录 · 共 ${report.completeness.length} 个重要项目，已种植 ${report.summary.planted} 个小区`">
        <div v-if="report.completeness.length === 0" class="faint">还没有重要项目。在“录入项目”里点 ★ 标出重要项目。</div>
        <div v-for="g in completenessGroups" :key="g.category" class="comp-group">
          <div class="comp-title">{{ TRAIT_CATEGORY[g.category] }}</div>
          <div class="comp-grid">
            <div v-for="c in g.items" :key="c.traitId" class="comp" :class="{ idle: !c.started, full: c.started && c.recorded === c.total }">
              <span class="comp-name">{{ c.traitName }}</span>
              <span class="meter" :class="{ wheat: c.started && c.recorded < c.total }"><i :style="{ width: (c.total ? (c.recorded / c.total) * 100 : 0) + '%' }" /></span>
              <span class="comp-num num">{{ c.started ? `${c.recorded}/${c.total}` : '未开始' }}</span>
            </div>
          </div>
        </div>
      </SectionCard>
    </template>
  </div>
</template>

<style scoped>
.quality {
  display: flex;
  flex-direction: column;
  gap: var(--sp-5);
}

.checked {
  font-size: var(--fs-sm);
  margin-right: 4px;
}

.filter-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 18px;
}

.stats {
  padding: 16px 20px;
}

.tabs {
  display: flex;
  gap: 4px;
}

.tabs button {
  border: none;
  background: none;
  padding: 4px 12px;
  border-radius: var(--radius-pill);
  font: inherit;
  font-family: var(--font-serif);
  font-size: 16px;
  font-weight: 700;
  color: var(--ink-3);
  cursor: pointer;
}

.tabs button b {
  font-family: var(--font-sans);
  font-size: 12px;
  margin-left: 4px;
  padding: 0 7px;
  border-radius: var(--radius-pill);
  background: var(--paper-deep);
  color: var(--ink-2);
}

.tabs button.on {
  color: var(--forest);
  background: var(--green-wash);
}

.tabs button.on b {
  background: var(--forest);
  color: #fbf3df;
}

.groups {
  display: flex;
  flex-direction: column;
}

.group-head {
  display: flex;
  align-items: baseline;
  gap: 10px;
  padding: 12px 20px 8px;
  background: var(--surface-2);
  border-top: 1px solid var(--line-soft);
  border-bottom: 1px solid var(--line-soft);
}

.group:first-child .group-head {
  border-top: none;
}

.group-head b {
  color: var(--forest);
}

.group-head .count {
  margin-left: auto;
  font-size: var(--fs-xs);
  color: var(--ink-3);
}

.issue {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 12px 20px;
  border-bottom: 1px solid var(--line-soft);
  border-left: 3px solid transparent;
}

.issue.error {
  border-left-color: var(--clay);
}

.issue.warn {
  border-left-color: var(--wheat);
}

.issue:last-child {
  border-bottom: none;
}

.level {
  flex-shrink: 0;
  margin-top: 1px;
}

.issue-body {
  flex: 1;
  min-width: 0;
}

.issue-title {
  font-weight: 600;
  color: var(--ink);
}

.issue-detail {
  margin-top: 2px;
  font-size: var(--fs-sm);
  color: var(--ink-2);
  line-height: 1.6;
}

.plots {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 6px;
  align-items: center;
}

.plot-chip {
  border: 1px solid var(--line-strong);
  background: var(--surface);
  border-radius: 6px;
  padding: 1px 8px;
  font-size: var(--fs-xs);
  color: var(--ink-2);
  cursor: pointer;
}

.plot-chip:hover {
  border-color: var(--green-2);
  color: var(--green);
}

.ack-note {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-top: 6px;
  font-size: var(--fs-xs);
  color: var(--green);
}

.issue-actions {
  flex-shrink: 0;
  display: flex;
  gap: 4px;
}

.issue-actions .el-button + .el-button {
  margin-left: 0;
}

.comp-group + .comp-group {
  margin-top: 14px;
}

.comp-title {
  font-size: var(--fs-xs);
  color: var(--ink-3);
  margin-bottom: 6px;
  letter-spacing: 0.05em;
}

.comp-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(230px, 1fr));
  gap: 6px 22px;
}

.comp {
  display: grid;
  grid-template-columns: 96px minmax(0, 1fr) 52px;
  align-items: center;
  gap: 8px;
  font-size: var(--fs-sm);
}

.comp-name {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.comp.idle {
  color: var(--ink-4);
}

.comp-num {
  text-align: right;
  font-size: var(--fs-xs);
  color: var(--ink-3);
}

.comp.full .comp-num {
  color: var(--green);
}
</style>
