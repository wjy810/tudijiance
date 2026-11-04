<script setup lang="ts">
// 试验记载本：按《河南省小麦品种试验记载本》填写封面、试验设计、栽培管理、产量计算参数、气象资料和品种评价。
// 田间记载、考种、产量这些按小区记的项目在平面图上录入，这里只放按试验记的部分
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Check, Document, MapLocation, Sunny, Tickets } from '@element-plus/icons-vue'

import { fieldApi, trialApi, type FieldDetail, type Layout, type TrialBook } from '@/shared/api'
import {
  BOOK_GROUPS, WEATHER_ANALYSIS_KEY, WEATHER_MONTHS, WEATHER_ROWS, derivedWeather, harvestArea, mergePrefill,
  prefillFromSetup, weatherKey, type BookField, type WeatherKind,
} from '@/shared/recordBook'
import { useAuthStore } from '@/stores/auth'
import { useCatalogStore } from '@/stores/catalog'
import PageHeader from '@/desktop/components/PageHeader.vue'
import SectionCard from '@/desktop/components/SectionCard.vue'
import EmptyState from '@/desktop/components/EmptyState.vue'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const catalog = useCatalogStore()

const trialId = computed(() => Number(route.params.id))
const layout = ref<Layout | null>(null)
const detail = ref<FieldDetail | null>(null)
const book = ref<TrialBook | null>(null)
const loading = ref(true)
const loadError = ref('')
const saving = ref(false)
const tab = ref<'info' | 'weather' | 'evaluation'>('info')

const info = ref<Record<string, string>>({})
const weather = ref<Record<string, string>>({})
const evaluations = ref<Record<string, string>>({})
/** 保存时的内容，用来判断有没有改过 */
const saved = ref('')
const prefilledCount = ref(0)

const trial = computed(() => layout.value?.trial ?? null)
const field = computed(() => detail.value?.field ?? null)
const readonly = computed(() => !auth.isTeacher)

const snapshot = () => JSON.stringify([info.value, weather.value, evaluations.value])
const dirty = computed(() => !loading.value && snapshot() !== saved.value)

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    const [l, b] = await Promise.all([trialApi.layout(trialId.value), trialApi.book(trialId.value), catalog.loadVarieties()])
    layout.value = l
    book.value = b
    detail.value = await fieldApi.detail(l.trial.fieldId)
    info.value = { ...b.info }
    weather.value = { ...b.weather }
    evaluations.value = { ...b.evaluations }
    saved.value = snapshot()
    // 第一次打开：和实验田、试验、小区重复的项目先按已有资料填上，保存后才算数
    if (!b.updatedAt && auth.isTeacher) {
      const prefill = prefillFromSetup(field.value, l.trial, detail.value.plots, l, catalog.varietyById)
      const merged = mergePrefill(info.value, prefill)
      prefilledCount.value = Object.keys(merged).length - Object.keys(info.value).length
      info.value = merged
    }
  } catch (e) {
    loadError.value = (e as Error).message
  } finally {
    loading.value = false
  }
}

async function save() {
  if (readonly.value || saving.value) return
  saving.value = true
  try {
    const b = await trialApi.saveBook(trialId.value, {
      info: info.value, weather: weather.value, evaluations: evaluations.value,
    })
    book.value = b
    info.value = { ...b.info }
    weather.value = { ...b.weather }
    evaluations.value = { ...b.evaluations }
    saved.value = snapshot()
    prefilledCount.value = 0
    ElMessage.success(b.harvestAreaM2 ? `已保存，折合亩产按收获面积 ${b.harvestAreaM2} m² 计算` : '已保存')
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    saving.value = false
  }
}

function onKey(e: KeyboardEvent) {
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 's') {
    e.preventDefault()
    save()
  }
}

onMounted(() => {
  load()
  window.addEventListener('keydown', onKey)
})
onBeforeUnmount(() => window.removeEventListener('keydown', onKey))

onBeforeRouteLeave(async () => {
  if (!dirty.value || readonly.value) return true
  try {
    await ElMessageBox.confirm('记载本改了还没保存，离开后改动会丢掉。', '还没保存', {
      confirmButtonText: '不保存，离开', cancelButtonText: '留在这里', type: 'warning',
    })
    return true
  } catch {
    return false
  }
})

