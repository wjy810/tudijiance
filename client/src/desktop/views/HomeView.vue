<script setup lang="ts">
// 首页（老师、学生）。从上到下按"先看清现在，再动手"排：
//   ① 问候 + 一句话概况
//   ② 当前实验田：生育期走到哪了、今天录了多少，右边是平面图缩略图（点开就能录）
//   ③ 今天该记什么（合并后换成 TodayTodoPanel）
//   ④ 需要处理：异常小区、数据检查、待审核的更正申请（老师）
//   ⑤ 最新录入：谁、什么时候、录了什么
//   ⑥ 全部实验田（有两块以上时才显示）
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  ArrowDown, ArrowRight, Calendar, Check, CircleCheck, DocumentChecked, EditPen, Grid, Plus, Refresh, Right,
  Warning,
} from '@element-plus/icons-vue'

import {
  fieldApi, observationApi, statsApi, trialApi, type Field, type FieldDetail, type Screen, type Trial,
} from '@/shared/api'
import { PLOT_STATUS } from '@/shared/labels'
import { rememberTrial } from '@/shared/composables/useTrialPicker'
import { MAIN_STAGES, stageView } from '@/shared/growthStage'
import { useAuthStore } from '@/stores/auth'
import MiniPlotMap from '@/desktop/components/MiniPlotMap.vue'
import BlockMiniMap from '@/desktop/components/BlockMiniMap.vue'
import blocksArt from '@/assets/art/empty-blocks.webp'
import { fieldSize } from '@/shared/fieldLayout'
import FieldFormDialog from '@/desktop/components/FieldFormDialog.vue'
import EmptyState from '@/desktop/components/EmptyState.vue'
import PageHeader from '@/desktop/components/PageHeader.vue'
import SectionCard from '@/desktop/components/SectionCard.vue'
import StatCard from '@/desktop/components/StatCard.vue'
import GrowthStageTrack from '@/desktop/components/GrowthStageTrack.vue'

const auth = useAuthStore()
const router = useRouter()

interface FieldCard {
  field: Field
  screen: Screen | null
}

const cards = ref<FieldCard[]>([])
const loading = ref(true)
const loadError = ref<string | null>(null)
const fieldDialog = ref(false)

/** 首页上方大卡片显示的那块地 */
const primaryId = ref<number | null>(null)
/** 这块地最新一年的试验（和大屏取的是同一个），"今天该记什么"要用它的编号 */
const primaryTrial = ref<Trial | null>(null)
/** 今天录过数据的小区，缩略图上加绿点 */
const enteredToday = ref<number[]>([])

async function load() {
  loading.value = true
  loadError.value = null
  try {
    const fields = await fieldApi.list()
    cards.value = await Promise.all(
      fields.map(async (field) => ({ field, screen: await statsApi.screen(field.id).catch(() => null) })),
    )
    const remembered = Number(localStorage.getItem('tudijiance.lastField'))
    primaryId.value = (cards.value.find((c) => c.field.id === remembered)
      ?? cards.value.find((c) => c.screen?.trialName)
      ?? cards.value[0])?.field.id ?? null
    await loadPrimaryTrial()
  } catch (e) {
    loadError.value = (e as Error).message
  } finally {
    loading.value = false
  }
}

async function loadPrimaryTrial() {
  primaryTrial.value = null
  enteredToday.value = []
  if (!primaryId.value) return
  try {
    const trials = await trialApi.list(primaryId.value)
    primaryTrial.value = [...trials].sort((a, b) => b.seasonYear - a.seasonYear || b.id - a.id)[0] ?? null
    if (primaryTrial.value) {
      const status = await observationApi.status(primaryTrial.value.id)
      enteredToday.value = status.plots.filter((p) => p.enteredToday).map((p) => p.plotId)
    }
  } catch {
    // 取不到只是少了绿点和待办，不影响首页其它内容
  }
}

async function switchPrimary(id: number) {
  primaryId.value = id
  rememberTrial(id, null)
  await loadPrimaryTrial()
}

onMounted(load)

// ---------- 问候 ----------

const now = new Date()
const greeting = computed(() => {
  const h = now.getHours()
  if (h < 5) return '夜深了'
  if (h < 11) return '早上好'
  if (h < 13) return '中午好'
  if (h < 18) return '下午好'
  return '晚上好'
})
const dateText = `${now.getMonth() + 1} 月 ${now.getDate()} 日 · 星期${'日一二三四五六'[now.getDay()]}`
const name = computed(() => auth.user?.displayName || auth.user?.username || '')

// ---------- 当前实验田 ----------

