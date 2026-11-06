<script setup lang="ts">
// 系谱图：按季分列，看清谁是谁的父代、谁是谁的子代。
// 点一个品系看简要信息、高亮它的祖先和后代；按住 Ctrl 再点一个，就能用这两个杂交（先点的是母本）。
// 一个杂交组合和从它选出的后代算一个家系，可以折叠。
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Close, Location, Minus, Plus, Search, Sort } from '@element-plus/icons-vue'

import { lineApi, type LineDetail } from '@/shared/api'
import { LINE_KIND, LINE_STATUS } from '@/shared/labels'
import { familyMembers, lineLabel } from '@/shared/pedigree'
import PageHeader from '@/desktop/components/PageHeader.vue'
import EmptyState from '@/desktop/components/EmptyState.vue'
import PedigreeCanvas from '@/desktop/components/breeding/PedigreeCanvas.vue'
import CrossDialog from '@/desktop/components/breeding/CrossDialog.vue'
import SelectDialog from '@/desktop/components/breeding/SelectDialog.vue'
import FounderDialog from '@/desktop/components/breeding/FounderDialog.vue'
import { useAuthStore } from '@/stores/auth'
import { useBreedingStore } from '@/stores/breeding'

const auth = useAuthStore()
const store = useBreedingStore()
const router = useRouter()
const loading = ref(false)

const seasonFilter = ref<number[]>([])
const showDropped = ref(false)
const collapsed = ref(new Set<number>())
const zoom = ref(1)
const search = ref('')
const selectedId = ref<number | null>(null)
const secondId = ref<number | null>(null)
const brief = ref<LineDetail | null>(null)
const scroller = ref<HTMLElement | null>(null)
const canvas = ref<InstanceType<typeof PedigreeCanvas> | null>(null)

async function load() {
  loading.value = true
  try {
    await Promise.all([store.loadLines(true), store.loadSeasons()])
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    loading.value = false
  }
}

onMounted(load)

const options = computed(() => ({
  seasonIds: seasonFilter.value.length ? new Set(seasonFilter.value) : null,
  showDropped: showDropped.value,
  collapsed: collapsed.value,
}))

const crosses = computed(() => store.lines.filter((l) => l.kind === 'CROSS'))

function toggle(id: number) {
  const s = new Set(collapsed.value)
  if (s.has(id)) s.delete(id)
  else s.add(id)
  collapsed.value = s
}

function collapseAll() {
  collapsed.value = new Set(crosses.value.filter((c) => familyMembers(c.id, store.lines).size > 0).map((c) => c.id))
}

function expandAll() {
  collapsed.value = new Set()
}

async function onSelect(id: number, additive: boolean) {
  if (additive && selectedId.value != null && selectedId.value !== id) {
    secondId.value = id
    return
  }
  selectedId.value = id
  secondId.value = null
}

