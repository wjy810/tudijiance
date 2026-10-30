<script setup lang="ts">
// 首页（老师、学生）：今天的情况一眼看清——各块实验田的状态、今日录入进度、需要关注的异常小区、最新录入
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Monitor, Plus, Right, Warning } from '@element-plus/icons-vue'

import { fieldApi, statsApi, type Field, type FieldDetail, type Screen } from '@/shared/api'
import { rememberTrial } from '@/shared/composables/useTrialPicker'
import { useAuthStore } from '@/stores/auth'
import MiniPlotMap from '@/desktop/components/MiniPlotMap.vue'
import FieldFormDialog from '@/desktop/components/FieldFormDialog.vue'

const auth = useAuthStore()
const router = useRouter()

interface FieldCard {
  field: Field
  screen: Screen | null
}

const cards = ref<FieldCard[]>([])
const loading = ref(true)
const fieldDialog = ref(false)

async function load() {
  loading.value = true
  try {
    const fields = await fieldApi.list()
    cards.value = await Promise.all(
      fields.map(async (field) => ({ field, screen: await statsApi.screen(field.id).catch(() => null) })),
    )
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    loading.value = false
  }
}

onMounted(load)

// ---------- 问候和日期 ----------

const now = new Date()
const greeting = computed(() => {
  const h = now.getHours()
  if (h < 5) return '夜深了'
  if (h < 11) return '早上好'
  if (h < 13) return '中午好'
  if (h < 18) return '下午好'
  return '晚上好'
})
const dateText = `${now.getFullYear()} 年 ${now.getMonth() + 1} 月 ${now.getDate()} 日 星期${'日一二三四五六'[now.getDay()]}`
const name = computed(() => auth.user?.displayName || auth.user?.username || '')

// ---------- 汇总 ----------

const totals = computed(() => {
  const sum = (pick: (s: Screen) => number) => cards.value.reduce((n, c) => n + (c.screen?.indicators ? pick(c.screen) : 0), 0)
  return [
    { label: '实验田', value: cards.value.length, unit: '块' },
    { label: '试验小区', value: sum((s) => s.indicators!.totalPlots), unit: '个' },
    { label: '今天已录', value: sum((s) => s.indicators!.enteredToday), unit: '个小区' },
    { label: '本周录入', value: sum((s) => s.indicators!.weekEntries), unit: '条' },
    { label: '异常小区', value: sum((s) => s.indicators!.abnormal), unit: '个', alert: true },
  ]
})

const abnormal = computed(() =>
  cards.value.flatMap((c) => (c.screen?.plots ?? [])
    .filter((p) => p.status === 'ABNORMAL')
    .map((p) => ({ ...p, fieldId: c.field.id, fieldName: c.field.name }))),
)

const recent = computed(() =>
  cards.value
    .flatMap((c) => (c.screen?.recent ?? []).map((r) => ({ ...r, fieldName: c.field.name })))
    .sort((a, b) => b.at.localeCompare(a.at))
    .slice(0, 8),
)

function timeText(at: string): string {
  const [date, time] = at.split('T')
  const today = new Date()
  const pad = (n: number) => String(n).padStart(2, '0')
  const todayText = `${today.getFullYear()}-${pad(today.getMonth() + 1)}-${pad(today.getDate())}`
  return date === todayText ? `今天 ${time!.slice(0, 5)}` : `${date!.slice(5)} ${time!.slice(0, 5)}`
}

/** 缩略图高度不超过 150px：行多列少的地按比例收窄 */
function mapWidth(s: Screen): string {
  return `${Math.round((150 / Math.max(s.rowCount, 1)) * 1.5 * s.colCount)}px`
}

function progress(s: Screen | null): number {
  const i = s?.indicators
  return i && i.totalPlots > 0 ? Math.round((i.enteredToday / i.totalPlots) * 100) : 0
}

function openMap(fieldId: number) {
  router.push(`/fields/${fieldId}`)
}

function openData(fieldId: number) {
  rememberTrial(fieldId, null)
  router.push('/data')
}

function onFieldCreated(detail: FieldDetail) {
  router.push(`/fields/${detail.field.id}`)
}
</script>