const primary = computed(() => cards.value.find((c) => c.field.id === primaryId.value) ?? null)
const ind = computed(() => primary.value?.screen?.indicators ?? null)
const stage = computed(() => stageView(ind.value?.currentStage))
const stageMonths = computed(() => (stage.value.index >= 0 ? MAIN_STAGES[stage.value.index]!.months : null))

const varietyCount = computed(() =>
  new Set((primary.value?.screen?.plots ?? []).map((p) => p.varietyName).filter(Boolean)).size)

const leftToday = computed(() => (ind.value ? Math.max(0, ind.value.planted - ind.value.enteredToday) : 0))

/** 缩略图：高度不超过 250px，行多列少的地按比例收窄 */
function mapWidth(s: Screen, maxHeight: number, maxWidth: number): string {
  if (s.layout) {
    // 区块布局按实际长宽比
    const f = fieldSize(s.layout)
    return `${Math.round(Math.min((maxHeight * f.w) / Math.max(f.h, 1e-6), maxWidth))}px`
  }
  const byHeight = (maxHeight / Math.max(s.rowCount, 1)) * 1.5 * s.colCount
  return `${Math.round(Math.min(byHeight, maxWidth))}px`
}

function mapHeight(s: Screen, maxHeight: number, maxWidth: number): string | undefined {
  if (!s.layout) return undefined
  const f = fieldSize(s.layout)
  return `${Math.round(Math.min(maxHeight, (maxWidth * f.h) / Math.max(f.w, 1e-6)))}px`
}

function fieldMeta(f: Field, s?: Screen | null): string {
  const blocks = s?.layout?.blocks.filter((b) => b.kind === 'PLANT').length
  return [
    blocks ? `${blocks} 个区块 · ${s!.plots.length} 个小区` : `${f.rowCount} 行 × ${f.colCount} 列`,
    f.location,
    f.irrigated === null ? null : f.irrigated ? '水地' : '旱地',
  ].filter(Boolean).join(' · ')
}

const LEGEND = (['GROWING', 'ABNORMAL', 'HARVESTED', 'NONE'] as const).map((k) => ({ key: k, ...PLOT_STATUS[k] }))

/** 问候下面的一句话：今天还差多少、有没有异常 */
const headline = computed(() => {
  if (loadError.value) {
    return '暂时取不到今天的情况。'
  }
  if (cards.value.length === 0) {
    return auth.isTeacher ? '先建一块实验田，设好行数和列数，就能开始记录了。' : '老师还没有建实验田。'
  }
  if (!ind.value) {
    return `${primary.value?.field.name ?? ''}还没有今年的试验${auth.isTeacher ? '，进入平面图新建一个' : ''}。`
  }
  const parts = [ind.value.enteredToday > 0
    ? `今天已经录了 ${ind.value.enteredToday} 个小区`
    : '今天还没有录入']
  parts.push(abnormalTotal.value > 0 ? `有 ${abnormalTotal.value} 个小区标了异常，记得去看看` : '没有异常小区')
  return parts.join('，') + '。'
})

function openMap(fieldId: number) {
  router.push(`/fields/${fieldId}`)
}

function openData(fieldId: number) {
  rememberTrial(fieldId, primaryTrial.value?.fieldId === fieldId ? primaryTrial.value.id : null)
  router.push('/data')
}

// ---------- 需要处理 ----------

const abnormal = computed(() =>
  cards.value.flatMap((c) => (c.screen?.plots ?? [])
    .filter((p) => p.status === 'ABNORMAL')
    .map((p) => ({ ...p, fieldId: c.field.id, fieldName: c.field.name }))),
)
const abnormalTotal = computed(() => abnormal.value.length)

/** 数据检查、更正申请这两页由别的功能提供；路由还没有时卡片显示"即将开放" */
function hasRoute(path: string): boolean {
  return router.getRoutes().some((r) => r.path === path)
}

interface Attention {
  key: string
  title: string
  desc: string
  icon: typeof Warning
  tone: 'clay' | 'green' | 'wheat' | 'sky'
  count?: number | null
  action: string
  go: (() => void) | null
}

