<script setup lang="ts">
// 首页（老师、学生）：今天的情况一眼看清——各块实验田的状态、今日录入进度、需要关注的异常小区、最新录入
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  CircleCheck, DataLine, Grid, MapLocation, Monitor, Notebook, PieChart, Plus, Right, Upload, Warning,
} from '@element-plus/icons-vue'

import { fieldApi, statsApi, type Field, type FieldDetail, type Screen } from '@/shared/api'
import { rememberTrial } from '@/shared/composables/useTrialPicker'
import { useAuthStore } from '@/stores/auth'
import MiniPlotMap from '@/desktop/components/MiniPlotMap.vue'
import FieldFormDialog from '@/desktop/components/FieldFormDialog.vue'
import EmptyState from '@/desktop/components/EmptyState.vue'
import bannerArt from '@/assets/art/home-banner.webp'

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
    { label: '实验田', value: cards.value.length, unit: '块', icon: MapLocation, tone: 'green' },
    { label: '试验小区', value: sum((s) => s.indicators!.totalPlots), unit: '个', icon: Grid, tone: 'wheat' },
    { label: '今天已录', value: sum((s) => s.indicators!.enteredToday), unit: '个小区', icon: CircleCheck, tone: 'green' },
    { label: '本周录入', value: sum((s) => s.indicators!.weekEntries), unit: '条', icon: Notebook, tone: 'sky' },
    { label: '异常小区', value: sum((s) => s.indicators!.abnormal), unit: '个', icon: Warning, tone: 'clay', alert: true },
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

/** 横幅上的一句话：第一块有试验的地现在怎么样 */
const headline = computed(() => {
  const c = cards.value.find((x) => x.screen?.indicators)
  if (!c?.screen?.indicators) {
    return cards.value.length ? '还没有试验，进入平面图新建这一年的试验' : '先建一块实验田，开始记录'
  }
  const i = c.screen.indicators
  const stage = i.currentStage ? `正处于${i.currentStage}` : '还没有记录生育期'
  return `${c.field.name}${stage}，今天已录 ${i.enteredToday} / ${i.totalPlots} 个小区`
})

const shortcuts = computed(() => [
  { label: '录入数据', desc: '在平面图上点选小区', icon: Grid, go: () => cards.value[0] && openMap(cards.value[0].field.id), show: cards.value.length > 0 },
  { label: '导入历史表格', desc: '按行按列对应到录入项目', icon: Upload, go: () => router.push('/import'), show: auth.isTeacher },
  { label: '自助分析', desc: '自己选字段做图表', icon: PieChart, go: () => router.push('/analysis'), show: true },
  { label: '统计报表', desc: '品种汇总、比对照、位次', icon: DataLine, go: () => router.push('/stats'), show: true },
].filter((x) => x.show))

function onFieldCreated(detail: FieldDetail) {
  router.push(`/fields/${detail.field.id}`)
}
</script>

