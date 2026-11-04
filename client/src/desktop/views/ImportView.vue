<script setup lang="ts">
// 导入历史表格（老师）：选文件 → 整理表格（选区域、定方向、对应字段、改格子）→ 检查 → 导入。
// 字段对应到录入项目；录入项目里没有的可以就地新建，表头会记成录入项目的别名，下次导入自动认出
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Aim, Back, CircleCheck, Download, MagicStick, Plus, RefreshLeft, Right, UploadFilled } from '@element-plus/icons-vue'
import type { UploadFile } from 'element-plus'

import {
  importApi, type CellEdit, type ImportCellError, type ImportFieldMapping, type ImportMapping, type ImportOrientation,
  type ImportPreview, type ImportRange, type ImportResult, type TraitCategory, type TraitView,
} from '@/shared/api'
import { TRAIT_CATEGORY } from '@/shared/labels'
import { useTrialPicker } from '@/shared/composables/useTrialPicker'
import {
  aliasKey, fieldsOf, guessOrientation, guessRange, guessTarget, guessTargets, importable, rangeText, suggestCustomTrait,
  type ColumnTarget, type CustomTraitSuggestion,
} from '@/shared/importGuess'
import { useCatalogStore } from '@/stores/catalog'
import SheetGrid from '@/desktop/components/SheetGrid.vue'
import type { FieldTone } from '@/desktop/components/sheetGrid'
import EmptyState from '@/desktop/components/EmptyState.vue'
import CustomTraitDialog from '@/desktop/components/CustomTraitDialog.vue'

const router = useRouter()
const catalog = useCatalogStore()
const picker = useTrialPicker()

const STEPS = ['选择文件', '整理表格', '检查', '完成']
const step = ref(0)
const file = ref<File | null>(null)
const preview = ref<ImportPreview | null>(null)
const busy = ref(false)