const attention = computed<Attention[]>(() => {
  const list: Attention[] = []
  const first = abnormal.value[0]
  list.push({
    key: 'abnormal',
    title: '异常小区',
    desc: abnormalTotal.value === 0
      ? '没有标了异常的小区'
      : abnormal.value.slice(0, 2).map((a) => `${a.code ?? ''} ${a.abnormalNote || '未写原因'}`).join('；')
        + (abnormalTotal.value > 2 ? ' 等' : ''),
    icon: abnormalTotal.value > 0 ? Warning : CircleCheck,
    tone: abnormalTotal.value > 0 ? 'clay' : 'green',
    count: abnormalTotal.value,
    action: abnormalTotal.value > 0 ? '去平面图看' : '',
    go: first ? () => openMap(first.fieldId) : null,
  })
  list.push({
    key: 'checks',
    title: '数据质量提醒',
    desc: '漏录、超出常见范围、前后对不上的数据',
    icon: DocumentChecked,
    tone: 'wheat',
    action: '去检查',
    // 合并"数据检查"后可在这里接上提醒条数（count）
    go: hasRoute('/checks') ? () => router.push('/checks') : null,
  })
  if (auth.isTeacher) {
    list.push({
      key: 'corrections',
      title: '待审核的更正申请',
      desc: '学生发现录错了提交的更正，老师审核后生效',
      icon: EditPen,
      tone: 'sky',
      action: '去审核',
      // 合并"更正申请"后可在这里接上待审核条数（count）
      go: hasRoute('/corrections') ? () => router.push('/corrections') : null,
    })
  }
  return list
})

// ---------- 最新录入 ----------

const multiField = computed(() => cards.value.length > 1)

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
  const [, m, d] = date!.split('-').map(Number)
  return date === todayText ? `今天 ${time!.slice(0, 5)}` : `${m}月${d}日 ${time!.slice(0, 5)}`
}

// ---------- 全部实验田 ----------

function progress(s: Screen | null): number {
  const i = s?.indicators
  return i && i.totalPlots > 0 ? Math.round((i.enteredToday / i.totalPlots) * 100) : 0
}

function onFieldCreated(detail: FieldDetail) {
  router.push(`/fields/${detail.field.id}`)
}
</script>

