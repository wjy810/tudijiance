<script setup lang="ts">
// 导入历史表格（老师）：选文件 → 对应列 → 检查 → 导入。
// 每一行是一个小区（一个重复）的记录；用小区编号，或者品种 + 重复，找到对应的小区
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Download, UploadFilled } from '@element-plus/icons-vue'
import type { UploadFile } from 'element-plus'

import {
  importApi, type ImportMapping, type ImportPreview, type ImportResult, type TraitCategory, type TraitView,
} from '@/shared/api'
import { TRAIT_CATEGORY } from '@/shared/labels'
import { useTrialPicker } from '@/shared/composables/useTrialPicker'
import { columnLetter, guessHeaderRow, guessTarget, importable, type ColumnTarget } from '@/shared/importGuess'
import { useCatalogStore } from '@/stores/catalog'

const router = useRouter()
const catalog = useCatalogStore()
const picker = useTrialPicker()

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
    resetMapping()
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

// ---------- 第 2 步：对应列 ----------

const settings = reactive({
  sheet: 0,
  headerRow: 1,
  observedOn: '' as string,
  assignLayout: true,
  createVarieties: true,
})

/** 每一列对应什么，键是列号 */
const targets = ref<ColumnTarget[]>([])

const sheet = computed(() => preview.value?.sheets[settings.sheet] ?? null)
const header = computed(() => sheet.value?.rows[settings.headerRow - 1] ?? [])
const colCount = computed(() => Math.max(sheet.value?.colCount ?? 0, header.value.length))
const sampleRows = computed(() => sheet.value?.rows.slice(settings.headerRow, settings.headerRow + 3) ?? [])

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

function resetMapping() {
  if (!sheet.value) {
    return
  }
  settings.headerRow = guessHeaderRow(sheet.value.rows)
  settings.observedOn = defaultDate()
  guessAll()
}

function guessAll() {
  targets.value = Array.from({ length: colCount.value }, (_, i) => guessTarget(header.value[i] ?? '', catalog.traits))
}

watch(() => settings.sheet, resetMapping)
watch(() => settings.headerRow, guessAll)

/** 下拉框的值用字符串表示，trait 用 "t:编号" */
function targetKey(t: ColumnTarget | undefined): string {
  if (!t || t.kind === 'ignore') {
    return 'ignore'
  }
  return t.kind === 'trait' ? `t:${t.traitId}` : t.kind
}

function setTarget(col: number, key: string) {
  const next: ColumnTarget = key.startsWith('t:')
    ? { kind: 'trait', traitId: Number(key.slice(2)) }
    : { kind: key as 'ignore' | 'plot' | 'variety' | 'replicate' | 'date' }
  // 小区编号、品种、重复、调查日期各只能有一列
  if (next.kind !== 'trait' && next.kind !== 'ignore') {
    targets.value = targets.value.map((t) => (t.kind === next.kind ? { kind: 'ignore' } : t))
  }
  targets.value[col] = next
  targets.value = [...targets.value]
}

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
    return '折合亩产（按小区面积换算成小区产量）'
  }
  return t.trait.unit && t.trait.unit !== '级' ? `${t.trait.name}（${t.trait.unit}）` : t.trait.name
}

const colOf = (kind: ColumnTarget['kind']) => {
  const i = targets.value.findIndex((t) => t.kind === kind)
  return i < 0 ? null : i
}

const mappedTraits = computed(() =>
  targets.value.flatMap((t, col) => (t.kind === 'trait' ? [{ col, traitId: t.traitId }] : [])))
const hasDateColumn = computed(() => mappedTraits.value.some((m) => catalog.traitById.get(m.traitId)?.trait.dataType !== 'DATE'))
const canAssignLayout = computed(() => colOf('plot') !== null && (colOf('variety') !== null || colOf('replicate') !== null))

const mappingProblem = computed(() => {
  if (colOf('plot') === null && colOf('variety') === null) {
    return '请指定"小区编号"列，或者"品种"列（再加上"重复"列），用来找到每一行对应的小区'
  }
  if (mappedTraits.value.length === 0) {
    return '请至少选一列要导入的项目'
  }
  if (hasDateColumn.value && colOf('date') === null && !settings.observedOn) {
    return '请填写调查日期'
  }
  return null
})