<template>
  <div class="page home" v-loading="loading">
    <header class="hello">
      <div>
        <h2>{{ greeting }}，{{ name }}</h2>
        <div class="page-desc">{{ dateText }}</div>
      </div>
      <div class="page-actions">
        <el-button :icon="Monitor" @click="router.push('/screen')">数据大屏</el-button>
        <el-button v-if="auth.isTeacher" type="primary" :icon="Plus" @click="fieldDialog = true">新建实验田</el-button>
      </div>
    </header>

    <section class="kpis">
      <div v-for="k in totals" :key="k.label" class="kpi panel">
        <div class="kpi-label">{{ k.label }}</div>
        <div class="kpi-value" :class="{ danger: k.alert && k.value > 0 }">
          <el-icon v-if="k.alert && k.value > 0" class="kpi-icon"><Warning /></el-icon>{{ k.value }}<span class="kpi-unit">{{ k.unit }}</span>
        </div>
      </div>
    </section>

    <div class="columns">
      <section class="fields">
        <div class="section-head">
          <h3>实验田</h3>
          <span class="muted">点卡片进入平面图录入数据</span>
        </div>

        <div v-if="!loading && cards.length === 0" class="panel empty">
          <div class="empty-title">还没有实验田</div>
          <div class="muted">
            {{ auth.isTeacher ? '新建一块实验田，设好行数和列数，就会自动生成小区网格。' : '请老师先建实验田。' }}
          </div>
          <el-button v-if="auth.isTeacher" type="primary" :icon="Plus" @click="fieldDialog = true">新建实验田</el-button>
        </div>

        <article v-for="c in cards" :key="c.field.id" class="field-card panel" @click="openMap(c.field.id)">
          <div class="map-box">
            <MiniPlotMap
              v-if="c.screen"
              :style="{ maxWidth: mapWidth(c.screen) }"
              :rows="c.screen.rowCount"
              :cols="c.screen.colCount"
              :plots="c.screen.plots"
            />
          </div>
          <div class="info">
            <div class="info-head">
              <div>
                <div class="field-name">{{ c.field.name }}</div>
                <div class="muted small">
                  {{ c.field.rowCount }} 行 × {{ c.field.colCount }} 列
                  <template v-if="c.field.location"> · {{ c.field.location }}</template>
                  <template v-if="c.field.irrigated !== null"> · {{ c.field.irrigated ? '水地' : '旱地' }}</template>
                </div>
              </div>
              <span v-if="c.screen?.indicators?.currentStage" class="stage">{{ c.screen.indicators.currentStage }}</span>
            </div>

            <template v-if="c.screen?.trialName && c.screen.indicators">
              <div class="trial">{{ c.screen.trialName }}</div>
              <div class="progress-row">
                <span class="muted small">今天已录</span>
                <el-progress
                  :percentage="progress(c.screen)"
                  :stroke-width="8"
                  :show-text="false"
                  color="#2f7d55"
                  class="bar"
                />
                <span class="num small">{{ c.screen.indicators.enteredToday }} / {{ c.screen.indicators.totalPlots }}</span>
              </div>
              <div class="facts">
                <span>已种植 <b>{{ c.screen.indicators.planted }}</b></span>
                <span>已收获 <b>{{ c.screen.indicators.harvested }}</b></span>
                <span :class="{ danger: c.screen.indicators.abnormal > 0 }">异常 <b>{{ c.screen.indicators.abnormal }}</b></span>
                <span>本周 <b>{{ c.screen.indicators.weekEntries }}</b> 条</span>
              </div>
            </template>
            <div v-else class="trial muted">
              还没有试验{{ auth.isTeacher ? '，进入后点"新建试验"' : '' }}
            </div>

            <div class="card-actions" @click.stop>
              <el-button type="primary" @click="openMap(c.field.id)">进入平面图<el-icon class="el-icon--right"><Right /></el-icon></el-button>
              <el-button text @click="openData(c.field.id)">查看数据</el-button>
            </div>
          </div>
        </article>
      </section>

      <aside class="side">
        <section class="panel panel-pad">
          <div class="panel-title">需要关注<span class="sub">标了异常的小区</span></div>
          <div v-if="abnormal.length === 0" class="ok">没有异常小区</div>
          <div
            v-for="a in abnormal"
            :key="a.plotId"
            class="alert-row"
            @click="openMap(a.fieldId)"
          >
            <span class="alert-dot" />
            <div class="alert-text">
              <div><b>{{ a.code }}</b> · {{ a.varietyName ?? '未分配品种' }}</div>
              <div class="muted small">{{ a.abnormalNote || '未写原因' }} · {{ a.fieldName }}</div>
            </div>
          </div>
        </section>

        <section class="panel panel-pad">
          <div class="panel-title">最新录入</div>
          <div v-if="recent.length === 0" class="muted small">还没有录入</div>
          <ul class="timeline">
            <li v-for="(r, i) in recent" :key="i">
              <div class="tl-time num">{{ timeText(r.at) }}</div>
              <div class="tl-body">
                <div v-if="r.plotCount > 1"><b>{{ r.user }}</b> 给 {{ r.plotCount }} 个小区录入{{ r.traitName }}</div>
                <div v-else><b>{{ r.user }}</b> 录入小区 {{ r.plotCode }} 的{{ r.traitName }}</div>
                <div class="tl-value">{{ r.value }}</div>
              </div>
            </li>
          </ul>
        </section>
      </aside>
    </div>

    <FieldFormDialog v-model="fieldDialog" @saved="onFieldCreated" />
  </div>