<template>
  <div class="page home">
    <PageHeader plain :title="`${greeting}，${name}`" :eyebrow="dateText">
      <template #desc>{{ loading ? '正在看看今天的情况……' : headline }}</template>
      <template v-if="auth.isTeacher && cards.length > 0" #actions>
        <el-button :icon="Plus" @click="fieldDialog = true">新建实验田</el-button>
      </template>
    </PageHeader>

    <!-- 加载中：骨架占位 -->
    <template v-if="loading && cards.length === 0">
      <div class="hero hero-skeleton">
        <div class="sk-main">
          <div class="skeleton" style="width: 120px; height: 14px" />
          <div class="skeleton" style="width: 260px; height: 28px; margin-top: 12px" />
          <div class="skeleton" style="width: 100%; height: 96px; margin-top: 28px" />
          <div class="skeleton" style="width: 70%; height: 44px; margin-top: 24px" />
        </div>
        <div class="skeleton sk-map" />
      </div>
      <div class="skeleton" style="height: 180px; margin-top: 20px; border-radius: 14px" />
    </template>

    <!-- 出错 -->
    <div v-else-if="loadError" class="panel">
      <EmptyState art="data" title="没取到首页的数据" :desc="`${loadError}。检查一下网络或服务器，再试一次。`">
        <el-button type="primary" :icon="Refresh" @click="load">再试一次</el-button>
      </EmptyState>
    </div>

    <!-- 还没有实验田 -->
    <div v-else-if="cards.length === 0" class="panel">
      <EmptyState
        art="blocks"
        title="还没有实验田"
        :desc="auth.isTeacher ? '新建一块实验田：定好有几行几列区块、每块多大、里面分几行几列小区，就会自动生成小区。' : '请老师先建实验田，建好后这里会显示生育期和今天的录入情况。'"
        :size="150"
      >
        <el-button v-if="auth.isTeacher" class="btn-wheat" :icon="Plus" @click="fieldDialog = true">新建实验田</el-button>
      </EmptyState>
    </div>

    <template v-else-if="primary">
      <!-- ② 当前实验田 -->
      <section class="hero fade-up" aria-label="当前实验田">
        <div class="hero-main">
          <div class="hero-head">
            <div class="hero-titles">
              <el-dropdown v-if="multiField" trigger="click" @command="switchPrimary">
                <button type="button" class="hero-eyebrow switch">当前实验田<el-icon><ArrowDown /></el-icon></button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item v-for="c in cards" :key="c.field.id" :command="c.field.id">
                      <span class="switch-item">{{ c.field.name }}</span>
                      <el-icon v-if="c.field.id === primaryId" class="switch-check"><Check /></el-icon>
                    </el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
              <div v-else class="hero-eyebrow">当前实验田</div>
              <h3 class="hero-title">{{ primary.field.name }}</h3>
              <div class="hero-meta">
                <span v-if="primary.screen?.trialName" class="hero-trial">{{ primary.screen.trialName }}</span>
                <span>{{ fieldMeta(primary.field, primary.screen) }}</span>
              </div>
            </div>
            <div v-if="ind" class="stage-now">
              <span class="stage-now-label">现在</span>
              <span class="stage-now-value">{{ stage.caption ?? '还没记录生育期' }}</span>
              <span v-if="stageMonths" class="stage-now-hint">一般在 {{ stageMonths }}</span>
            </div>
          </div>

          <template v-if="ind">
            <GrowthStageTrack class="hero-track" :current="ind.currentStage" :size="50" />

            <div class="hero-stats stat-row">
              <StatCard bare label="已种植" :value="ind.planted" :total="ind.totalPlots" unit="个小区" tone="green" />
              <StatCard bare label="参试品种" :value="varietyCount" unit="个" />
              <StatCard bare label="近 7 天录入" :value="ind.weekEntries" unit="条" />
              <StatCard bare label="已收获" :value="ind.harvested" :total="ind.totalPlots" unit="个小区" tone="wheat" />
            </div>
          </template>
          <div v-else class="hero-empty">
            <EmptyState
              art="field"
              :size="84"
              title="这块地还没有试验"
              :desc="auth.isTeacher ? '进入平面图，点右上角「试验 → 新建试验」，再给小区分配品种。' : '请老师在平面图上新建今年的试验。'"
            />
          </div>

          <div class="hero-actions">
            <el-button class="btn-wheat" size="large" @click="openMap(primary.field.id)">
              {{ ind ? '进入平面图录入' : '进入平面图' }}<el-icon class="el-icon--right"><Right /></el-icon>
            </el-button>
            <el-button v-if="ind" size="large" @click="openData(primary.field.id)">查看数据</el-button>
          </div>
        </div>

        <button type="button" class="hero-map" title="打开平面图" @click="openMap(primary.field.id)">
          <span class="map-frame">
            <BlockMiniMap
              v-if="primary.screen?.layout"
              :style="{ width: mapWidth(primary.screen, 236, 340), height: mapHeight(primary.screen, 236, 340) }"
              :layout="primary.screen.layout"
              :plots="primary.screen.plots"
              :entered="enteredToday"
              labels="code"
            />
            <MiniPlotMap
              v-else-if="primary.screen"
              :style="{ width: mapWidth(primary.screen, 236, 340) }"
              :rows="primary.screen.rowCount"
              :cols="primary.screen.colCount"
              :plots="primary.screen.plots"
              :entered="enteredToday"
              labels
            />
          </span>
          <span class="map-legend">
            <span v-for="l in LEGEND" :key="l.key"><i :style="{ background: l.color }" />{{ l.label }}</span>
            <span v-if="enteredToday.length"><i class="today-dot" />今天录过</span>
          </span>
          <span class="map-open">点开平面图<el-icon><ArrowRight /></el-icon></span>
        </button>
      </section>

      <!-- ③ 今天该记什么 -->
      <SectionCard class="today fade-up" title="今天该记什么" :icon="Calendar" tone="wheat" sub="按现在的生育期和记载标准提醒">
        <template v-if="ind" #actions>
          <div class="today-progress">
            <span class="tp-text">今天已录 <b class="num">{{ ind.enteredToday }}</b> / {{ ind.totalPlots }} 个小区</span>
            <span class="meter tp-meter"><i :style="{ width: progress(primary.screen) + '%' }" /></span>
          </div>
        </template>

        <!--
          合并时替换为：<TodayTodoPanel v-if="primaryTrial" :trial-id="primaryTrial.id" />
          （import TodayTodoPanel from '@/desktop/components/TodayTodoPanel.vue'），并删掉下面整个 .todo-temp 临时占位。
          这块区域按 3–6 条待办设计：每条一行，左边项目名和原因，右边"还差几个小区"和"去录入"按钮。
        -->
        <div class="todo-temp">
          <div v-if="!primaryTrial" class="todo-none">这块地还没有试验，建好试验、分好品种后，这里会提醒每天该记哪些项目。</div>
          <div v-else-if="ind && ind.planted === 0" class="todo-none">
            还没给小区分配品种。在平面图上点「布置试验」，选中小区后分配品种，就可以开始录入了。
          </div>
          <EmptyState
            v-else-if="ind && leftToday === 0 && ind.planted > 0"
            art="done"
            :size="88"
            title="今天的小区都录过了"
            desc="辛苦了。明天打开首页，这里会接着提醒该记什么。"
          />
          <div v-else class="todo-row">
            <span class="todo-mark" aria-hidden="true" />
            <div class="todo-text">
              <div class="todo-title">还没录的小区</div>
              <div class="todo-reason">今天已录 {{ ind?.enteredToday ?? 0 }} 个小区，在平面图上点选小区就能录入</div>
            </div>
            <div class="todo-left">还差 <b class="num">{{ leftToday }}</b> 个小区</div>
            <el-button type="primary" @click="openMap(primary.field.id)">去录入</el-button>
          </div>
        </div>
      </SectionCard>

      <!-- ④ 需要处理 -->
      <section class="block fade-up" aria-label="需要处理">
        <div class="block-head">
          <h3>需要处理</h3>
        </div>
        <div class="attn-grid" :class="`n${attention.length}`">
          <button
            v-for="a in attention"
            :key="a.key"
            type="button"
            class="attn"
            :class="[`tone-${a.tone}`, { off: !a.go }]"
            :disabled="!a.go"
            @click="a.go?.()"
          >
            <span class="attn-icon"><el-icon><component :is="a.icon" /></el-icon></span>
            <span class="attn-body">
              <span class="attn-title">
                {{ a.title }}
                <b v-if="a.count != null" class="attn-count num">{{ a.count }}</b>
              </span>
              <span class="attn-desc">{{ a.desc }}</span>
            </span>
            <span v-if="!a.go && a.key !== 'abnormal'" class="chip">即将开放</span>
            <span v-else-if="a.action" class="attn-go">{{ a.action }}<el-icon><ArrowRight /></el-icon></span>
          </button>
        </div>
      </section>

      <!-- ⑤ 最新录入 -->
      <SectionCard class="fade-up" title="最新录入" :sub="multiField ? '所有实验田' : primary.field.name" flush>
        <template #actions>
          <el-button link type="primary" @click="openData(primary.field.id)">
            去数据查看<el-icon class="el-icon--right"><ArrowRight /></el-icon>
          </el-button>
        </template>
        <div v-if="recent.length === 0" class="recent-empty">还没有人录入数据。在平面图上点选小区，录下第一条吧。</div>
        <table v-else class="feed">
          <thead>
            <tr>
              <th class="c-time">时间</th>
              <th class="c-who">录入人</th>
              <th>项目</th>
              <th>小区</th>
              <th>结果</th>
              <th v-if="multiField">实验田</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(r, i) in recent" :key="i">
              <td class="c-time num">{{ timeText(r.at) }}</td>
              <td class="c-who"><span class="who"><span class="who-avatar">{{ r.user.slice(0, 1) }}</span>{{ r.user }}</span></td>
              <td class="c-trait">{{ r.traitName }}</td>
              <td>
                <span v-if="r.plotCount > 1" class="chip">{{ r.plotCount }} 个小区</span>
                <span v-else class="num">小区 {{ r.plotCode }}</span>
              </td>
              <td class="c-value">{{ r.value }}</td>
              <td v-if="multiField" class="faint">{{ r.fieldName }}</td>
            </tr>
          </tbody>
        </table>
      </SectionCard>

      <!-- ⑥ 全部实验田 -->
      <section v-if="multiField" class="block fade-up" aria-label="全部实验田">
        <div class="block-head">
          <h3>全部实验田</h3>
          <span class="faint">{{ cards.length }} 块 · 点卡片进入平面图</span>
        </div>
        <div class="field-grid">
          <article v-for="c in cards" :key="c.field.id" class="fcard" :class="{ on: c.field.id === primaryId }" @click="openMap(c.field.id)">
            <div class="fcard-map">
              <BlockMiniMap
                v-if="c.screen?.layout"
                :style="{ width: mapWidth(c.screen, 150, 260), height: mapHeight(c.screen, 150, 260) }"
                :layout="c.screen.layout"
                :plots="c.screen.plots"
              />
              <MiniPlotMap
                v-else-if="c.screen"
                :style="{ width: mapWidth(c.screen, 150, 260) }"
                :rows="c.screen.rowCount"
                :cols="c.screen.colCount"
                :plots="c.screen.plots"
              />
            </div>
            <div class="fcard-body">
              <div class="fcard-head">
                <span class="fcard-name">{{ c.field.name }}</span>
                <span v-if="c.screen?.indicators?.currentStage" class="chip wheat">{{ c.screen.indicators.currentStage }}</span>
              </div>
              <div class="fcard-meta">{{ c.screen?.trialName ?? '还没有试验' }} · {{ c.screen?.layout ? `${c.screen.layout.blocks.filter((b) => b.kind === 'PLANT').length} 个区块` : `${c.field.rowCount} × ${c.field.colCount}` }}</div>
              <div v-if="c.screen?.indicators" class="fcard-progress">
                <span class="meter"><i :style="{ width: progress(c.screen) + '%' }" /></span>
                <span class="num">今天 {{ c.screen.indicators.enteredToday }} / {{ c.screen.indicators.totalPlots }}</span>
              </div>
            </div>
          </article>
          <button v-if="auth.isTeacher" type="button" class="fcard add" @click="fieldDialog = true">
            <img :src="blocksArt" alt="" class="add-art" draggable="false">
            <span class="add-text"><el-icon><Plus /></el-icon>新建实验田</span>
            <span class="add-hint">区块、道路、小区一次设好</span>
          </button>
        </div>
      </section>
    </template>

    <FieldFormDialog v-model="fieldDialog" @saved="onFieldCreated" />
  </div>