watch(selectedId, async (id) => {
  brief.value = null
  if (id == null) return
  try {
    brief.value = await lineApi.detail(id)
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
})

const selected = computed(() => (selectedId.value ? store.lineById.get(selectedId.value) ?? null : null))
const second = computed(() => (secondId.value ? store.lineById.get(secondId.value) ?? null : null))

/** 简要信息里的几项重要数据：最近一季的重要项目 */
const importantValues = computed(() => {
  const s = brief.value?.seasonValues[brief.value.seasonValues.length - 1]
  return s ? { season: s.seasonName, traits: s.traits.filter((t) => t.important).slice(0, 8) } : null
})

const plantedText = computed(() => {
  const groups = new Map<string, { fieldId: number; plotIds: number[]; text: string }>()
  for (const p of brief.value?.plantings ?? []) {
    const key = `${p.seasonId}:${p.fieldId}`
    const g = groups.get(key) ?? {
      fieldId: p.fieldId, plotIds: [],
      text: `${p.seasonName} · ${p.fieldName}${p.generation ? `（F${p.generation}）` : ''}`,
    }
    g.plotIds.push(p.plotId)
    groups.set(key, g)
  }
  return [...groups.values()]
})

function swap() {
  ;[selectedId.value, secondId.value] = [secondId.value, selectedId.value]
}

// ---------- 找品系 ----------
const hits = computed(() => {
  const k = search.value.trim()
  if (!k) return []
  return store.lines.filter((l) => l.name.includes(k) || l.code.includes(k) || (l.formerNames ?? '').includes(k)).slice(0, 8)
})

async function locate(id: number) {
  const l = store.lineById.get(id)
  if (!l) return
  // 被折叠、被筛掉、淘汰的先放出来
  if (l.status === 'DROPPED') showDropped.value = true
  if (seasonFilter.value.length && l.homeSeasonId != null && !seasonFilter.value.includes(l.homeSeasonId)) seasonFilter.value = []
  const s = new Set(collapsed.value)
  for (const c of crosses.value) {
    if (familyMembers(c.id, store.lines).has(id)) s.delete(c.id)
  }
  collapsed.value = s
  selectedId.value = id
  secondId.value = null
  search.value = ''
  await nextTick()
  const pos = canvas.value?.positionOf(id)
  if (pos && scroller.value) {
    scroller.value.scrollTo({
      left: Math.max(0, pos.x - scroller.value.clientWidth / 2 + pos.w / 2),
      top: Math.max(0, pos.y - scroller.value.clientHeight / 2),
      behavior: 'smooth',
    })
  }
}

// ---------- 操作 ----------
const crossDialog = ref(false)
const selectDialog = ref(false)
const founderDialog = ref(false)

function goDetail(id: number) {
  router.push(`/breeding/lines/${id}`)
}

function goMap(fieldId: number, plotIds: number[]) {
  router.push({ path: `/fields/${fieldId}`, query: { highlight: plotIds.join(',') } })
}

async function afterChange() {
  await store.reload()
  if (selectedId.value) brief.value = await lineApi.detail(selectedId.value)
}
</script>

<template>
  <div class="page pedigree-page">
    <PageHeader title="系谱图" eyebrow="品种">
      <template #desc>
        按季分列，连线从父母指向子代：<span class="legend mother" />母本 <span class="legend father" />父本 <span class="legend parent" />选株。
        点一个品系看它的祖先和后代；按住 Ctrl 再点一个可以拿这两个杂交（先点的是母本）。
      </template>
      <template v-if="auth.isTeacher" #actions>
        <el-button @click="founderDialog = true">新建品种</el-button>
        <el-button type="primary" @click="crossDialog = true">杂交</el-button>
      </template>
    </PageHeader>

    <div class="panel toolbar">
      <el-popover :visible="hits.length > 0" placement="bottom-start" :width="280" :show-arrow="false">
        <template #reference>
          <el-input v-model="search" :prefix-icon="Search" placeholder="找品系：名称、编号、曾用名" clearable class="search"
                    @keyup.enter="hits[0] && locate(hits[0].id)" />
        </template>
        <div class="hits">
          <div v-for="h in hits" :key="h.id" class="hit" @click="locate(h.id)">
            <b>{{ h.name }}</b><span class="mono">{{ h.code }}</span><span class="muted">{{ LINE_KIND[h.kind] }}</span>
          </div>
        </div>
      </el-popover>
      <el-select v-model="seasonFilter" multiple collapse-tags placeholder="只看某几季" clearable style="width: 220px">
        <el-option v-for="s in store.seasons" :key="s.id" :value="s.id" :label="s.name" />
      </el-select>
      <el-checkbox v-model="showDropped">显示淘汰的</el-checkbox>
      <el-button size="small" @click="collapseAll">全部折叠</el-button>
      <el-button size="small" @click="expandAll">全部展开</el-button>
      <div class="spacer" />
      <el-button size="small" :icon="Minus" circle @click="zoom = Math.max(0.4, +(zoom - 0.1).toFixed(1))" />
      <span class="zoom">{{ Math.round(zoom * 100) }}%</span>
      <el-button size="small" :icon="Plus" circle @click="zoom = Math.min(1.6, +(zoom + 0.1).toFixed(1))" />
    </div>

    <div class="body">
      <div ref="scroller" v-loading="loading" class="panel scroller" @click.self="selectedId = null; secondId = null">
        <EmptyState v-if="!loading && store.lines.length === 0" art="blocks" title="还没有品系"
                    desc="先在季和地块里建季、建地块，新建品种种下去；以后选株、杂交出来的子代都会画在这里。" />
        <PedigreeCanvas
          v-else
          ref="canvas"
          :lines="store.lines"
          :seasons="store.seasons"
          :options="options"
          :selected-id="selectedId"
          :second-id="secondId"
          :zoom="zoom"
          @select="onSelect"
          @open="goDetail"
          @toggle="toggle"
        />
      </div>

      <aside v-if="selected" class="panel side">
        <div class="side-head">
          <div>
            <div class="kind">{{ LINE_KIND[selected.kind] }} · <el-tag size="small" :type="LINE_STATUS[selected.status].tag" effect="plain">{{ LINE_STATUS[selected.status].label }}</el-tag></div>
            <h3>{{ lineLabel(selected) }}</h3>
          </div>
          <el-button :icon="Close" link @click="selectedId = null; secondId = null" />
        </div>
        <div class="row"><span class="k">系谱式</span><b>{{ selected.pedigree }}</b></div>
        <div class="row"><span class="k">父母</span>{{ selected.parentText }}</div>
        <div v-if="brief" class="row"><span class="k">来源</span>{{ brief.originText }}</div>
        <div v-if="selected.formerNames" class="row"><span class="k">曾用名</span>{{ selected.formerNames }}</div>
        <div class="sub">每季种在哪</div>
        <div v-if="plantedText.length" class="planted">
          <div v-for="p in plantedText" :key="p.text" class="planted-row">
            <span>{{ p.text }}</span>
            <el-button link type="primary" size="small" :icon="Location" @click="goMap(p.fieldId, p.plotIds)" />
          </div>
        </div>
        <div v-else class="hint">还没种过</div>
        <template v-if="importantValues && importantValues.traits.length">
          <div class="sub">{{ importantValues.season }} 的重要数据</div>
          <div class="values">
            <div v-for="t in importantValues.traits" :key="t.traitId" class="value"><span class="k">{{ t.traitName }}</span>{{ t.display }}</div>
          </div>
        </template>

        <div v-if="second" class="pair">
          <div class="sub">杂交</div>
          <div class="pair-row"><span class="k">母本</span><b>{{ lineLabel(selected) }}</b></div>
          <div class="pair-row"><span class="k">父本</span><b>{{ lineLabel(second) }}</b></div>
          <div class="pair-actions">
            <el-button size="small" :icon="Sort" @click="swap">对调</el-button>
            <el-button v-if="auth.isTeacher" size="small" type="primary" @click="crossDialog = true">用这两个杂交</el-button>
          </div>
        </div>

        <div class="side-actions">
          <el-button type="primary" plain size="small" @click="goDetail(selected.id)">详情</el-button>
          <template v-if="auth.isTeacher">
            <el-button size="small" @click="selectDialog = true">选株</el-button>
            <el-button v-if="!second" size="small" @click="crossDialog = true">拿它杂交</el-button>
          </template>
        </div>
        <div v-if="!second && auth.isTeacher" class="hint tip">按住 Ctrl 再点一个品系，可以拿这两个杂交。</div>
      </aside>
    </div>

    <CrossDialog v-model="crossDialog" :mother-id="selectedId" :father-id="secondId" @saved="afterChange" />
    <SelectDialog v-model="selectDialog" :line-id="selectedId" @saved="afterChange" />
    <FounderDialog v-model="founderDialog" />
  </div>
</template>

<style scoped>
.pedigree-page {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}

.toolbar {
  display: flex;
  gap: 10px;
  align-items: center;
  padding: 8px 14px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}

.search {
  width: 260px;
}

.spacer {
  flex: 1;
}

.zoom {
  width: 44px;
  text-align: center;
  font-size: var(--fs-xs);
  color: var(--ink-3);
}

.body {
  flex: 1;
  min-height: 420px;
  display: flex;
  gap: 12px;
}

.scroller {
  flex: 1;
  overflow: auto;
  padding: 12px;
  min-height: 420px;
  max-height: calc(100vh - 250px);
}

.side {
  width: 300px;
  flex-shrink: 0;
  padding: 14px 16px;
  font-size: var(--fs-sm);
  align-self: flex-start;
  max-height: calc(100vh - 250px);
  overflow: auto;
}

.side-head {
  display: flex;
  justify-content: space-between;
}

.side h3 {
  margin: 4px 0 8px;
  font-family: var(--font-serif);
  color: var(--forest);
}

.kind {
  font-size: var(--fs-xs);
  color: var(--ink-3);
}

.row {
  margin-bottom: 4px;
}

.k {
  display: inline-block;
  min-width: 48px;
  margin-right: 6px;
  color: var(--ink-3);
  font-size: var(--fs-xs);
}

.sub {
  margin: 10px 0 4px;
  font-weight: 600;
  color: var(--ink-2);
}

.planted-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.values {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 2px 8px;
}

.pair {
  margin-top: 8px;
  padding: 8px 10px;
  border-radius: var(--radius-sm);
  background: var(--sky-wash);
}

.pair-actions {
  margin-top: 6px;
  display: flex;
  gap: 6px;
}

.side-actions {
  margin-top: 12px;
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}

.side-actions .el-button + .el-button {
  margin-left: 0;
}

.tip {
  margin-top: 6px;
}

.hits .hit {
  padding: 6px 8px;
  border-radius: 4px;
  cursor: pointer;
  display: flex;
  gap: 8px;
  align-items: baseline;
}

.hits .hit:hover {
  background: var(--surface-2);
}

.mono {
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  color: var(--ink-3);
}

.muted {
  margin-left: auto;
  font-size: var(--fs-xs);
  color: var(--ink-4);
}

.legend {
  display: inline-block;
  width: 22px;
  height: 0;
  margin: 0 4px 3px 8px;
  border-top: 2px solid var(--ink-3);
  vertical-align: middle;
}

.legend.father {
  border-top-style: dashed;
}

.legend.parent {
  border-top-style: dotted;
}
</style>