const title = computed(() => trial.value?.name ?? '试验记载本')

const lastSaved = computed(() => {
  const b = book.value
  if (!b?.updatedAt) return '还没保存过'
  const t = b.updatedAt.replace('T', ' ').slice(0, 16)
  return `${b.updatedBy ?? ''} ${t} 保存`
})

// ---------- 基本情况 ----------

const area = computed(() => harvestArea(info.value))

function fieldSpan(f: BookField): number {
  return f.span ?? (f.type === 'textarea' ? 4 : 1)
}

const filledCount = computed(() =>
  BOOK_GROUPS.map((g) => g.fields.filter((f) => info.value[f.key]?.trim()).length))

// ---------- 气象资料 ----------

const weatherGroups = computed(() => {
  const groups: { group: string; rows: typeof WEATHER_ROWS }[] = []
  for (const r of WEATHER_ROWS) {
    const last = groups[groups.length - 1]
    if (last && last.group === r.group) last.rows.push(r)
    else groups.push({ group: r.group, rows: [r] })
  }
  return groups
})

function cell(row: string, month: number, kind: WeatherKind): string {
  return weather.value[weatherKey(row, month, kind)] ?? ''
}

function setCell(row: string, month: number, kind: WeatherKind, value: string) {
  const v = value.replace(/[^\d.\-]/g, '')
  const key = weatherKey(row, month, kind)
  const next = { ...weather.value }
  if (v === '') delete next[key]
  else next[key] = v
  weather.value = next
}

const weatherFilled = computed(() => Object.keys(weather.value).length)

// ---------- 品种评价 ----------

const trialVarieties = computed(() => {
  const ids = [...new Set((layout.value?.plantings ?? []).map((p) => p.varietyId).filter((v): v is number => v != null))]
  return ids
    .map((id) => catalog.varietyById.get(id))
    .filter((v) => !!v)
    .sort((a, b) => Number(!!b!.controlType) - Number(!!a!.controlType) || a!.name.localeCompare(b!.name, 'zh'))
})

const evaluatedCount = computed(() => trialVarieties.value.filter((v) => evaluations.value[String(v!.id)]?.trim()).length)

function setEvaluation(id: number, text: string) {
  const next = { ...evaluations.value }
  if (text.trim() === '') delete next[String(id)]
  else next[String(id)] = text
  evaluations.value = next
}

function setInfo(key: string, value: string | null | undefined) {
  const next = { ...info.value }
  if (value == null || String(value).trim() === '') delete next[key]
  else next[key] = String(value)
  info.value = next
}
</script>