</template>

<style scoped>
.home {
  display: flex;
  flex-direction: column;
  gap: var(--sp-5);
}

.home > :deep(.page-header) {
  margin-bottom: 0;
}

.home > :deep(.page-header) h2 {
  font-size: 26px;
}

/* ---------- ② 当前实验田 ---------- */

.hero {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(300px, 400px);
  min-height: 300px;
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow);
  overflow: hidden;
}

.hero-main {
  display: flex;
  flex-direction: column;
  min-width: 0;
  padding: 22px 28px 24px;
}

.hero-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: var(--sp-4);
}

.hero-titles {
  min-width: 0;
}

.hero-eyebrow {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 0;
  border: none;
  background: none;
  font: inherit;
  font-size: var(--fs-xs);
  letter-spacing: 0.12em;
  color: var(--wheat);
}

.hero-eyebrow.switch {
  cursor: pointer;
}

.hero-eyebrow.switch:hover {
  color: var(--wheat-ink);
}

.hero-title {
  margin: 4px 0 0;
  font-family: var(--font-serif);
  font-size: 22px;
  font-weight: 700;
  color: var(--forest);
  letter-spacing: 0.04em;
  line-height: var(--lh-tight);
}

.hero-meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px 10px;
  margin-top: 6px;
  font-size: var(--fs-sm);
  color: var(--ink-3);
}

