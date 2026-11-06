<script setup lang="ts">
// 季和地块：一季一次种植周期（一年可以有两季），季里有几块地块，每块地块的区块划分可以和以前不一样。
// 新建季时选接着哪一季（或几季）；新建地块时照着以前的地块划分，或者从空白开始。
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Delete, Edit, Finished, Plus, Right } from '@element-plus/icons-vue'

import { seasonApi, type FieldDetail, type LandSummary, type SeasonView } from '@/shared/api'
import PageHeader from '@/desktop/components/PageHeader.vue'
import EmptyState from '@/desktop/components/EmptyState.vue'
import SeasonDialog from '@/desktop/components/breeding/SeasonDialog.vue'
import LandDialog from '@/desktop/components/breeding/LandDialog.vue'
import CleanupDialog from '@/desktop/components/breeding/CleanupDialog.vue'
import FounderDialog from '@/desktop/components/breeding/FounderDialog.vue'
import { useAuthStore } from '@/stores/auth'
import { useBreedingStore } from '@/stores/breeding'

const auth = useAuthStore()
const store = useBreedingStore()
const router = useRouter()
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    await Promise.all([store.loadSeasons(true), store.loadLines(true)])
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    loading.value = false
  }
}

onMounted(load)

/** 最新的季在上面 */
const views = computed(() => [...store.seasonViews])

function followText(v: SeasonView): string {
  if (v.followIds.length === 0) return '全新开始'
  return '接着 ' + v.followIds.map((id) => store.seasonById.get(id)?.name ?? '').filter(Boolean).join('、')
}

function dateText(v: SeasonView): string {
  return v.season.endDate ? `${v.season.startDate} 至 ${v.season.endDate}` : `${v.season.startDate} 开始`
}

// ---------- 季 ----------
const seasonDialog = ref(false)
const editingSeason = ref<SeasonView | null>(null)

function openSeason(v: SeasonView | null) {
  editingSeason.value = v
  seasonDialog.value = true
}

async function removeSeason(v: SeasonView) {
  try {
    await ElMessageBox.confirm(`删除“${v.season.name}”？季里有地块、或者有品系是在这一季杂交或选出来的，不能删。`, '删除季', {
      type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消',
    })
    await seasonApi.remove(v.season.id)
    ElMessage.success('已删除')
    await store.loadSeasons(true)
  } catch (e) {
    if (e !== 'cancel') ElMessage.error((e as Error).message)
  }
}

// ---------- 地块 ----------
const landDialog = ref(false)
const landSeason = ref<SeasonView | null>(null)

function openLand(v: SeasonView) {
  landSeason.value = v
  landDialog.value = true
}

function onLandSaved(d: FieldDetail) {
  router.push({ path: `/fields/${d.field.id}`, query: { mode: 'layout' } })
}

function openMap(f: LandSummary) {
  router.push(`/fields/${f.id}`)
}

async function removeLand(v: SeasonView, f: LandSummary) {
  try {
    await ElMessageBox.confirm(`删除地块“${f.name}”？录过数据、或者有品系是从这里选出来的，不能删。`, '删除地块', {
      type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消',
    })
    await seasonApi.removeLand(v.season.id, f.id)
    ElMessage.success('已删除')
    await store.loadSeasons(true)
  } catch (e) {
    if (e !== 'cancel') ElMessage.error((e as Error).message)
  }
}

// ---------- 淘汰整理、原始材料 ----------
const cleanupDialog = ref(false)
const cleanupSeason = ref<SeasonView | null>(null)

function openCleanup(v: SeasonView) {
  cleanupSeason.value = v
  cleanupDialog.value = true
}

const founderDialog = ref(false)

const stats = computed(() => ({
  seasons: store.seasonViews.length,
  lands: store.seasonViews.reduce((n, v) => n + v.fields.length, 0),
  lines: store.lines.filter((l) => l.status !== 'DROPPED').length,
}))
</script>

