<script setup lang="ts">
// 品系库：所有品系（原始材料、杂交组合、选株后代），按名称、曾用名、编号、系谱式找；点开看详情
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'

import { lineApi, type LineKind, type LineRow, type LineStatus } from '@/shared/api'
import { LINE_KIND, LINE_STATUS } from '@/shared/labels'
import { byCode, latestGeneration, naturalCompare } from '@/shared/pedigree'
import PageHeader from '@/desktop/components/PageHeader.vue'
import FounderDialog from '@/desktop/components/breeding/FounderDialog.vue'
import CrossDialog from '@/desktop/components/breeding/CrossDialog.vue'
import { useAuthStore } from '@/stores/auth'
import { useBreedingStore } from '@/stores/breeding'

const auth = useAuthStore()
const store = useBreedingStore()
const router = useRouter()
const loading = ref(false)
const keyword = ref('')
const status = ref<LineStatus | ''>('')
const kind = ref<LineKind | ''>('')
const seasonId = ref<number | null>(null)
const selected = ref<LineRow[]>([])

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

const rows = computed(() => {
  const k = keyword.value.trim()
  return store.lines.filter((l) =>
    (!status.value || l.status === status.value)
    && (!kind.value || l.kind === kind.value)
    && (!seasonId.value || l.planted.some((p) => p.seasonId === seasonId.value) || l.originSeasonId === seasonId.value)
    && (!k || l.name.includes(k) || l.code.includes(k) || (l.formerNames ?? '').includes(k) || l.pedigree.includes(k)))
})

const counts = computed(() => {
  const c = { all: store.lines.length, active: 0, dropped: 0 }
  store.lines.forEach((l) => {
    if (l.status === 'DROPPED') c.dropped++
    else c.active++
  })
  return c
})

function lastSeason(l: LineRow): string {
  const p = l.planted[l.planted.length - 1]
  return p ? store.seasonById.get(p.seasonId)?.name ?? '' : ''
}

function open(l: LineRow) {
  router.push(`/breeding/lines/${l.id}`)
}

async function setStatus(s: LineStatus) {
  const ids = selected.value.map((l) => l.id)
  if (ids.length === 0) return
  try {
    await lineApi.setStatus(ids, s)
    ElMessage.success(`${ids.length} 个品系改成“${LINE_STATUS[s].label}”`)
    await store.reload()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

const founderDialog = ref(false)
const crossDialog = ref(false)

const sortCode = (a: LineRow, b: LineRow) => byCode(a, b)
const sortName = (a: LineRow, b: LineRow) => naturalCompare(a.name, b.name)
</script>

<template>
  <div class="page">
    <PageHeader title="品系库" eyebrow="育种">
      <template #desc>
        所有品系：原始材料、杂交组合、选株后代。共 {{ counts.all }} 个，在用 {{ counts.active }} 个，淘汰 {{ counts.dropped }} 个。
        编号建好后不变，改名后旧名留作曾用名。
      </template>
      <template v-if="auth.isTeacher" #actions>
        <el-button @click="crossDialog = true">杂交</el-button>
        <el-button type="primary" :icon="Plus" @click="founderDialog = true">新建原始材料</el-button>
      </template>
    </PageHeader>

    <div class="panel filters">
      <el-input v-model="keyword" placeholder="搜名称、曾用名、编号、系谱式" clearable class="search" />
      <el-select v-model="kind" placeholder="类型" clearable style="width: 120px">
        <el-option v-for="(label, key) in LINE_KIND" :key="key" :value="key" :label="label" />
      </el-select>
      <el-select v-model="status" placeholder="状态" clearable style="width: 110px">
        <el-option v-for="(s, key) in LINE_STATUS" :key="key" :value="key" :label="s.label" />
      </el-select>
      <el-select v-model="seasonId" placeholder="哪一季种的或选出的" clearable style="width: 210px">
        <el-option v-for="s in [...store.seasons].reverse()" :key="s.id" :value="s.id" :label="s.name" />
      </el-select>
      <div class="spacer" />
      <template v-if="auth.isTeacher && selected.length">
        <span class="hint">选了 {{ selected.length }} 个，改成</span>
        <el-button v-for="(s, key) in LINE_STATUS" :key="key" size="small" @click="setStatus(key)">{{ s.label }}</el-button>
      </template>
    </div>

    <div class="panel table-panel">
      <el-table
        v-loading="loading"
        :data="rows"
        empty-text="没有品系"
        row-key="id"
        class="clickable"
        @row-click="open"
        @selection-change="(v: LineRow[]) => (selected = v)"
      >
        <el-table-column v-if="auth.isTeacher" type="selection" width="40" />
        <el-table-column label="名称" min-width="150" sortable :sort-method="sortName">
          <template #default="{ row }">
            <b>{{ row.name }}</b>
            <div v-if="row.formerNames" class="former">曾用名：{{ row.formerNames }}</div>
          </template>
        </el-table-column>
        <el-table-column label="编号" width="130" sortable :sort-method="sortCode">
          <template #default="{ row }"><span class="mono">{{ row.code }}</span></template>
        </el-table-column>
        <el-table-column label="类型" width="96">
          <template #default="{ row }">{{ LINE_KIND[row.kind as LineKind] }}</template>
        </el-table-column>
        <el-table-column prop="pedigree" label="系谱式" min-width="200" show-overflow-tooltip />
        <el-table-column prop="parentText" label="父母" min-width="160" show-overflow-tooltip />
        <el-table-column label="最近种的季" min-width="170">
          <template #default="{ row }">
            {{ lastSeason(row) || '还没种' }}
            <span v-if="latestGeneration(row)" class="gen">{{ latestGeneration(row) }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="childCount" label="子代" width="70" align="right" />
        <el-table-column label="状态" width="86">
          <template #default="{ row }">
            <el-tag size="small" :type="LINE_STATUS[row.status as LineStatus].tag" effect="plain">
              {{ LINE_STATUS[row.status as LineStatus].label }}
            </el-tag>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <FounderDialog v-model="founderDialog" />
    <CrossDialog v-model="crossDialog" />
  </div>
</template>

<style scoped>
.filters {
  display: flex;
  gap: 8px;
  align-items: center;
  flex-wrap: wrap;
  padding: 10px 14px;
  margin-bottom: 12px;
}

.search {
  width: 240px;
}

.spacer {
  flex: 1;
}

.table-panel {
  padding: 4px 16px 12px;
}

.clickable :deep(tr) {
  cursor: pointer;
}

.mono {
  font-family: var(--font-mono);
}

.former {
  font-size: var(--fs-xs);
  color: var(--ink-3);
}

.gen {
  margin-left: 6px;
  padding: 0 5px;
  border-radius: 3px;
  background: var(--sky-soft);
  color: var(--sky);
  font-size: var(--fs-xs);
  font-weight: 600;
}
</style>