.hero-trial {
  color: var(--ink-2);
  font-weight: 600;
}

.hero-trial::after {
  content: '';
  display: inline-block;
  width: 1px;
  height: 11px;
  margin-left: 10px;
  vertical-align: -1px;
  background: var(--line-strong);
}

.stage-now {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  flex-shrink: 0;
  text-align: right;
}

.stage-now-label {
  font-size: var(--fs-xs);
  color: var(--ink-3);
}

.stage-now-value {
  margin-top: 2px;
  font-family: var(--font-serif);
  font-size: var(--fs-xl);
  font-weight: 700;
  color: var(--wheat);
  letter-spacing: 0.06em;
}

.stage-now-hint {
  font-size: var(--fs-xs);
  color: var(--ink-3);
}

.hero-track {
  margin-top: 18px;
}

.hero-stats {
  margin-top: 20px;
  padding-top: 16px;
  border-top: 1px solid var(--line-soft);
}

.hero-empty {
  margin: 8px 0;
  border: 1px dashed var(--line-strong);
  border-radius: var(--radius);
  background: var(--surface-2);
}

.hero-empty :deep(.empty) {
  padding: 18px 16px;
}

.hero-actions {
  display: flex;
  gap: var(--sp-2);
  margin-top: auto;
  padding-top: 20px;
}

/* 右边的平面图：铺在土壤纹理上，整块可以点 */
.hero-map {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 14px;
  padding: 28px 24px 22px;
  border: none;
  border-left: 1px solid var(--line);
  background:
    radial-gradient(ellipse 70% 60% at 50% 42%, rgba(255, 249, 232, 0.5), rgba(255, 249, 232, 0) 70%),
    #e9e1cd url('@/assets/art/soil.jpg') repeat;
  background-size: auto, 192px;
  box-shadow: inset 0 2px 14px rgba(80, 60, 20, 0.1);
  font: inherit;
  color: inherit;
  cursor: pointer;
}

.map-frame {
  display: flex;
  justify-content: center;
  padding: 10px;
  border-radius: 10px;
  background: rgba(255, 253, 248, 0.38);
  box-shadow: 0 0 0 1px rgba(120, 100, 60, 0.14), 0 10px 24px -14px rgba(60, 45, 15, 0.45);
  transition: transform var(--dur) var(--ease), box-shadow var(--dur) var(--ease);
}