<template>
  <div class="page home" v-loading="loading">
    <header class="banner" :style="{ backgroundImage: `url(${bannerArt})` }">
      <div class="banner-text">
        <div class="date">{{ dateText }}</div>
        <h1>{{ greeting }}，{{ name }}</h1>
        <div class="headline">{{ headline }}</div>
        <div class="banner-actions">
          <el-button v-if="cards.length" class="btn-wheat" size="large" @click="openMap(cards[0]!.field.id)">
            进入平面图<el-icon class="el-icon--right"><Right /></el-icon>
          </el-button>
          <el-button size="large" :icon="Monitor" @click="router.push('/screen')">数据大屏</el-button>
          <el-button v-if="auth.isTeacher" size="large" :icon="Plus" @click="fieldDialog = true">新建实验田</el-button>
        </div>
      </div>
    </header>

    <section class="kpis">
      <div v-for="k in totals" :key="k.label" class="kpi panel" :class="{ danger: k.alert && k.value > 0 }">
        <div class="kpi-icon" :class="k.tone"><el-icon><component :is="k.icon" /></el-icon></div>
        <div>
          <div class="kpi-label">{{ k.label }}</div>
          <div class="kpi-value num">{{ k.value }}<span class="kpi-unit">{{ k.unit }}</span></div>
        </div>
      </div>
    </section>

    <div class="columns">
      <section class="fields">
        <div class="section-head">
          <h3>实验田</h3>
          <span class="muted">点卡片进入平面图录入数据</span>
        </div>

        <div v-if="!loading && cards.length === 0" class="panel">
          <EmptyState
            art="field"
            title="还没有实验田"
            :desc="auth.isTeacher ? '新建一块实验田，设好行数和列数，就会自动生成小区网格。' : '请老师先建实验田。'"
            :size="140"
          >
            <el-button v-if="auth.isTeacher" class="btn-wheat" :icon="Plus" @click="fieldDialog = true">新建实验田</el-button>
          </EmptyState>
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
              <span v-if="c.screen?.indicators?.currentStage" class="chip wheat">{{ c.screen.indicators.currentStage }}</span>
            </div>

            <template v-if="c.screen?.trialName && c.screen.indicators">
              <div class="trial">{{ c.screen.trialName }}</div>
              <div class="progress-row">
                <span class="muted small">今天已录</span>
                <el-progress
                  :percentage="progress(c.screen)"
                  :stroke-width="8"
                  :show-text="false"
                  color="#2e6b4f"
                  class="bar"
                />
                <span class="num small">{{ c.screen.indicators.enteredToday }} / {{ c.screen.indicators.totalPlots }}</span>
              </div>
              <div class="facts">
                <div><b class="num">{{ c.screen.indicators.planted }}</b><span>已种植</span></div>
                <div><b class="num">{{ c.screen.indicators.harvested }}</b><span>已收获</span></div>
                <div :class="{ danger: c.screen.indicators.abnormal > 0 }"><b class="num">{{ c.screen.indicators.abnormal }}</b><span>异常</span></div>
                <div><b class="num">{{ c.screen.indicators.weekEntries }}</b><span>本周录入</span></div>
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

        <div class="section-head shortcuts-head">
          <h3>常用</h3>
        </div>
        <div class="shortcuts">
          <button v-for="sc in shortcuts" :key="sc.label" type="button" class="shortcut panel" @click="sc.go()">
            <span class="sc-icon"><el-icon><component :is="sc.icon" /></el-icon></span>
            <span class="sc-text">
              <span class="sc-label">{{ sc.label }}</span>
              <span class="sc-desc">{{ sc.desc }}</span>
            </span>
            <el-icon class="sc-arrow"><Right /></el-icon>
          </button>
        </div>
      </section>

      <aside class="side">
        <section class="panel panel-pad">
          <div class="panel-title">需要关注<span class="sub">标了异常的小区</span></div>
          <div v-if="abnormal.length === 0" class="ok"><el-icon><CircleCheck /></el-icon>没有异常小区</div>
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
.banner {
  position: relative;
  overflow: hidden;
  margin-bottom: 18px;
  border-radius: 18px;
  border: 1px solid var(--line);
  background-color: #f4efe3;
  background-size: cover;
  background-position: right center;
  box-shadow: var(--shadow);
  min-height: 200px;
  display: flex;
  align-items: center;
}

.banner::before {
  content: '';
  position: absolute;
  inset: 0;
  background: linear-gradient(90deg, rgba(248, 243, 232, 0.96) 0%, rgba(248, 243, 232, 0.88) 42%, rgba(248, 243, 232, 0) 70%);
}

.banner-text {
  position: relative;
  padding: 30px 36px;
}

.date {
  font-size: 13px;
  color: var(--wheat);
  letter-spacing: 1px;
}

.banner h1 {
  margin: 8px 0 8px;
  font-family: var(--font-serif);
  font-size: 30px;
  font-weight: 700;
  color: var(--forest);
  letter-spacing: 2px;
}

.headline {
  color: var(--ink-2);
  font-size: 14px;
}

.banner-actions {
  margin-top: 20px;
  display: flex;
  gap: 10px;
}

.kpis {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 14px;
  margin-bottom: 22px;
}

.kpi {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 16px 18px;
}

.kpi-icon {
  width: 44px;
  height: 44px;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 20px;
  flex-shrink: 0;
}

.kpi-icon.green { background: var(--green-soft); color: var(--green); }
.kpi-icon.wheat { background: var(--wheat-soft); color: var(--wheat); }
.kpi-icon.sky { background: var(--sky-soft); color: var(--sky); }
.kpi-icon.clay { background: var(--clay-soft); color: var(--clay); }

