<script setup lang="ts">
// 品系详情：从哪来、父母和子代（小系谱图）、父母当季种在哪、每季种在哪、选出时的单株数据、各季的数据，
// 可以和父母、兄弟品系对比，导出 Excel
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Download, Edit, Location, Plus } from '@element-plus/icons-vue'

import { lineApi, type LineDetail, type LineRow, type LineStatus, type PlantingRow } from '@/shared/api'
import { LINE_KIND, LINE_STATUS, ECOTYPE, QUALITY_TYPE, TRAIT_CATEGORY } from '@/shared/labels'
import { ancestors, childrenMap, descendants, lineLabel } from '@/shared/pedigree'
import PedigreeCanvas from '@/desktop/components/breeding/PedigreeCanvas.vue'
import LineEditDialog from '@/desktop/components/breeding/LineEditDialog.vue'
import SelectDialog from '@/desktop/components/breeding/SelectDialog.vue'
import CrossDialog from '@/desktop/components/breeding/CrossDialog.vue'
import PlantValuesDialog from '@/desktop/components/breeding/PlantValuesDialog.vue'
import EmptyState from '@/desktop/components/EmptyState.vue'
import { useAuthStore } from '@/stores/auth'
import { useBreedingStore } from '@/stores/breeding'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const store = useBreedingStore()

const id = computed(() => Number(route.params.id))
const detail = ref<LineDetail | null>(null)
const loading = ref(false)
const line = computed(() => detail.value?.line ?? null)

async function load() {
  loading.value = true
  try {
    const [d] = await Promise.all([lineApi.detail(id.value), store.loadLines(), store.loadSeasons()])
    detail.value = d
    compareIds.value = []
    compared.value = []
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    loading.value = false
  }
}

watch(id, load, { immediate: true })

async function reload() {
  await store.reload()
  await load()
}

// ---------- 小系谱图：祖先和后代 ----------
const family = computed(() => {
  if (!line.value) return new Set<number>()
  const byId = store.lineById
  const s = new Set<number>([line.value.id])
  ancestors(line.value.id, byId).forEach((x) => s.add(x))
  descendants(line.value.id, childrenMap(store.lines)).forEach((x) => s.add(x))
  return s
})

function goLine(lineId: number) {
  router.push(`/breeding/lines/${lineId}`)
}

function goMap(fieldId: number, plotIds: number[]) {
  router.push({ path: `/fields/${fieldId}`, query: { highlight: plotIds.join(',') } })
}

/** 种植记录按季、地块合在一起 */
const plantingGroups = computed(() => {
  const map = new Map<string, { seasonName: string; fieldId: number; fieldName: string; generation: number | null; rows: PlantingRow[] }>()
  for (const p of detail.value?.plantings ?? []) {
    const key = `${p.seasonId}:${p.fieldId}`
    const g = map.get(key) ?? { seasonName: p.seasonName, fieldId: p.fieldId, fieldName: p.fieldName, generation: p.generation, rows: [] }
    g.rows.push(p)
    map.set(key, g)
  }
  return [...map.values()]
})

function placesText(rows: PlantingRow[]): string {
  const byBlock = new Map<string, string[]>()
  rows.forEach((r) => {
    const list = byBlock.get(r.blockName ?? '') ?? []
    list.push(r.plotCode ?? '')
    byBlock.set(r.blockName ?? '', list)
  })
  return [...byBlock.entries()].map(([b, codes]) => `${b} ${codes.join('、')} 号`).join('；')
}

// ---------- 各季数据和对比 ----------
const compareIds = ref<number[]>([])
const compared = ref<LineDetail[]>([])
const importantOnly = ref(true)