.hero-map:hover .map-frame {
  transform: translateY(-2px);
  box-shadow: 0 0 0 1px rgba(120, 100, 60, 0.2), 0 16px 30px -14px rgba(60, 45, 15, 0.55);
}

.map-legend {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 4px 12px;
  font-size: 11.5px;
  color: var(--ink-2);
}

.map-legend span {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}

.map-legend i {
  width: 10px;
  height: 8px;
  border-radius: 2px;
  box-shadow: inset 0 0 0 1px rgba(80, 60, 20, 0.15);
}

.map-legend i.today-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--green);
  box-shadow: none;
}

.map-open {
  position: absolute;
  top: 12px;
  right: 14px;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px 10px;
  border-radius: var(--radius-pill);
  background: rgba(255, 253, 248, 0.82);
  font-size: var(--fs-xs);
  color: var(--ink-2);
  box-shadow: 0 0 0 1px rgba(120, 100, 60, 0.12);
  transition: color var(--dur), background var(--dur), box-shadow var(--dur);
}

.hero-map:hover .map-open,
.hero-map:focus-visible .map-open {
  color: var(--forest);
  background: var(--surface);
  box-shadow: 0 0 0 1px rgba(120, 100, 60, 0.18), var(--shadow-xs);
}

.hero-skeleton {
  padding: 0;
}

.sk-main {
  padding: 24px 28px;
}

.sk-map {
  border-radius: 0;
}

.switch-item {
  min-width: 160px;
}

.switch-check {
  margin-left: 12px;
  color: var(--green);
}

/* ---------- ③ 今天该记什么 ---------- */

.today-progress {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: var(--fs-xs);
  color: var(--ink-3);
}

.today-progress b {
  font-size: var(--fs-sm);
  color: var(--forest);
}

.tp-meter {
  width: 120px;
}

.todo-temp {
  min-height: 64px;
}

.todo-none {
  padding: 14px 0 4px;
  color: var(--ink-3);
  font-size: var(--fs-sm);
}

/* 一条待办：TodayTodoPanel 合并进来以后也按这个样子排 */
.todo-row {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 12px 14px;
  border: 1px solid var(--line-soft);
  border-radius: 12px;
  background: var(--surface-2);
}

.todo-mark {
  width: 4px;
  align-self: stretch;
  border-radius: 2px;
  background: var(--wheat-bright);
}

.todo-text {
  flex: 1;
  min-width: 0;
}

.todo-title {
  font-weight: 600;
  color: var(--ink);
}

.todo-reason {
  margin-top: 2px;
  font-size: var(--fs-xs);
  color: var(--ink-3);
}

.todo-left {
  font-size: var(--fs-sm);
  color: var(--ink-2);
  white-space: nowrap;
}

.todo-left b {
  font-family: var(--font-serif);
  font-size: var(--fs-lg);
  color: var(--wheat);
}

.today :deep(.empty) {
  padding: 8px 16px 6px;
}

/* ---------- 分组标题（一组卡片的标题写在外面） ---------- */

.block-head {
  display: flex;
  align-items: baseline;
  gap: 10px;
  margin-bottom: 12px;
}

.block-head h3 {
  margin: 0;
  font-family: var(--font-serif);
  font-size: 16px;
  font-weight: 700;
  color: var(--forest);
  letter-spacing: 0.03em;
}

.block-head .faint {
  font-size: var(--fs-xs);
}

/* ---------- ④ 需要处理 ---------- */

.attn-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 14px;
}