onMounted(async () => {
  try {
    await Promise.all([picker.init(), catalog.loadTraits()])
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
})

// ---------- 第 1 步：选文件 ----------

async function onFile(upload: UploadFile) {
  if (!upload.raw) {
    return
  }
  if (!picker.trialId.value) {
    ElMessage.warning('先选择要导入到哪个试验')
    return
  }
  busy.value = true
  try {
    preview.value = await importApi.preview(upload.raw)
    file.value = upload.raw
    if (preview.value.sheets.every((s) => s.rowCount === 0)) {
      ElMessage.warning('表格是空的')
      return
    }
    settings.sheet = Math.max(0, preview.value.sheets.findIndex((s) => s.rowCount > 0))
    resetSheet()
    step.value = 1
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    busy.value = false
  }
}

async function downloadTemplate() {
  if (!picker.trial.value) {
    ElMessage.warning('先选择试验')
    return
  }
  try {
    await importApi.template(picker.trial.value.id, `${picker.trial.value.name}-导入模板.xlsx`)
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

// ---------- 第 2 步：整理表格 ----------

const settings = reactive({
  sheet: 0,
  orientation: 'COLUMNS' as ImportOrientation,
  range: null as ImportRange | null,
  observedOn: '' as string,
  assignLayout: true,
  createVarieties: true,
  rememberHeaders: true,
})

/** 页面上改过的格子："行,列" → 新内容 */
const edits = ref(new Map<string, string>())
/** 每个字段对应什么，按区域里的字段顺序 */
const targets = ref<ColumnTarget[]>([])
/** 检查出来的问题格子："行,列" → 原因 */
const errorCells = ref(new Map<string, string>())
const focusCell = ref<{ r: number; c: number } | null>(null)

const sheet = computed(() => preview.value?.sheets[settings.sheet] ?? null)
/** 套用改过的格子以后的表格内容 */
const rows = computed(() => {
  const base = sheet.value?.rows ?? []
  if (edits.value.size === 0) {
    return base
  }
  const copy = base.map((r) => [...r])
  for (const [k, v] of edits.value) {
    const [r, c] = k.split(',').map(Number) as [number, number]
    while (copy.length <= r) copy.push([])
    copy[r]![c] = v
  }
  return copy
})
const colCount = computed(() => Math.max(sheet.value?.colCount ?? 0, ...rows.value.map((r) => r.length)))
const fields = computed(() => (settings.range ? fieldsOf(rows.value, settings.range, settings.orientation) : []))

function defaultDate(): string {
  // 往年的试验默认记在收获时节，当年的默认今天
  const year = picker.trial.value?.seasonYear
  const today = new Date()
  if (year && year < today.getFullYear()) {
    return `${year}-06-15`
  }
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${today.getFullYear()}-${pad(today.getMonth() + 1)}-${pad(today.getDate())}`
}

/** 换了文件或工作表：重新猜方向、区域和字段 */
function resetSheet() {
  edits.value = new Map()
  errorCells.value = new Map()
  settings.observedOn = defaultDate()
  autoDetect()
}

function autoDetect() {
  if (!sheet.value) {
    return
  }
  settings.orientation = guessOrientation(rows.value)
  settings.range = guessRange(rows.value, settings.orientation)
  guessAll()
}

function guessAll() {
  targets.value = guessTargets(fields.value.map((f) => f.header), catalog.traits)
}

function setOrientation(o: ImportOrientation) {
  settings.orientation = o
  settings.range = guessRange(rows.value, o)
  guessAll()
}

function setRange(range: ImportRange) {
  settings.range = range
  guessAll()
}

watch(() => settings.sheet, () => {
  if (step.value === 1) resetSheet()
})

function onEdit(r: number, c: number, value: string) {
  const next = new Map(edits.value)
  const original = sheet.value?.rows[r]?.[c] ?? ''
  if (value === original) {
    next.delete(`${r},${c}`)
  } else {
    next.set(`${r},${c}`, value)
  }
  edits.value = next
  errorCells.value.delete(`${r},${c}`)
  // 改的是表头时重新猜这个字段
  const f = fieldAt(r, c)
  if (f?.header) {
    targets.value[f.field] = guessTarget(value, catalog.traits)
    targets.value = [...targets.value]
  }
}

function fieldAt(r: number, c: number): { field: number; header: boolean } | null {
  const range = settings.range
  if (!range || r < range.top || r > range.bottom || c < range.left || c > range.right) return null
  return settings.orientation === 'COLUMNS'
    ? { field: c - range.left, header: r === range.top }
    : { field: r - range.top, header: c === range.left }
}

function undoEdits() {
  edits.value = new Map()
  guessAll()
}

/** 下拉框的值用字符串表示，录入项目用 "t:编号" */
function targetKey(t: ColumnTarget | undefined): string {
  if (!t || t.kind === 'ignore') {
    return 'ignore'
  }
  return t.kind === 'trait' ? `t:${t.traitId}` : t.kind
}

function setTarget(index: number, key: string) {
  const next: ColumnTarget = key.startsWith('t:')
    ? { kind: 'trait', traitId: Number(key.slice(2)) }
    : { kind: key as 'ignore' | 'plot' | 'variety' | 'replicate' | 'date' }
  // 小区编号、品种、重复、调查日期各只能有一个字段
  if (next.kind !== 'trait' && next.kind !== 'ignore') {
    targets.value = targets.value.map((t) => (t.kind === next.kind ? { kind: 'ignore' } : t))
  }
  targets.value[index] = next
  targets.value = [...targets.value]
}

const tones = computed(() => {
  const map = new Map<number, FieldTone>()
  targets.value.forEach((t, i) => map.set(i, t.kind === 'ignore' ? 'ignore' : t.kind === 'trait' ? 'trait' : 'key'))
  return map
})

const traitGroups = computed(() => {
  const groups = new Map<TraitCategory, TraitView[]>()
  for (const t of catalog.traits) {
    if (importable(t)) {
      groups.set(t.trait.category, [...(groups.get(t.trait.category) ?? []), t])
    }
  }
  return [...groups.entries()].map(([c, items]) => ({ label: TRAIT_CATEGORY[c], items }))
})

function traitLabel(t: TraitView): string {
  if (t.trait.code === 'yield_per_mu') {
    return '折合亩产（换算成小区产量）'
  }
  return t.trait.unit && t.trait.unit !== '级' ? `${t.trait.name}（${t.trait.unit}）` : t.trait.name
}

/** 字段是按录入项目里记下的别名认出来的 */
function byAlias(index: number): boolean {
  const t = targets.value[index]
  const header = fields.value[index]?.header ?? ''
  return t?.kind === 'trait' && !!catalog.traitById.get(t.traitId)?.aliases?.includes(aliasKey(header))
}

const indexOf = (kind: ColumnTarget['kind']) => {
  const i = targets.value.findIndex((t) => t.kind === kind)
  return i < 0 ? null : i
}

const mappedTraits = computed(() =>
  targets.value.flatMap((t, index) => (t.kind === 'trait' ? [{ index, traitId: t.traitId }] : [])))
const needsDate = computed(() => mappedTraits.value.some((m) => catalog.traitById.get(m.traitId)?.trait.dataType !== 'DATE'))
const canAssignLayout = computed(() => indexOf('plot') !== null && (indexOf('variety') !== null || indexOf('replicate') !== null))
const unmatched = computed(() => fields.value.filter((f, i) => targets.value[i]?.kind === 'ignore' && f.header && f.samples.length > 0))

const mappingProblem = computed(() => {
  if (!settings.range) {
    return '请在表格上拖动鼠标，选出要导入的数据区域'
  }
  if (indexOf('plot') === null && indexOf('variety') === null) {
    return '请指定"小区编号"字段，或者"品种"字段（再加上"重复"字段），用来找到每条记录对应的小区'
  }
  if (mappedTraits.value.length === 0) {
    return '请至少指定一个要导入的录入项目'
  }
  if (needsDate.value && indexOf('date') === null && !settings.observedOn) {
    return '请填写调查日期'
  }
  return null
})

const ROLE: Record<string, ImportFieldMapping['role']> = {
  plot: 'PLOT_CODE', variety: 'VARIETY', replicate: 'REPLICATE', date: 'OBSERVED_ON',
}

function mapping(dryRun: boolean): ImportMapping {
  const fieldList: ImportFieldMapping[] = targets.value.flatMap((t, index): ImportFieldMapping[] => {
    if (t.kind === 'ignore') return []
    return t.kind === 'trait' ? [{ index, role: 'TRAIT', traitId: t.traitId }] : [{ index, role: ROLE[t.kind]! }]
  })
  const editList: CellEdit[] = [...edits.value].map(([k, value]) => {
    const [row, col] = k.split(',').map(Number) as [number, number]
    return { row, col, value }
  })
  return {
    trialId: picker.trialId.value!,
    sheet: settings.sheet,
    range: settings.range!,
    orientation: settings.orientation,
    fields: fieldList,
    observedOn: settings.observedOn || null,
    assignLayout: canAssignLayout.value && settings.assignLayout,
    createVarieties: canAssignLayout.value && settings.assignLayout && settings.createVarieties,
    edits: editList,
    rememberHeaders: settings.rememberHeaders,
    dryRun,
  }
}

// ---------- 按表头新建录入项目 ----------

const customOpen = ref(false)
const customPreset = ref<(CustomTraitSuggestion & { header: string }) | null>(null)
const customFor = ref<number | null>(null)

function openCustom(index: number) {
  const f = fields.value[index]
  if (!f) return
  // 猜类型用这个字段下所有的值，不只是前几个
  const all = fieldsOf(rows.value, settings.range!, settings.orientation, 200)[index]?.samples ?? f.samples
  customPreset.value = { ...suggestCustomTrait(f.header, all), header: f.header }
  customFor.value = index
  customOpen.value = true
}

async function onCustomCreated(t: TraitView) {
  await catalog.loadTraits(true)
  if (customFor.value !== null) {
    setTarget(customFor.value, `t:${t.trait.id}`)
  }
}

// ---------- 第 3 步：检查、导入 ----------

const check = ref<ImportResult | null>(null)
const result = ref<ImportResult | null>(null)

async function runCheck() {
  if (mappingProblem.value) {
    ElMessage.warning(mappingProblem.value)
    return
  }
  busy.value = true
  try {
    check.value = await importApi.run(file.value!, mapping(true))
    errorCells.value = new Map(check.value.errors.map((e) => [`${e.r},${e.c}`, e.message]))
    step.value = 2
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    busy.value = false
  }
}

/** 从问题列表回到表格，定位到出错的格子直接改 */
function fixCell(e: ImportCellError) {
  step.value = 1
  focusCell.value = { r: e.r, c: e.c }
}

async function runImport() {
  busy.value = true
  try {
    result.value = await importApi.run(file.value!, mapping(false))
    if (result.value.aliasesRemembered > 0) {
      await catalog.loadTraits(true)
    }
    step.value = 3
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    busy.value = false
  }
}

function restart() {
  step.value = 0
  file.value = null
  preview.value = null
  check.value = null
  result.value = null
  edits.value = new Map()
  errorCells.value = new Map()
}

const orientationHelp = computed(() => settings.orientation === 'COLUMNS'
  ? '第一行是表头，每一列是一个字段，下面每一行是一个小区'
  : '第一列是表头，每一行是一个字段，右边每一列是一个小区')
</script>

<template>
  <div class="page import" v-loading="busy">
    <div class="page-header">
      <div>
        <h2>导入历史表格</h2>
        <div class="page-desc">把以前记在 Excel 里的数据导进来，和新录入的数据一起统计。按列、按行排的表都能导，字段对应到录入项目。</div>
      </div>
    </div>

    <div class="steps panel">
      <div v-for="(s, i) in STEPS" :key="s" class="step" :class="{ done: i < step, now: i === step }">
        <span class="dot">
          <el-icon v-if="i < step"><CircleCheck /></el-icon>
          <template v-else>{{ i + 1 }}</template>
        </span>
        <span class="label">{{ s }}</span>
        <span v-if="i < STEPS.length - 1" class="bar" />
      </div>
    </div>

    <!-- 第 1 步：选文件 -->
    <section v-if="step === 0" class="panel panel-pad">
      <div class="panel-title">导入到哪个试验</div>
      <div class="pick">
        <el-select :model-value="picker.fieldId.value" placeholder="实验田" class="w200" @update:model-value="picker.selectField">
          <el-option v-for="f in picker.fields.value" :key="f.id" :value="f.id" :label="f.name" />
        </el-select>
        <el-select v-model="picker.trialId.value" placeholder="试验" class="w280" no-data-text="这块地还没有试验，先在平面图上新建">
          <el-option v-for="t in picker.trials.value" :key="t.id" :value="t.id" :label="`${t.seasonYear} 年 · ${t.name}`" />
        </el-select>
        <span class="hint">导入往年的数据前，先在平面图上新建那一年的试验。</span>
      </div>

      <el-upload drag :auto-upload="false" :show-file-list="false" accept=".xlsx,.xls,.csv" :on-change="onFile" class="drop">
        <EmptyState art="import" title="把表格拖到这里，或点击选择文件" desc="支持 Excel（.xlsx、.xls）和 CSV；每列一个字段、每行一个字段的表都可以" :size="130">
          <el-button class="btn-wheat" :icon="UploadFilled">选择文件</el-button>
        </EmptyState>
      </el-upload>

      <div class="template">
        <div>
          <b>没有现成的表格？</b>
          <span class="muted">下载导入模板，里面已经填好这个试验各小区的编号、品种和重复，补上数据后导入即可。</span>
        </div>
        <el-button :icon="Download" @click="downloadTemplate">下载导入模板</el-button>
      </div>
    </section>

    <!-- 第 2 步：整理表格 -->
    <div v-if="step === 1 && sheet" class="arrange">
      <section class="panel sheet-panel">
        <div class="sheet-bar">
          <el-select v-if="(preview?.sheets.length ?? 0) > 1" v-model="settings.sheet" class="w160" size="default">
            <el-option v-for="(s, i) in preview!.sheets" :key="i" :value="i" :label="`工作表：${s.name}`" />
          </el-select>
          <el-segmented
            :model-value="settings.orientation"
            :options="[{ label: '每列一个字段', value: 'COLUMNS' }, { label: '每行一个字段', value: 'ROWS' }]"
            @update:model-value="(v: string | number | boolean) => setOrientation(v as ImportOrientation)"
          />
          <span class="range-tag" :class="{ empty: !settings.range }">
            <el-icon><Aim /></el-icon>
            {{ settings.range ? `数据区域 ${rangeText(settings.range)}` : '还没选区域' }}
          </span>
          <el-button :icon="MagicStick" @click="autoDetect">自动识别</el-button>
          <el-button v-if="edits.size" :icon="RefreshLeft" text @click="undoEdits">撤销 {{ edits.size }} 处修改</el-button>
        </div>
        <div class="sheet-tip">
          {{ orientationHelp }}。<b>拖动鼠标</b>重新选数据区域，<b>双击格子</b>可以直接改内容。
          <span class="legend"><i class="sw head" />表头 <i class="sw key" />找小区用 <i class="sw trait" />录入项目 <i class="sw edit" />改过 <i class="sw err" />有问题</span>
        </div>
        <div class="sheet-wrap">
          <SheetGrid
            :rows="rows"
            :col-count="colCount"
            :range="settings.range"
            :orientation="settings.orientation"
            :tones="tones"
            :edits="edits"
            :errors="errorCells"
            :focus="focusCell"
            @update:range="setRange"
            @edit="onEdit"
          />
        </div>
      </section>

      <aside class="panel map-side">
        <div class="side-head">
          <div class="panel-title">字段对应<span class="sub">{{ fields.length }} 个字段</span></div>
          <div v-if="unmatched.length" class="hint warn">
            有 {{ unmatched.length }} 个字段还没对应，可以选一个录入项目，或者按表头新建。
          </div>
        </div>
        <div class="field-list">
          <div v-for="(f, i) in fields" :key="i" class="field" :class="{ off: targets[i]?.kind === 'ignore' }">
            <div class="field-top">
              <span class="field-name">{{ f.header || '（没有表头）' }}</span>
              <span v-if="byAlias(i)" class="chip green">记住的</span>
            </div>
            <div class="samples">{{ f.samples.join('、') || '没有数据' }}</div>
            <div class="field-ctl">
              <el-select
                :model-value="targetKey(targets[i])"
                filterable
                size="default"
                class="target"
                @update:model-value="(v: string) => setTarget(i, v)"
              >
                <el-option value="ignore" label="不导入" />
                <el-option-group label="找小区用">
                  <el-option value="plot" label="小区编号" />
                  <el-option value="variety" label="品种" />
                  <el-option value="replicate" label="重复" />
                  <el-option value="date" label="调查日期" />
                </el-option-group>
                <el-option-group v-for="g in traitGroups" :key="g.label" :label="g.label">
                  <el-option v-for="t in g.items" :key="t.trait.id" :value="`t:${t.trait.id}`" :label="traitLabel(t)" />
                </el-option-group>
              </el-select>
              <el-tooltip v-if="targets[i]?.kind === 'ignore' && f.header && f.samples.length" content="按这个表头新建一个录入项目" placement="top">
                <el-button :icon="Plus" circle @click="openCustom(i)" />
              </el-tooltip>
            </div>
          </div>
        </div>

        <div class="options">
          <div class="opt-row">
            <span class="opt-label">调查日期</span>
            <el-date-picker v-model="settings.observedOn" type="date" value-format="YYYY-MM-DD" :clearable="false" class="w160" />
          </div>
          <div class="hint">没有"调查日期"字段时，非日期类项目都记成这一天。</div>
          <el-checkbox v-model="settings.assignLayout" :disabled="!canAssignLayout">按表格设置这一年的品种和重复</el-checkbox>
          <el-checkbox v-model="settings.createVarieties" :disabled="!canAssignLayout || !settings.assignLayout">品种表里没有的品种自动新增</el-checkbox>
          <el-checkbox v-model="settings.rememberHeaders">记住表头对应关系（记到录入项目的别名里）</el-checkbox>
        </div>

        <div class="side-actions">
          <el-button :icon="Back" @click="restart">换个文件</el-button>
          <el-button type="primary" :disabled="!!mappingProblem" @click="runCheck">检查<el-icon class="el-icon--right"><Right /></el-icon></el-button>
        </div>
        <div v-if="mappingProblem" class="hint problem">{{ mappingProblem }}</div>
      </aside>
    </div>

    <!-- 第 3 步：检查 -->
    <section v-if="step === 2 && check" class="panel panel-pad">
      <div class="panel-title">检查结果<span class="sub">还没有写入，确认没问题再导入</span></div>
      <div class="summary">
        <div class="sum"><b class="num">{{ check.records }}</b><span>条记录</span></div>
        <div class="sum good"><b class="num">{{ check.created }}</b><span>个值将导入</span></div>
        <div class="sum"><b class="num">{{ check.duplicates }}</b><span>个值已经导入过</span></div>
        <div class="sum" :class="{ bad: check.errorCount > 0 }"><b class="num">{{ check.errorCount }}</b><span>个格子有问题</span></div>
        <div class="sum"><b class="num">{{ check.layoutChanges }}</b><span>处布局变化</span></div>
      </div>
      <el-alert v-if="check.newVarieties.length" type="warning" :closable="false" show-icon class="gap"
        :title="`将新增品种：${check.newVarieties.join('、')}`" />
      <template v-if="check.errors.length">
        <div class="hint gap">有问题的格子不导入，其它照常导入。点"去改"回到表格直接改。</div>
        <el-table :data="check.errors" size="small" max-height="380" class="gap">
          <el-table-column label="位置" width="90"><template #default="{ row }"><code class="inline">{{ row.col }}{{ row.row }}</code></template></el-table-column>
          <el-table-column prop="message" label="问题" />
          <el-table-column width="90" align="right">
            <template #default="{ row }"><el-button link type="primary" @click="fixCell(row)">去改</el-button></template>
          </el-table-column>
        </el-table>
        <div v-if="check.errorCount > check.errors.length" class="hint">只列出前 {{ check.errors.length }} 个。</div>
      </template>
      <div class="bottom-actions">
        <el-button :icon="Back" @click="step = 1">返回修改</el-button>
        <el-button type="primary" :disabled="check.created === 0 && check.layoutChanges === 0" @click="runImport">
          导入 {{ check.created }} 个值
        </el-button>
      </div>
    </section>

    <!-- 第 4 步：完成 -->
    <section v-if="step === 3 && result" class="panel">
      <EmptyState art="data" title="导入完成" :size="140"
        :desc="`导入了 ${result.created} 个值${result.duplicates ? `，${result.duplicates} 个已经导入过跳过了` : ''}${result.errorCount ? `，${result.errorCount} 个格子有问题没有导入` : ''}${result.aliasesRemembered ? `；记住了 ${result.aliasesRemembered} 个表头，下次自动认出` : ''}。`">
        <el-button type="primary" @click="router.push('/data')">查看数据</el-button>
        <el-button @click="router.push(`/fields/${picker.fieldId.value}`)">去平面图</el-button>
        <el-button @click="restart">再导入一份</el-button>
      </EmptyState>
    </section>

    <CustomTraitDialog v-model="customOpen" :preset="customPreset" @created="onCustomCreated" />
  </div>
</template>

<style scoped>
.import {
  display: flex;
  flex-direction: column;
  height: 100%;
  max-width: none;
}

.steps {
  display: flex;
  align-items: center;
  padding: 14px 22px;
  margin-bottom: 16px;
}

.step {
  flex: 1;
  display: flex;
  align-items: center;
  gap: 10px;
  color: var(--ink-3);
}

.step:last-child {
  flex: 0;
}

.dot {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-family: var(--font-serif);
  font-weight: 700;
  border: 1.5px solid var(--line-strong);
  background: var(--surface);
  flex-shrink: 0;
}

.step.now .dot {
  background: var(--forest);
  border-color: var(--forest);
  color: #fbf3df;
  box-shadow: 0 0 0 4px rgba(46, 107, 79, 0.15);
}

.step.done .dot {
  background: var(--green-soft);
  border-color: var(--green);
  color: var(--green);
}

.step.now .label {
  color: var(--forest);
  font-weight: 700;
}

.step.done .label {
  color: var(--green);
}

.label {
  white-space: nowrap;
}

.bar {
  flex: 1;
  height: 2px;
  margin: 0 8px;
  background: var(--line);
  border-radius: 1px;
}

.step.done .bar {
  background: var(--green);
}

.pick {
  display: flex;
  gap: 10px;
  align-items: center;
  margin-bottom: 16px;
}

.w160 { width: 160px; }
.w200 { width: 200px; }
.w280 { width: 280px; }

.drop :deep(.el-upload-dragger) {
  border: 2px dashed var(--line-strong);
  border-radius: 14px;
  background: var(--surface-2);
  padding: 8px;
}

.drop :deep(.el-upload-dragger:hover),
.drop :deep(.el-upload-dragger.is-dragover) {
  border-color: var(--wheat);
  background: #fbf3df;
}

.template {
  margin-top: 16px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 16px;
  border-radius: 12px;
  background: var(--wheat-soft);
  border: 1px solid #ead5a8;
  font-size: 13px;
}

/* 第 2 步：左边表格，右边字段对应 */
.arrange {
  flex: 1;
  min-height: 520px;
  display: flex;
  gap: 16px;
}

.sheet-panel {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.sheet-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  padding: 12px 14px;
  background: var(--surface-2);
  border-bottom: 1px solid var(--line);
}

.range-tag {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: 30px;
  padding: 0 12px;
  border-radius: 8px;
  background: var(--green-soft);
  color: var(--green);
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}

.range-tag.empty {
  background: var(--clay-soft);
  color: var(--clay);
}

.sheet-tip {
  padding: 8px 14px;
  font-size: 12.5px;
  color: var(--ink-2);
  border-bottom: 1px solid var(--line);
  display: flex;
  flex-wrap: wrap;
  gap: 4px 12px;
}

.legend {
  margin-left: auto;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: var(--ink-3);
}

.sw {
  display: inline-block;
  width: 12px;
  height: 12px;
  border-radius: 3px;
  margin-left: 6px;
  border: 1px solid rgba(0, 0, 0, 0.08);
}

.sw.head { background: #f6e7c3; }
.sw.key { background: #eaf2f5; }
.sw.trait { background: #edf4ea; }
.sw.edit { background: #d9a748; }
.sw.err { background: #fbe9e2; border-color: #c4593b; }

.sheet-wrap {
  flex: 1;
  min-height: 0;
}

.map-side {
  width: 380px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  min-height: 0;
  overflow: hidden;
}

.side-head {
  padding: 16px 18px 6px;
}

.warn {
  color: #8a6118;
}

.field-list {
  flex: 1;
  overflow: auto;
  padding: 4px 12px;
}

.field {
  padding: 10px 8px;
  border-bottom: 1px dashed var(--line);
}

.field.off .field-name {
  color: var(--ink-3);
}

.field-top {
  display: flex;
  align-items: center;
  gap: 8px;
}

.field-name {
  font-weight: 700;
  color: var(--forest);
}

.samples {
  margin: 3px 0 7px;
  font-size: 12px;
  color: var(--ink-3);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.field-ctl {
  display: flex;
  gap: 6px;
}

.target {
  flex: 1;
}

.options {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 12px 18px;
  border-top: 1px solid var(--line);
  background: var(--surface-2);
}

.opt-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.opt-label {
  font-size: 13px;
  color: var(--ink-2);
}

.options .el-checkbox {
  height: 26px;
}

.side-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  padding: 12px 18px 4px;
}

.problem {
  padding: 0 18px 12px;
  color: var(--clay);
  text-align: right;
}

/* 第 3 步 */
.summary {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 12px;
}

.sum {
  display: flex;
  flex-direction: column;
  padding: 14px 16px;
  border-radius: 12px;
  background: var(--surface-2);
  border: 1px solid var(--line);
}

.sum b {
  font-family: var(--font-serif);
  font-size: 26px;
  color: var(--forest);
}

.sum span {
  font-size: 12.5px;
  color: var(--ink-2);
}

.sum.good b {
  color: var(--green);
}

.sum.bad {
  background: var(--clay-soft);
  border-color: #ecc6b8;
}

.sum.bad b {
  color: var(--clay);
}

.gap {
  margin-top: 14px;
}

.bottom-actions {
  margin-top: 18px;
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
</style>