/** 能对比的：父母和兄弟品系（同一个父母的其它子代） */
const compareChoices = computed<{ label: string; options: LineRow[] }[]>(() => {
  const d = detail.value
  if (!d) return []
  const siblings = new Map<number, LineRow>()
  d.parents.forEach((p) => {
    store.lines.filter((l) => l.id !== d.line.id && [l.motherId, l.fatherId, l.parentId].includes(p.id))
      .forEach((l) => siblings.set(l.id, l))
  })
  return [
    { label: '父母', options: d.parents },
    { label: '兄弟品系', options: [...siblings.values()] },
    { label: '子代', options: d.children },
  ].filter((g) => g.options.length > 0)
})

watch(compareIds, async (ids) => {
  try {
    compared.value = await Promise.all(ids.map((x) => compared.value.find((c) => c.line.id === x) ?? lineApi.detail(x)))
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
})

interface Column { key: string; title: string; sub: string; values: Map<number, { display: string; tip: string }> }

const columns = computed<Column[]>(() => {
  const d = detail.value
  if (!d) return []
  const cols: Column[] = []
  if (d.plantValues.length) {
    cols.push({
      key: 'plant', title: '选出时的单株', sub: '',
      values: new Map(d.plantValues.map((v) => [v.traitId, { display: v.display, tip: '选株时当场量的这一株' }])),
    })
  }
  d.seasonValues.forEach((s) => cols.push({
    key: `s${s.seasonId}`, title: s.seasonName, sub: s.generation ? `F${s.generation}` : '',
    values: new Map(s.traits.map((t) => [t.traitId, {
      display: t.display,
      tip: t.plots.map((p) => `${p.place}：${p.display}`).join('\n'),
    }])),
  }))
  // 对比的品系：取它最近一季的数据
  compared.value.forEach((c) => {
    const last = c.seasonValues[c.seasonValues.length - 1]
    cols.push({
      key: `c${c.line.id}`, title: lineLabel(c.line), sub: last ? `对比 · ${last.seasonName}` : '对比 · 还没有数据',
      values: new Map((last?.traits ?? []).map((t) => [t.traitId, {
        display: t.display, tip: t.plots.map((p) => `${p.place}：${p.display}`).join('\n'),
      }])),
    })
  })
  return cols
})

/** 行：出现过的项目，重要的在前，按类别、顺序排 */
const traitRows = computed(() => {
  const d = detail.value
  if (!d) return []
  const map = new Map<number, { traitId: number; name: string; category: string; sortOrder: number; important: boolean }>()
  const add = (t: { traitId: number; traitName: string; category: string; sortOrder: number; important: boolean }) => {
    if (!map.has(t.traitId)) {
      map.set(t.traitId, { traitId: t.traitId, name: t.traitName, category: t.category, sortOrder: t.sortOrder, important: t.important })
    }
  }
  d.seasonValues.forEach((s) => s.traits.forEach(add))
  compared.value.forEach((c) => c.seasonValues.forEach((s) => s.traits.forEach(add)))
  d.plantValues.forEach((v) => {
    if (!map.has(v.traitId)) {
      map.set(v.traitId, { traitId: v.traitId, name: v.traitName, category: '', sortOrder: 0, important: true })
    }
  })
  return [...map.values()]
    .filter((r) => !importantOnly.value || r.important)
    .sort((a, b) => Number(b.important) - Number(a.important) || a.category.localeCompare(b.category) || a.sortOrder - b.sortOrder)
})

// ---------- 操作 ----------
const editDialog = ref(false)
const selectDialog = ref(false)
const crossDialog = ref(false)
const plantValuesDialog = ref(false)

async function setStatus(s: LineStatus) {
  if (!line.value) return
  try {
    await lineApi.setStatus([line.value.id], s)
    ElMessage.success(`改成“${LINE_STATUS[s].label}”`)
    await reload()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

async function remove() {
  if (!line.value) return
  try {
    await ElMessageBox.confirm(`删除“${line.value.name}”？有子代、种过的品系不能删，只能标成淘汰。`, '删除品系', {
      type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消',
    })
    await lineApi.remove(line.value.id)
    ElMessage.success('已删除')
    await store.reload()
    router.replace('/breeding/lines')
  } catch (e) {
    if (e !== 'cancel') ElMessage.error((e as Error).message)
  }
}

async function exportExcel() {
  if (!line.value) return
  try {
    await lineApi.exportExcel(line.value.id, `${line.value.code}-品系数据.xlsx`)
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

function onCrossSaved(r: { line: LineRow }) {
  goLine(r.line.id)
}
</script>

<template>
  <div v-loading="loading" class="page">
    <template v-if="line && detail">
      <header class="head panel">
        <div class="head-main">
          <div class="eyebrow">
            {{ LINE_KIND[line.kind] }}
            <el-tag size="small" :type="LINE_STATUS[line.status].tag" effect="plain">{{ LINE_STATUS[line.status].label }}</el-tag>
          </div>
          <h2>{{ line.name }} <span v-if="line.name !== line.code" class="code">{{ line.code }}</span></h2>
          <div class="pedigree">系谱式：<b>{{ line.pedigree }}</b></div>
          <div class="origin">{{ detail.originText }}</div>
          <div v-if="line.formerNames" class="former">曾用名：{{ line.formerNames }}</div>
          <div class="attrs">
            <span v-if="line.ecotype">{{ ECOTYPE[line.ecotype] }}</span>
            <span v-if="line.qualityType">{{ QUALITY_TYPE[line.qualityType] }}</span>
            <span v-if="line.breeder">来源：{{ line.breeder }}</span>
            <span v-if="line.remark">{{ line.remark }}</span>
          </div>
        </div>
        <div class="head-actions">
          <el-button :icon="Download" @click="exportExcel">导出 Excel</el-button>
          <template v-if="auth.isTeacher">
            <el-button :icon="Edit" @click="editDialog = true">改名、属性</el-button>
            <el-button :icon="Plus" @click="selectDialog = true">选株</el-button>
            <el-button @click="crossDialog = true">拿它杂交</el-button>
            <el-dropdown trigger="click" @command="setStatus">
              <el-button>改状态</el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item v-for="(s, key) in LINE_STATUS" :key="key" :command="key" :disabled="key === line.status">
                    {{ s.label }}
                  </el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
            <el-button type="danger" plain @click="remove">删除</el-button>
          </template>
        </div>
      </header>

      <div class="grid">
        <section class="panel block">
          <h3>父母和子代</h3>
          <div class="kin">
            <div class="kin-row">
              <span class="kin-label">父母</span>
              <template v-if="detail.parents.length">
                <el-tag v-for="(p, i) in detail.parents" :key="p.id" class="chip" effect="plain" @click="goLine(p.id)">
                  {{ line.kind === 'CROSS' ? (i === 0 ? '母本 ' : '父本 ') : '选自 ' }}{{ lineLabel(p) }}
                </el-tag>
              </template>
              <span v-else class="hint">原始材料，没有父母记录</span>
            </div>
            <div class="kin-row">
              <span class="kin-label">子代</span>
              <template v-if="detail.children.length">
                <el-tag v-for="c in detail.children" :key="c.id" class="chip" type="success" effect="plain" @click="goLine(c.id)">
                  {{ lineLabel(c) }}
                </el-tag>
              </template>
              <span v-else class="hint">还没有</span>
            </div>
          </div>
          <div class="mini">
            <PedigreeCanvas
              :lines="store.lines"
              :seasons="store.seasons"
              :options="{ only: family, showDropped: true }"
              :selected-id="line.id"
              compact
              @open="goLine"
              @select="(x: number) => x !== line!.id && goLine(x)"
            />
          </div>
        </section>

        <section class="panel block">
          <h3>种在哪</h3>
          <div v-if="detail.parentPlaces.length" class="places">
            <div class="sub-title">{{ line.kind === 'CROSS' ? '杂交时父母种在哪' : '从哪里选出来的' }}</div>
            <div v-for="p in detail.parentPlaces" :key="`${p.lineId}-${p.fieldId}`" class="place">
              <span class="role">{{ p.role }}</span>
              <span class="who">{{ p.lineName }}</span>
              <span class="where">{{ p.seasonName }} · {{ p.place }}</span>
              <el-button link type="primary" size="small" :icon="Location" @click="goMap(p.fieldId, p.plotIds)">在平面图上看</el-button>
            </div>
          </div>
          <div class="sub-title">每季种在哪</div>
          <div v-if="plantingGroups.length" class="places">
            <div v-for="g in plantingGroups" :key="`${g.seasonName}-${g.fieldId}`" class="place">
              <span class="role">{{ g.generation ? `F${g.generation}` : '' }}</span>
              <span class="who">{{ g.seasonName }}</span>
              <span class="where">{{ g.fieldName }} · {{ placesText(g.rows) }}</span>
              <el-button link type="primary" size="small" :icon="Location" @click="goMap(g.fieldId, g.rows.map((r) => r.plotId))">
                在平面图上看
              </el-button>
            </div>
          </div>
          <div v-else class="hint">还没种过。在季和地块里打开一块地块，"布置试验"里把它拖到区块上，或者右键"种植"。</div>
          <template v-if="line.kind === 'SELECTION'">
            <div class="sub-title">
              选出时的单株数据
              <el-button v-if="auth.canRecord" link type="primary" size="small" @click="plantValuesDialog = true">
                {{ detail.plantValues.length ? '修改' : '录入' }}
              </el-button>
            </div>
            <div v-if="detail.plantValues.length" class="plant-values">
              <span v-for="v in detail.plantValues" :key="v.traitId" class="pv"><b>{{ v.traitName }}</b> {{ v.display }}</span>
            </div>
            <div v-else class="hint">还没录。选株时当场量的这一株的数据，不进大田的汇总和统计。</div>
          </template>
        </section>
      </div>

      <section class="panel block">
        <div class="values-head">
          <h3>各季的数据</h3>
          <div class="values-tools">
            <el-checkbox v-model="importantOnly">只看重要项目</el-checkbox>
            <el-select
              v-model="compareIds"
              multiple
              collapse-tags
              collapse-tags-tooltip
              placeholder="和父母、兄弟品系对比"
              style="width: 280px"
              :disabled="compareChoices.length === 0"
            >
              <el-option-group v-for="g in compareChoices" :key="g.label" :label="g.label">
                <el-option v-for="o in g.options" :key="o.id" :value="o.id" :label="lineLabel(o)" />
              </el-option-group>
            </el-select>
          </div>
        </div>
        <div class="hint">每季每个项目取各位置最近一次录的值，数值和日期取平均；鼠标移到值上看每个位置的值。</div>
        <el-table v-if="columns.length && traitRows.length" :data="traitRows" border size="small" class="values">
          <el-table-column label="项目" min-width="140" fixed>
            <template #default="{ row }">
              <span :class="{ imp: row.important }">{{ row.name }}</span>
              <span v-if="row.category" class="cat">{{ TRAIT_CATEGORY[row.category as keyof typeof TRAIT_CATEGORY] }}</span>
            </template>
          </el-table-column>
          <el-table-column v-for="c in columns" :key="c.key" min-width="130" :class-name="c.key.startsWith('c') ? 'cmp' : ''">
            <template #header>
              <div class="col-title">{{ c.title }}</div>
              <div v-if="c.sub" class="col-sub">{{ c.sub }}</div>
            </template>
            <template #default="{ row }">
              <el-tooltip v-if="c.values.get(row.traitId)" placement="top" :content="c.values.get(row.traitId)!.tip" :show-after="300">
                <span class="cell">{{ c.values.get(row.traitId)!.display }}</span>
              </el-tooltip>
              <span v-else class="none">—</span>
            </template>
          </el-table-column>
        </el-table>
        <EmptyState v-else art="data" :size="90" title="还没有数据" desc="种下去以后，在平面图上选中它种的小区块录入，数据就归到这个品系名下。" />
      </section>
    </template>

    <LineEditDialog v-model="editDialog" :line="line" @saved="reload" />
    <SelectDialog v-model="selectDialog" :line-id="line?.id ?? null" @saved="reload" />
    <CrossDialog v-model="crossDialog" :mother-id="line?.id ?? null" @saved="onCrossSaved" />
    <PlantValuesDialog v-model="plantValuesDialog" :line-id="line?.id ?? null" :values="detail?.plantValues ?? []" @saved="load" />
  </div>
</template>

<style scoped>
.head {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  padding: 18px 20px;
  margin-bottom: 14px;
}

.eyebrow {
  display: flex;
  gap: 8px;
  align-items: center;
  font-size: var(--fs-xs);
  color: var(--ink-3);
}

h2 {
  margin: 4px 0 6px;
  font-family: var(--font-serif);
  color: var(--forest);
  font-size: var(--fs-2xl);
}

h2 .code {
  margin-left: 6px;
  font-family: var(--font-mono);
  font-size: var(--fs-md);
  color: var(--ink-3);
  font-weight: 400;
}

.pedigree {
  font-size: var(--fs-md);
}

.origin,
.former,
.attrs {
  margin-top: 4px;
  font-size: var(--fs-sm);
  color: var(--ink-2);
}

.attrs {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
  color: var(--ink-3);
}

.head-actions {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
  justify-content: flex-end;
  align-content: flex-start;
  max-width: 460px;
}

.head-actions .el-button + .el-button {
  margin-left: 0;
}

.grid {
  display: grid;
  grid-template-columns: minmax(0, 1.2fr) minmax(0, 1fr);
  gap: 14px;
  margin-bottom: 14px;
}

@media (width < 1100px) {
  .grid {
    grid-template-columns: 1fr;
  }
}

.block {
  padding: 14px 18px;
}

h3 {
  margin: 0 0 10px;
  font-family: var(--font-serif);
  color: var(--forest);
  font-size: var(--fs-md);
}

.kin-row {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
  margin-bottom: 6px;
}

.kin-label {
  width: 36px;
  color: var(--ink-3);
  font-size: var(--fs-xs);
}

.chip {
  cursor: pointer;
}

.mini {
  margin-top: 8px;
  max-height: 340px;
  overflow: auto;
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-sm);
  padding: 6px;
}

.sub-title {
  margin: 10px 0 6px;
  font-weight: 600;
  font-size: var(--fs-sm);
  color: var(--ink-2);
}

.places {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.place {
  display: grid;
  grid-template-columns: 40px auto minmax(0, 1fr) auto;
  gap: 8px;
  align-items: center;
  font-size: var(--fs-sm);
}

.role {
  color: var(--sky);
  font-weight: 600;
  font-size: var(--fs-xs);
}

.who {
  font-weight: 600;
  white-space: nowrap;
}

.where {
  color: var(--ink-2);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.plant-values {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 16px;
  font-size: var(--fs-sm);
}

.pv b {
  color: var(--ink-3);
  font-weight: 400;
  margin-right: 4px;
}

.values-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
}

.values-tools {
  display: flex;
  gap: 12px;
  align-items: center;
}

.values {
  margin-top: 8px;
}

.values :deep(.cmp) {
  background: var(--sky-wash);
}

.col-title {
  font-weight: 600;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.col-sub {
  font-size: 11px;
  color: var(--ink-3);
  font-weight: 400;
}

.imp {
  font-weight: 600;
}

.cat {
  margin-left: 6px;
  font-size: 11px;
  color: var(--ink-4);
}

.none {
  color: var(--ink-4);
}

.cell {
  cursor: default;
}
</style>