</template>

<style scoped>
.hello {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  margin-bottom: 20px;
}

.hello h2 {
  margin: 0;
  font-size: 24px;
  font-weight: 600;
}

.kpis {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 14px;
  margin-bottom: 22px;
}

.kpi {
  padding: 14px 18px;
}

.kpi-label {
  font-size: 13px;
  color: var(--app-text-secondary);
}

.kpi-value {
  font-size: 28px;
  font-weight: 600;
  margin-top: 4px;
  display: flex;
  align-items: baseline;
}

.kpi-value.danger {
  color: var(--app-danger);
}

.kpi-icon {
  font-size: 20px;
  margin-right: 6px;
  align-self: center;
}

.kpi-unit {
  font-size: 13px;
  font-weight: normal;
  color: var(--app-text-secondary);
  margin-left: 4px;
}

.columns {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 340px;
  gap: 20px;
  align-items: start;
}

.section-head {
  display: flex;
  align-items: baseline;
  gap: 10px;
  margin-bottom: 12px;
}

.section-head h3 {
  margin: 0;
  font-size: 16px;
}

.empty {
  padding: 36px;
  text-align: center;
  display: flex;
  flex-direction: column;
  gap: 10px;
  align-items: center;
}

.empty-title {
  font-size: 16px;
  font-weight: 600;
}

.field-card {
  display: grid;
  grid-template-columns: minmax(260px, 46%) minmax(0, 1fr);
  gap: 24px;
  padding: 18px 20px;
  margin-bottom: 16px;
  cursor: pointer;
  transition: border-color 0.15s, box-shadow 0.15s;
}

.field-card:hover {
  border-color: var(--el-color-primary-light-5);
  box-shadow: 0 4px 16px rgba(47, 125, 85, 0.1);
}

.map-box {
  background: #f7f8f5;
  border-radius: 8px;
  padding: 14px;
  align-self: start;
  display: flex;
  justify-content: center;
}

.info {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.info-head {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: flex-start;
}

.field-name {
  font-size: 18px;
  font-weight: 600;
  margin-bottom: 4px;
}

.small {
  font-size: 12px;
}

.stage {
  flex-shrink: 0;
  background: var(--app-wheat-soft);
  color: #8a6416;
  border-radius: 999px;
  padding: 3px 12px;
  font-size: 13px;
  font-weight: 600;
}

.trial {
  margin: 14px 0 10px;
  font-size: 14px;
}

.progress-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.bar {
  flex: 1;
}

.facts {
  display: flex;
  gap: 18px;
  margin-top: 12px;
  color: var(--app-text-secondary);
  font-size: 13px;
}

.facts b {
  color: var(--app-text);
  font-weight: 600;
}

.facts .danger,
.facts .danger b {
  color: var(--app-danger);
}

.card-actions {
  margin-top: auto;
  padding-top: 16px;
  display: flex;
  gap: 4px;
}

.side {
  display: flex;
  flex-direction: column;
  gap: 16px;
  margin-top: 34px;
}

.ok {
  color: var(--app-accent);
  font-size: 13px;
}

.alert-row {
  display: flex;
  gap: 10px;
  padding: 8px 6px;
  border-radius: 6px;
  cursor: pointer;
}

.alert-row:hover {
  background: #fbf1ef;
}

.alert-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--app-danger);
  margin-top: 7px;
  flex-shrink: 0;
}

.timeline {
  list-style: none;
  margin: 0;
  padding: 0;
}

.timeline li {
  display: grid;
  grid-template-columns: 76px 1fr;
  gap: 8px;
  padding: 8px 0;
  border-top: 1px solid var(--app-border);
  font-size: 13px;
}

.timeline li:first-child {
  border-top: none;
  padding-top: 0;
}

.tl-time {
  color: var(--app-text-muted);
  font-size: 12px;
  padding-top: 1px;
}

.tl-value {
  color: var(--app-accent);
  font-weight: 600;
  margin-top: 2px;
}
</style>