<template>
  <div class="page">
    <PageHeader title="季和地块" eyebrow="日常">
      <template #desc>
        一季一次种植周期，一年可以有两季（大田、夏繁或温室）。每季的地块可以重新划；种植时下拉框最前面是接着的季种过的品系和它们的子代。
        现在 {{ stats.seasons }} 季、{{ stats.lands }} 块地块、{{ stats.lines }} 个在用的品系。
      </template>
      <template v-if="auth.isTeacher" #actions>
        <el-button @click="founderDialog = true">新建品种</el-button>
        <el-button type="primary" :icon="Plus" @click="openSeason(null)">新建季</el-button>
      </template>
    </PageHeader>

    <div v-loading="loading" class="seasons">
      <EmptyState
        v-if="!loading && views.length === 0"
        art="field"
        title="还没有季"
        desc="先建第一季（全新开始），在季里建地块、划区块，然后新建品种种下去。"
      >
        <el-button v-if="auth.isTeacher" type="primary" :icon="Plus" @click="openSeason(null)">新建季</el-button>
      </EmptyState>

      <section v-for="v in views" :key="v.season.id" class="panel season">
        <header class="season-head">
          <div class="season-title">
            <h3>{{ v.season.name }}</h3>
            <div class="meta">
              <span>{{ dateText(v) }}</span>
              <span class="sep-dot">·</span>
              <span :class="{ fresh: v.followIds.length === 0 }">{{ followText(v) }}</span>
              <template v-if="v.season.remark"><span class="sep-dot">·</span><span>{{ v.season.remark }}</span></template>
            </div>
          </div>
          <div v-if="auth.isTeacher" class="season-actions">
            <el-button type="primary" plain size="small" :icon="Plus" @click="openLand(v)">新建地块</el-button>
            <el-tooltip v-if="v.followIds.length" content="接着的季里没再种、也没有子代的品系，定淘汰还是保留" placement="top">
              <el-button size="small" :icon="Finished" @click="openCleanup(v)">淘汰整理</el-button>
            </el-tooltip>
            <el-button size="small" :icon="Edit" @click="openSeason(v)">修改</el-button>
            <el-button size="small" :icon="Delete" @click="removeSeason(v)" />
          </div>
        </header>
        <div v-if="v.fields.length" class="lands">
          <article v-for="f in v.fields" :key="f.id" class="land" @click="openMap(f)">
            <div class="land-name">{{ f.name }}</div>
            <div class="land-meta">
              {{ f.plotCount }} 个小区块 · 已种 {{ f.plantedCount }} 个 · {{ f.lineCount }} 个品系
            </div>
            <el-progress
              :percentage="f.plotCount ? Math.round((f.plantedCount / f.plotCount) * 100) : 0"
              :show-text="false"
              :stroke-width="4"
              color="var(--green)"
            />
            <div class="land-actions">
              <el-button link type="primary" size="small" @click.stop="openMap(f)">
                打开平面图<el-icon class="el-icon--right"><Right /></el-icon>
              </el-button>
              <el-button v-if="auth.isTeacher" link type="danger" size="small" @click.stop="removeLand(v, f)">删除</el-button>
            </div>
          </article>
        </div>
        <div v-else class="no-land hint">
          还没有地块。<el-button v-if="auth.isTeacher" link type="primary" @click="openLand(v)">新建地块</el-button>
        </div>
      </section>
    </div>

    <SeasonDialog v-model="seasonDialog" :season="editingSeason" />
    <LandDialog v-model="landDialog" :season="landSeason" @saved="onLandSaved" />
    <CleanupDialog v-model="cleanupDialog" :season="cleanupSeason" />
    <FounderDialog v-model="founderDialog" />
  </div>
</template>

<style scoped>
.seasons {
  display: flex;
  flex-direction: column;
  gap: 16px;
  min-height: 200px;
}

.season {
  padding: 16px 18px;
}

.season-head {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: flex-start;
  margin-bottom: 12px;
}

.season-title h3 {
  margin: 0 0 2px;
  font-family: var(--font-serif);
  color: var(--forest);
  font-size: var(--fs-lg);
}

.meta {
  font-size: var(--fs-xs);
  color: var(--ink-3);
}

.meta .fresh {
  color: var(--wheat-ink);
}

.sep-dot {
  margin: 0 6px;
}

.season-actions {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
  justify-content: flex-end;
}

.season-actions .el-button + .el-button {
  margin-left: 0;
}

.lands {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: 12px;
}

.land {
  padding: 12px 14px 8px;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--surface-2);
  cursor: pointer;
  transition: border-color 0.15s, box-shadow 0.15s;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.land:hover {
  border-color: var(--green-2);
  box-shadow: 0 2px 10px rgb(0 0 0 / 0.06);
}

.land-name {
  font-weight: 700;
  font-size: var(--fs-md);
  color: var(--ink);
}

.land-meta {
  font-size: var(--fs-xs);
  color: var(--ink-3);
}

.land-actions {
  display: flex;
  justify-content: space-between;
}

.no-land {
  padding: 8px 0;
}
</style>