.kpi-label {
  font-size: 12.5px;
  color: var(--ink-2);
}

.kpi-value {
  margin-top: 2px;
  font-family: var(--font-serif);
  font-size: 28px;
  font-weight: 700;
  color: var(--forest);
  line-height: 1.15;
}

.kpi.danger .kpi-value {
  color: var(--clay);
}

.kpi-unit {
  margin-left: 4px;
  font-family: var(--font-sans);
  font-size: 12px;
  font-weight: normal;
  color: var(--ink-3);
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
  font-family: var(--font-serif);
  font-size: 17px;
  color: var(--forest);
}

.shortcuts-head {
  margin-top: 22px;
}

.field-card {
  display: grid;
  grid-template-columns: minmax(240px, 0.95fr) 1.3fr;
  gap: 24px;
  padding: 18px;
  margin-bottom: 14px;
  cursor: pointer;
  transition: box-shadow 0.2s, transform 0.2s;
}

.field-card:hover {
  box-shadow: var(--shadow-lift);
  transform: translateY(-1px);
}

.map-box {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 18px;
  border-radius: 12px;
  background: #e9e1cd url('@/assets/art/soil.jpg') repeat;
  background-size: 192px;
  box-shadow: inset 0 0 0 1px rgba(120, 100, 60, 0.15), inset 0 2px 8px rgba(80, 60, 20, 0.12);
}

.info {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.info-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 8px;
}

.field-name {
  font-family: var(--font-serif);
  font-size: 19px;
  font-weight: 700;
  color: var(--forest);
}

.small {
  font-size: 12px;
}

.trial {
  margin-top: 12px;
  font-weight: 600;
  color: var(--ink);
}

.progress-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 10px;
}

.bar {
  flex: 1;
}

.facts {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px;
  margin-top: 14px;
}

.facts > div {
  display: flex;
  flex-direction: column;
  padding: 8px 10px;
  border-radius: 10px;
  background: var(--surface-2);
  border: 1px solid var(--line);
}

.facts b {
  font-family: var(--font-serif);
  font-size: 18px;
  color: var(--forest);
}

.facts span {
  font-size: 12px;
  color: var(--ink-3);
}

.facts .danger b {
  color: var(--clay);
}

.card-actions {
  margin-top: auto;
  padding-top: 14px;
  display: flex;
  gap: 6px;
}

.shortcuts {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.shortcut {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 16px;
  text-align: left;
  cursor: pointer;
  font: inherit;
  color: inherit;
  transition: box-shadow 0.2s, border-color 0.2s;
}

.shortcut:hover {
  border-color: #d9c48f;
  box-shadow: var(--shadow-lift);
}

.sc-icon {
  width: 38px;
  height: 38px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 18px;
  color: var(--wheat);
  background: var(--wheat-soft);
}

.sc-text {
  flex: 1;
  display: flex;
  flex-direction: column;
}

.sc-label {
  font-weight: 600;
  color: var(--forest);
}

.sc-desc {
  font-size: 12px;
  color: var(--ink-3);
}

.sc-arrow {
  color: var(--ink-3);
}

.side {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.ok {
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--green);
  font-size: 13px;
}

.alert-row {
  display: flex;
  gap: 10px;
  padding: 8px 10px;
  margin: 0 -10px;
  border-radius: 8px;
  cursor: pointer;
}

.alert-row:hover {
  background: var(--clay-soft);
}

.alert-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--clay);
  margin-top: 6px;
  flex-shrink: 0;
}

.alert-text {
  font-size: 13px;
  min-width: 0;
}

.timeline {
  list-style: none;
  margin: 0;
  padding: 0 0 0 14px;
  border-left: 1px dashed var(--line-strong);
}

.timeline li {
  position: relative;
  padding: 0 0 14px 4px;
}

.timeline li::before {
  content: '';
  position: absolute;
  left: -19px;
  top: 5px;
  width: 9px;
  height: 9px;
  border-radius: 50%;
  background: var(--surface);
  border: 2px solid var(--wheat);
}

.timeline li:last-child {
  padding-bottom: 0;
}

.tl-time {
  font-size: 11.5px;
  color: var(--ink-3);
}

.tl-body {
  font-size: 13px;
  margin-top: 2px;
}

.tl-value {
  color: var(--green);
  font-weight: 600;
  margin-top: 1px;
}
</style>