function mapping(dryRun: boolean): ImportMapping {
  return {
    trialId: picker.trialId.value!,
    sheet: settings.sheet,
    headerRow: settings.headerRow,
    plotCodeCol: colOf('plot'),
    varietyCol: colOf('variety'),
    replicateCol: colOf('replicate'),
    observedOnCol: colOf('date'),
    observedOn: settings.observedOn || null,
    assignLayout: canAssignLayout.value && settings.assignLayout,
    createVarieties: canAssignLayout.value && settings.assignLayout && settings.createVarieties,
    columns: mappedTraits.value,
    dryRun,
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
    step.value = 2
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    busy.value = false
  }
}

async function runImport() {
  busy.value = true
  try {
    result.value = await importApi.run(file.value!, mapping(false))
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
}
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h2>导入历史表格</h2>
        <div class="page-desc">把以前记在 Excel 里的数据导进来，和新录入的数据一起统计。每一行是一个小区（一个重复）的记录。</div>
      </div>
    </div>

    <div class="panel panel-pad steps-panel">
      <el-steps :active="step" finish-status="success" align-center>
        <el-step title="选择文件" />
        <el-step title="对应列" />
        <el-step title="检查" />
        <el-step title="完成" />
      </el-steps>
    </div>

    <!-- 第 1 步 -->
    <section v-if="step === 0" class="panel panel-pad">
      <div class="panel-title">导入到哪个试验</div>
      <div class="row">
        <el-select :model-value="picker.fieldId.value" placeholder="实验田" class="w200" @update:model-value="picker.selectField">
          <el-option v-for="f in picker.fields.value" :key="f.id" :value="f.id" :label="f.name" />
        </el-select>
        <el-select v-model="picker.trialId.value" placeholder="试验" class="w280" no-data-text="这块地还没有试验，先在平面图上新建">
          <el-option v-for="t in picker.trials.value" :key="t.id" :value="t.id" :label="`${t.seasonYear} 年 · ${t.name}`" />
        </el-select>
        <span class="hint">导入往年的数据前，先在平面图上新建那一年的试验。</span>
      </div>

      <el-upload
        drag
        :auto-upload="false"
        :show-file-list="false"
        accept=".xlsx,.xls,.csv"
        :disabled="busy"
        class="upload"
        :on-change="onFile"
      >
        <el-icon class="upload-icon"><UploadFilled /></el-icon>
        <div class="upload-text">把表格拖到这里，或<em>点击选择文件</em></div>
        <div class="hint">支持 Excel（.xlsx、.xls）和 CSV；表格最多 5000 行</div>
      </el-upload>
      <div v-loading="busy" />

      <div class="template">
        <div>
          <b>没有现成的表格？</b>
          <span class="muted">下载导入模板，里面已经填好这个试验各小区的编号、品种和重复，补上数据后导入即可。</span>
        </div>
        <el-button :icon="Download" @click="downloadTemplate">下载导入模板</el-button>
      </div>
    </section>

    <!-- 第 2 步 -->
    <template v-if="step === 1 && sheet">
      <section class="panel panel-pad">
        <div class="panel-title">表格<span class="sub">{{ file?.name }}</span></div>
        <div class="row">
          <template v-if="(preview?.sheets.length ?? 0) > 1">
            <span class="muted">工作表</span>
            <el-select v-model="settings.sheet" class="w200">
              <el-option v-for="(s, i) in preview?.sheets" :key="i" :value="i" :label="`${s.name}（${s.rowCount} 行）`" />
            </el-select>
          </template>
          <span class="muted">表头在第</span>
          <el-input-number v-model="settings.headerRow" :min="1" :max="Math.max(1, sheet.rows.length)" size="small" />
          <span class="muted">行，下面共 {{ Math.max(0, sheet.rowCount - settings.headerRow) }} 行数据</span>
        </div>
        <div class="sheet-preview">
          <table>
            <tbody>
              <tr v-for="(r, i) in sheet.rows.slice(0, 10)" :key="i" :class="{ head: i === settings.headerRow - 1, before: i < settings.headerRow - 1 }">
                <th>{{ i + 1 }}</th>
                <td v-for="c in colCount" :key="c">{{ r[c - 1] ?? '' }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <section class="panel panel-pad">
        <div class="panel-title">每一列对应什么<span class="sub">已经按表头自动选好，不对的改一下；不需要的列选"不导入"</span></div>
        <el-table :data="Array.from({ length: colCount }, (_, i) => i)" size="small" class="map-table">
          <el-table-column label="列" width="56" align="center">
            <template #default="{ row: col }">{{ columnLetter(col) }}</template>
          </el-table-column>
          <el-table-column label="表头" min-width="140">
            <template #default="{ row: col }"><b>{{ header[col] || '（空）' }}</b></template>
          </el-table-column>
          <el-table-column label="前几行的内容" min-width="200">
            <template #default="{ row: col }">
              <span class="muted">{{ sampleRows.map((r) => r[col] ?? '').filter(Boolean).join('、') || '—' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="对应到" min-width="280">
            <template #default="{ row: col }">
              <el-select
                :model-value="targetKey(targets[col])"
                filterable
                class="target"
                :class="{ mapped: targetKey(targets[col]) !== 'ignore' }"
                @update:model-value="(v: string) => setTarget(col, v)"
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
            </template>
          </el-table-column>
        </el-table>

        <div class="options">
          <div v-if="hasDateColumn && colOf('date') === null" class="option">
            <span>调查日期</span>
            <el-date-picker v-model="settings.observedOn" type="date" value-format="YYYY-MM-DD" :clearable="false" />
            <span class="hint">表格里没有日期列，非日期项目都记成这一天；播种期、成熟期等日期项目用格子里的日期。</span>
          </div>
          <div v-if="canAssignLayout" class="option">
            <el-checkbox v-model="settings.assignLayout">按表格里的品种、重复设置这一年各小区的品种和重复</el-checkbox>
            <el-checkbox v-if="settings.assignLayout" v-model="settings.createVarieties">品种表里没有的品种自动新增</el-checkbox>
          </div>
          <div v-else-if="colOf('plot') === null && colOf('variety') !== null" class="hint option">
            没有小区编号列时，按"品种 + 重复"在这一年的平面图上找小区，所以要先在平面图上布置好品种和重复。
          </div>
        </div>

        <div class="actions">
          <el-alert v-if="mappingProblem" :title="mappingProblem" type="warning" :closable="false" show-icon class="problem" />
          <el-button @click="restart">重新选文件</el-button>
          <el-button type="primary" :loading="busy" :disabled="!!mappingProblem" @click="runCheck">检查</el-button>
        </div>
      </section>
    </template>

    <!-- 第 3 步 -->
    <section v-if="step === 2 && check" class="panel panel-pad">
      <div class="summary">
        <div class="stat"><div class="stat-value">{{ check.rows }}</div><div class="stat-label">行数据</div></div>
        <div class="stat ok"><div class="stat-value">{{ check.created }}</div><div class="stat-label">个值将导入</div></div>
        <div class="stat"><div class="stat-value">{{ check.duplicates }}</div><div class="stat-label">个值以前导入过，跳过</div></div>
        <div class="stat" :class="{ bad: check.errorCount > 0 }"><div class="stat-value">{{ check.errorCount }}</div><div class="stat-label">个格子有问题，不导入</div></div>
      </div>
      <div v-if="check.newVarieties.length" class="note">
        将新增 {{ check.newVarieties.length }} 个品种：{{ check.newVarieties.join('、') }}
      </div>
      <div v-if="check.layoutChanges" class="note">将按表格设置 {{ check.layoutChanges }} 处小区的品种或重复</div>

      <template v-if="check.errors.length">
        <div class="panel-title errors-title">有问题的格子<span class="sub">改好表格后可以重新导入；也可以先导入没问题的部分</span></div>
        <el-table :data="check.errors" size="small" max-height="360">
          <el-table-column label="行" prop="row" width="70" align="center" />
          <el-table-column label="列" prop="col" width="70" align="center" />
          <el-table-column label="问题" prop="message" />
        </el-table>
        <div v-if="check.errorCount > check.errors.length" class="hint">只列出前 {{ check.errors.length }} 个</div>
      </template>

      <div class="actions">
        <el-button @click="step = 1">返回修改</el-button>
        <el-button type="primary" :loading="busy" :disabled="check.created === 0" @click="runImport">
          导入 {{ check.created }} 个值
        </el-button>
      </div>
    </section>

    <!-- 第 4 步 -->
    <section v-if="step === 3 && result" class="panel panel-pad">
      <el-result icon="success" :title="`导入了 ${result.created} 个值`">
        <template #sub-title>
          <div class="muted">
            {{ picker.trial.value?.name }}
            <template v-if="result.duplicates"> · 跳过 {{ result.duplicates }} 个以前导入过的值</template>
            <template v-if="result.errorCount"> · {{ result.errorCount }} 个有问题的格子没有导入</template>
            <template v-if="result.newVarieties.length"> · 新增品种 {{ result.newVarieties.join('、') }}</template>
          </div>
        </template>
        <template #extra>
          <el-button type="primary" @click="router.push(`/fields/${picker.fieldId.value}?trial=${picker.trialId.value}`)">到平面图看看</el-button>
          <el-button @click="router.push('/data')">数据查看</el-button>
          <el-button @click="restart">再导入一个</el-button>
        </template>
      </el-result>
    </section>
  </div>
</template>

<style scoped>
.steps-panel {
  margin-bottom: 16px;
}

.panel + .panel {
  margin-top: 16px;
}

.row {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.w200 {
  width: 200px;
}

.w280 {
  width: 280px;
}

.upload {
  margin-top: 18px;
}

.upload-icon {
  font-size: 46px;
  color: var(--el-color-primary-light-3);
}

.upload-text {
  margin: 6px 0 4px;
}

.template {
  margin-top: 18px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
  background: var(--app-wheat-soft);
  border-radius: 8px;
  padding: 12px 16px;
}

.sheet-preview {
  margin-top: 12px;
  overflow: auto;
  max-height: 300px;
  border: 1px solid var(--app-border);
  border-radius: 6px;
}

.sheet-preview table {
  border-collapse: collapse;
  font-size: 12px;
  white-space: nowrap;
}

.sheet-preview th,
.sheet-preview td {
  border: 1px solid #eceff0;
  padding: 4px 8px;
}

.sheet-preview th {
  background: #f6f8f5;
  color: var(--app-text-muted);
  font-weight: normal;
  position: sticky;
  left: 0;
}

.sheet-preview tr.head td {
  background: var(--app-accent-soft);
  font-weight: 600;
}

.sheet-preview tr.before td {
  color: var(--app-text-muted);
}

.map-table {
  width: 100%;
}

.target {
  width: 100%;
}

.target.mapped :deep(.el-select__wrapper) {
  box-shadow: 0 0 0 1px var(--el-color-primary-light-5) inset;
  background: var(--app-accent-soft);
}

.options {
  margin-top: 14px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.option {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.actions {
  margin-top: 18px;
  display: flex;
  justify-content: flex-end;
  align-items: center;
  gap: 8px;
}

.problem {
  flex: 1;
}

.summary {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
}

.stat {
  border: 1px solid var(--app-border);
  border-radius: 8px;
  padding: 12px 16px;
}

.stat-value {
  font-size: 26px;
  font-weight: 600;
}

.stat-label {
  font-size: 13px;
  color: var(--app-text-secondary);
}

.stat.ok .stat-value {
  color: var(--app-accent);
}

.stat.bad .stat-value {
  color: var(--app-danger);
}

.note {
  margin-top: 12px;
  background: var(--app-wheat-soft);
  border-radius: 6px;
  padding: 8px 12px;
  font-size: 13px;
}

.errors-title {
  margin-top: 18px;
}
</style>