<template>
  <div class="page book-page">
    <PageHeader :title="title" eyebrow="试验记载本">
      <template #desc>
        <template v-if="field">{{ field.name }} · {{ trial?.seasonYear }} 年收获 · </template>
        按《河南省小麦品种试验记载本》填写。田间记载、室内考种、小区产量按小区记，在平面图上录入。
      </template>
      <template #actions>
        <span class="save-state" :class="{ dirty }">{{ dirty ? '有改动未保存' : lastSaved }}</span>
        <el-button v-if="field" :icon="MapLocation" @click="router.push({ path: `/fields/${field.id}`, query: { trial: trialId } })">
          回平面图
        </el-button>
        <el-button v-if="!readonly" type="primary" :icon="Check" :loading="saving" :disabled="!dirty" @click="save">
          保存<kbd class="kbd-light">Ctrl S</kbd>
        </el-button>
      </template>
    </PageHeader>

    <div v-if="loading" class="skeleton-wrap">
      <div class="skeleton" style="height: 44px; width: 360px" />
      <div class="skeleton" style="height: 220px" />
      <div class="skeleton" style="height: 320px" />
    </div>

    <SectionCard v-else-if="loadError">
      <EmptyState art="data" title="没取到记载本" :desc="`${loadError}。检查一下网络或服务器。`">
        <el-button type="primary" @click="load">再试一次</el-button>
      </EmptyState>
    </SectionCard>

    <template v-else>
      <el-alert
        v-if="prefilledCount > 0"
        type="success"
        show-icon
        :closable="false"
        class="prefill-tip"
        :title="`已按实验田、试验和小区的资料预先填好 ${prefilledCount} 项（年度、地点、对照品种、小区尺寸等），检查一下再点保存。`"
      />
      <el-alert v-if="readonly" type="info" :closable="false" show-icon title="记载本由老师填写，这里只能查看。" class="prefill-tip" />

      <div class="book-tabs" role="tablist">
        <button :class="{ on: tab === 'info' }" role="tab" @click="tab = 'info'">
          <el-icon><Document /></el-icon>基本情况
        </button>
        <button :class="{ on: tab === 'weather' }" role="tab" @click="tab = 'weather'">
          <el-icon><Sunny /></el-icon>气象资料<span v-if="weatherFilled" class="count num">{{ weatherFilled }}</span>
        </button>
        <button :class="{ on: tab === 'evaluation' }" role="tab" @click="tab = 'evaluation'">
          <el-icon><Tickets /></el-icon>品种评价
          <span v-if="trialVarieties.length" class="count num">{{ evaluatedCount }}/{{ trialVarieties.length }}</span>
        </button>
      </div>

      <!-- 基本情况 -->
      <div v-show="tab === 'info'" class="groups">
        <SectionCard
          v-for="(g, gi) in BOOK_GROUPS"
          :key="g.key"
          :title="g.title"
          :sub="`已填 ${filledCount[gi]} / ${g.fields.length}`"
        >
          <p v-if="g.desc" class="group-desc">{{ g.desc }}</p>
          <div class="form-grid">
            <label
              v-for="f in g.fields"
              :key="f.key"
              class="form-item"
              :style="{ gridColumn: `span ${fieldSpan(f)}` }"
            >
              <span class="form-label">{{ f.label }}<em v-if="f.unit">（{{ f.unit }}）</em></span>
              <el-date-picker
                v-if="f.type === 'date'"
                :model-value="info[f.key] ?? ''"
                type="date"
                value-format="YYYY-MM-DD"
                format="YYYY 年 M 月 D 日"
                :disabled="readonly"
                :placeholder="f.hint ?? '选择日期'"
                style="width: 100%"
                @update:model-value="(v: string) => setInfo(f.key, v)"
              />
              <el-select
                v-else-if="f.type === 'select'"
                :model-value="info[f.key] ?? ''"
                :disabled="readonly"
                clearable
                placeholder="选择"
                @update:model-value="(v: string) => setInfo(f.key, v)"
              >
                <el-option v-for="o in f.options" :key="o" :value="o" :label="o" />
              </el-select>
              <el-input
                v-else-if="f.type === 'textarea'"
                :model-value="info[f.key] ?? ''"
                type="textarea"
                :autosize="{ minRows: 2, maxRows: 8 }"
                :disabled="readonly"
                :placeholder="f.hint ?? '写清楚时间、种类、数量和方法'"
                @update:model-value="(v: string) => setInfo(f.key, v)"
              />
              <el-input
                v-else
                :model-value="info[f.key] ?? ''"
                :inputmode="f.type === 'number' ? 'decimal' : undefined"
                :disabled="readonly"
                :placeholder="f.hint ?? ''"
                @update:model-value="(v: string) => setInfo(f.key, f.type === 'number' ? v.replace(/[^\d.\-]/g, '') : v)"
              />
            </label>
          </div>
          <div v-if="g.key === 'yield'" class="area-result">
            <span class="area-label">收获面积</span>
            <template v-if="area != null">
              <b class="num">{{ area }}</b><span class="unit">m²</span>
              <span class="hint">
                {{ info.harvestArea ? '按直接填的' : `= ${info.harvestLength || info.plotLength} ×［(${info.rowsPerPlot} − 1) × ${info.rowSpacing} + ${info.ridgeWidth}］÷ 100` }}
              </span>
            </template>
            <span v-else class="hint">还算不出来：要有收获长度（或小区长度）、每小区行数、行距和畦埂宽。没有时折合亩产按小区的长 × 宽算。</span>
          </div>
        </SectionCard>
      </div>

      <!-- 气象资料 -->
      <SectionCard
        v-show="tab === 'weather'"
        :title="`${trial ? `${trial.seasonYear - 1}-${trial.seasonYear} 年度` : ''}小麦生育期间气象资料`"
        sub="当年和常年对比。气象资料不好取时可以不填。"
        flush
      >
        <div class="weather-scroll">
          <table class="weather">
            <thead>
              <tr>
                <th rowspan="2" colspan="2" class="corner">项目</th>
                <th v-for="m in WEATHER_MONTHS" :key="m" colspan="2" class="month">{{ m }} 月</th>
              </tr>
              <tr>
                <template v-for="m in WEATHER_MONTHS" :key="m">
                  <th class="kind">当年</th>
                  <th class="kind norm">常年</th>
                </template>
              </tr>
            </thead>
            <tbody>
              <template v-for="g in weatherGroups" :key="g.group">
                <tr v-for="(r, ri) in g.rows" :key="r.key" :class="{ derived: !!r.derive }">
                  <th v-if="ri === 0" :rowspan="g.rows.length" class="group">{{ g.group }}</th>
                  <th class="row-label">{{ r.label }}<em>{{ r.unit }}</em></th>
                  <template v-for="m in WEATHER_MONTHS" :key="m">
                    <td v-for="k in (['cur', 'norm'] as const)" :key="k" :class="{ norm: k === 'norm' }">
                      <input
                        class="num"
                        inputmode="decimal"
                        :value="cell(r.key, m, k)"
                        :disabled="readonly"
                        :placeholder="derivedWeather(weather, r, m, k)?.toString() ?? ''"
                        :aria-label="`${m} 月 ${r.group} ${r.label} ${k === 'cur' ? '当年' : '常年'}`"
                        @change="setCell(r.key, m, k, ($event.target as HTMLInputElement).value)"
                      >
                    </td>
                  </template>
                </tr>
              </template>
            </tbody>
          </table>
        </div>
        <template #footer>
          月平均气温、月降水总量、月日照时数没填时，灰字是按三旬算出来的参考值。
        </template>
      </SectionCard>

      <!-- 品种评价 -->
      <div v-show="tab === 'evaluation'" class="groups">
        <SectionCard title="气象分析" sub="本年度气候对小麦生长的影响">
          <el-input
            :model-value="info[WEATHER_ANALYSIS_KEY] ?? ''"
            type="textarea"
            :autosize="{ minRows: 3, maxRows: 10 }"
            :disabled="readonly"
            placeholder="例如：播种期墒情适宜，冬前积温偏高，春季倒春寒较轻……"
            @update:model-value="(v: string) => setInfo(WEATHER_ANALYSIS_KEY, v)"
          />
        </SectionCard>
        <SectionCard title="分品种评价" :sub="`共 ${trialVarieties.length} 个品种，已写 ${evaluatedCount} 个`">
          <EmptyState
            v-if="trialVarieties.length === 0"
            art="field"
            title="这个试验还没分配品种"
            desc="在平面图的“布置试验”里给小区分配品种后，这里会列出各个品种。"
          />
          <div v-else class="eval-list">
            <div v-for="v in trialVarieties" :key="v!.id" class="eval-item">
              <div class="eval-head">
                <span class="eval-name">{{ v!.name }}</span>
                <span v-if="v!.code" class="muted num">{{ v!.code }}</span>
                <span v-if="v!.controlType" class="chip wheat">对照</span>
              </div>
              <el-input
                :model-value="evaluations[String(v!.id)] ?? ''"
                type="textarea"
                :autosize="{ minRows: 2, maxRows: 8 }"
                :disabled="readonly"
                placeholder="产量表现、抗性、熟相、优缺点和建议"
                @update:model-value="(t: string) => setEvaluation(v!.id, t)"
              />
            </div>
          </div>
        </SectionCard>
      </div>
    </template>
  </div>