.attn-grid.n2 {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.attn {
  display: flex;
  align-items: center;
  gap: 14px;
  min-width: 0;
  padding: 16px 18px;
  text-align: left;
  font: inherit;
  color: inherit;
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  box-shadow: var(--shadow);
  cursor: pointer;
  transition: border-color var(--dur-fast), box-shadow var(--dur-fast), transform var(--dur-fast);
}

.attn:not(.off):hover {
  border-color: #d9c48f;
  box-shadow: var(--shadow-lift);
  transform: translateY(-1px);
}

.attn.off {
  cursor: default;
  box-shadow: none;
  background: rgba(255, 253, 248, 0.6);
}

.attn-icon {
  width: 40px;
  height: 40px;
  border-radius: 11px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  font-size: 19px;
}

.tone-clay .attn-icon { background: var(--clay-soft); color: var(--clay); }
.tone-green .attn-icon { background: var(--green-soft); color: var(--green); }
.tone-wheat .attn-icon { background: var(--wheat-soft); color: var(--wheat); }
.tone-sky .attn-icon { background: var(--sky-soft); color: var(--sky); }

.attn-body {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.attn-title {
  display: flex;
  align-items: baseline;
  gap: 8px;
  font-weight: 600;
  color: var(--ink);
}

.attn-count {
  font-family: var(--font-serif);
  font-size: var(--fs-lg);
  color: var(--forest);
}

.tone-clay .attn-count {
  color: var(--clay);
}

.attn-desc {
  margin-top: 3px;
  font-size: var(--fs-xs);
  line-height: 1.55;
  color: var(--ink-3);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.attn-go {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  flex-shrink: 0;
  font-size: var(--fs-sm);
  color: var(--green);
}

/* ---------- ⑤ 最新录入 ---------- */

.recent-empty {
  padding: 16px 20px 22px;
  color: var(--ink-3);
  font-size: var(--fs-sm);
}

.feed {
  width: 100%;
  border-collapse: collapse;
  font-size: var(--fs-sm);
}

.feed th {
  padding: 8px 12px;
  text-align: left;
  font-weight: 600;
  font-size: var(--fs-xs);
  color: var(--ink-3);
  border-bottom: 1px solid var(--line);
}

.feed td {
  padding: 10px 12px;
  border-bottom: 1px solid var(--line-soft);
  color: var(--ink-2);
}

.feed th:first-child,
.feed td:first-child {
  padding-left: 20px;
}

.feed th:last-child,
.feed td:last-child {
  padding-right: 20px;
}

.feed tbody tr:last-child td {
  border-bottom: none;
}

.feed tbody tr:hover td {
  background: var(--surface-2);
}

.c-time {
  width: 120px;
  color: var(--ink-3) !important;
  white-space: nowrap;
}

.c-who {
  width: 150px;
}

.who {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: var(--ink);
}

.who-avatar {
  width: 22px;
  height: 22px;
  border-radius: 50%;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  background: var(--wheat-soft);
  color: var(--wheat-ink);
  font-size: 11px;
  font-weight: 700;
}

.c-trait {
  color: var(--ink) !important;
  font-weight: 500;
}

.c-value {
  color: var(--green) !important;
  font-weight: 600;
}

/* ---------- ⑥ 全部实验田 ---------- */

.field-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 14px;
}

.fcard {
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  box-shadow: var(--shadow);
  cursor: pointer;
  transition: border-color var(--dur-fast), box-shadow var(--dur-fast), transform var(--dur-fast);
}

.fcard:hover {
  border-color: #d9c48f;
  box-shadow: var(--shadow-lift);
  transform: translateY(-1px);
}

.fcard.on {
  border-color: #dcc285;
}

.fcard-map {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 180px;
  padding: 14px;
  box-sizing: border-box;
  background: #e9e1cd url('@/assets/art/soil.jpg') repeat;
  background-size: 192px;
  border-bottom: 1px solid var(--line);
}

.fcard-body {
  padding: 14px 16px 16px;
}

.fcard-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.fcard-name {
  font-family: var(--font-serif);
  font-size: var(--fs-md);
  font-weight: 700;
  color: var(--forest);
}

.fcard-meta {
  margin-top: 4px;
  font-size: var(--fs-xs);
  color: var(--ink-3);
}

.fcard-progress {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 12px;
  font-size: var(--fs-xs);
  color: var(--ink-2);
}

.fcard-progress .meter {
  flex: 1;
}

.fcard.add {
  align-items: center;
  justify-content: center;
  gap: 6px;
  min-height: 240px;
  flex-direction: column;
  font: inherit;
  color: var(--ink-3);
  background: transparent;
  border: 1.5px dashed var(--line-strong);
  box-shadow: none;
}

.fcard.add:hover {
  color: var(--green);
  border-color: var(--green-2);
  background: rgba(255, 253, 248, 0.6);
}

.add-art {
  width: 62%;
  max-width: 220px;
  opacity: 0.55;
  filter: saturate(0.7);
  transition: opacity var(--dur) var(--ease), filter var(--dur) var(--ease), transform var(--dur) var(--ease);
}

.fcard.add:hover .add-art {
  opacity: 1;
  filter: none;
  transform: translateY(-2px);
}

.add-text {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: var(--fs-md);
  font-weight: 600;
}

.add-hint {
  font-size: var(--fs-xs);
  color: var(--ink-3);
}

/* ---------- 窄一点的窗口 ---------- */

@media (max-width: 1360px) {
  .hero {
    grid-template-columns: minmax(0, 1fr) 320px;
  }

  .hero-main {
    padding: 20px 22px 22px;
  }

  .hero-stats.stat-row > * + * {
    padding-left: 14px;
  }
}
</style>