</template>

<style scoped>
.book-page {
  display: flex;
  flex-direction: column;
  gap: var(--sp-4);
}

.save-state {
  font-size: var(--fs-sm);
  color: var(--ink-3);
  margin-right: var(--sp-2);
}

.save-state.dirty {
  color: var(--wheat-ink);
  font-weight: 600;
}

.kbd-light {
  margin-left: 8px;
  font-size: 11px;
  opacity: 0.75;
  font-family: inherit;
}

.skeleton-wrap {
  display: flex;
  flex-direction: column;
  gap: var(--sp-4);
}

.prefill-tip {
  margin: 0;
}

/* 分页：像记载本的书签 */
.book-tabs {
  display: flex;
  gap: 4px;
  border-bottom: 1px solid var(--line);
}

.book-tabs button {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 9px 18px;
  border: 1px solid transparent;
  border-bottom: none;
  border-radius: var(--radius-sm) var(--radius-sm) 0 0;
  background: transparent;
  color: var(--ink-2);
  font-size: var(--fs-base);
  cursor: pointer;
  margin-bottom: -1px;
  transition: background var(--dur-fast) var(--ease), color var(--dur-fast) var(--ease);
}

.book-tabs button:hover {
  color: var(--forest);
  background: var(--surface-2);
}

.book-tabs button.on {
  background: var(--surface);
  border-color: var(--line);
  color: var(--forest);
  font-weight: 600;
  box-shadow: inset 0 2px 0 var(--wheat);
}

.book-tabs button:focus-visible {
  outline: none;
  box-shadow: var(--ring);
}

.count {
  font-size: 11px;
  padding: 0 6px;
  border-radius: var(--radius-pill);
  background: var(--paper-deep);
  color: var(--ink-2);
  line-height: 18px;
}

.groups {
  display: flex;
  flex-direction: column;
  gap: var(--sp-4);
}

.group-desc {
  margin: 0 0 var(--sp-3);
  font-size: var(--fs-sm);
  color: var(--ink-2);
  line-height: 1.7;
}

.form-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px 18px;
}

.form-item {
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-width: 0;
}

.form-label {
  font-size: var(--fs-sm);
  color: var(--ink-2);
}

.form-label em {
  font-style: normal;
  color: var(--ink-3);
}

.area-result {
  margin-top: var(--sp-4);
  padding: 12px 16px;
  border-radius: var(--radius-sm);
  background: var(--green-wash);
  border: 1px solid var(--green-soft);
  display: flex;
  align-items: baseline;
  gap: 8px;
  flex-wrap: wrap;
}

.area-label {
  font-size: var(--fs-sm);
  color: var(--ink-2);
}

.area-result b {
  font-family: var(--font-serif);
  font-size: var(--fs-xl);
  color: var(--forest);
}

.area-result .unit {
  color: var(--ink-3);
  margin-right: 8px;
}

/* 气象资料表 */
.weather-scroll {
  overflow-x: auto;
}

.weather {
  border-collapse: collapse;
  font-size: var(--fs-sm);
  min-width: 100%;
}

.weather th,
.weather td {
  border-bottom: 1px solid var(--line-soft);
  border-right: 1px solid var(--line-soft);
}

.weather thead th {
  background: var(--surface-2);
  color: var(--ink-2);
  font-weight: 600;
  padding: 6px 4px;
  position: sticky;
  top: 0;
}

.weather .month {
  border-bottom: 1px solid var(--line);
  color: var(--forest);
}

.weather .kind {
  font-weight: normal;
  font-size: var(--fs-xs);
}

.weather .kind.norm,
.weather td.norm {
  background: var(--surface-2);
}

.weather tbody th {
  text-align: left;
  font-weight: normal;
  padding: 0 10px;
  white-space: nowrap;
  background: var(--surface);
  position: sticky;
  left: 0;
}

.weather .group {
  color: var(--forest);
  font-weight: 600;
  left: 0;
  min-width: 70px;
}

.weather .row-label {
  left: 70px;
  min-width: 108px;
}

.weather .row-label em {
  font-style: normal;
  color: var(--ink-3);
  margin-left: 4px;
  font-size: var(--fs-xs);
}

.weather tr.derived th,
.weather tr.derived td {
  border-bottom-color: var(--line);
}

.weather tr.derived .row-label {
  font-weight: 600;
}

.weather td input {
  width: 52px;
  height: 30px;
  border: none;
  background: transparent;
  text-align: right;
  padding: 0 6px;
  font-size: var(--fs-sm);
  color: var(--ink);
}

.weather td input::placeholder {
  color: var(--ink-4);
}

.weather td input:focus {
  outline: 2px solid var(--green-2);
  outline-offset: -2px;
  background: var(--surface);
}

.weather td input:disabled {
  color: var(--ink-2);
}

.eval-list {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px 18px;
}

.eval-item {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.eval-head {
  display: flex;
  align-items: center;
  gap: 8px;
}

.eval-name {
  font-weight: 600;
  color: var(--forest);
}

@media (max-width: 1360px) {
  .form-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .form-item[style*='span 4'] {
    grid-column: span 2 !important;
  }

  .eval-list {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
