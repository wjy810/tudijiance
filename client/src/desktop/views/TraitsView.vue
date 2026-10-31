<script setup lang="ts">
// 录入项目：标准项目（NY/T 1301—2025）和自定义项目。老师可以停用不用的项目、新增和删除自定义项目，
// 还可以维护"导入时的表头"（别名）：导入历史表格时，表头是这些写法的列自动对应到这个项目
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { EditPen, Plus, Search, Upload } from '@element-plus/icons-vue'
import { useRouter } from 'vue-router'

import { traitApi, type TraitCategory, type TraitView } from '@/shared/api'
import { TRAIT_CATEGORY, TRAIT_DATA_TYPE } from '@/shared/labels'
import { useAuthStore } from '@/stores/auth'
import { useCatalogStore } from '@/stores/catalog'
import CustomTraitDialog from '@/desktop/components/CustomTraitDialog.vue'
import { aliasKey } from '@/shared/importGuess'

const auth = useAuthStore()
const catalog = useCatalogStore()
const loading = ref(false)
const tab = ref<TraitCategory>('OPERATION')
const customOpen = ref(false)
const router = useRouter()
const keyword = ref('')

const categories = computed(() => (Object.keys(TRAIT_CATEGORY) as TraitCategory[]))
/** 有搜索词时在全部分类里按名称和表头别名找，否则显示当前分类 */
const rows = computed(() => {
  const kw = keyword.value.trim()
  if (!kw) {
    return catalog.traits.filter((t) => t.trait.category === tab.value)
  }
  const key = aliasKey(kw)
  return catalog.traits.filter((t) => t.trait.name.includes(kw) || t.aliases.some((a) => a.includes(key)))
})
const aliasCount = computed(() => catalog.traits.reduce((n, t) => n + t.aliases.length, 0))
const counts = computed(() => {
  const map = new Map<TraitCategory, number>()
  for (const t of catalog.traits) {
    map.set(t.trait.category, (map.get(t.trait.category) ?? 0) + 1)
  }
  return map
})

async function load() {
  loading.value = true
  try {
    await catalog.loadTraits(true)
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    loading.value = false
  }
}

onMounted(load)

const MAX_TAGS = 6

function optionLabel(o: TraitView['options'][number]): string {
  return o.label === o.valueCode || o.label.startsWith(o.valueCode) ? o.label : `${o.valueCode} ${o.label}`
}

async function toggle(t: TraitView, enabled: boolean) {
  try {
    await traitApi.setEnabled(t.trait.id, enabled)
    t.trait.enabled = enabled
    ElMessage.success(enabled ? `已启用"${t.trait.name}"` : `已停用"${t.trait.name}"，录入时不再显示`)
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

async function remove(t: TraitView) {
  try {
    await ElMessageBox.confirm(`删除自定义项目"${t.trait.name}"？已经录过数据的项目不能删，可以停用。`, '删除项目', {
      type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消',
    })
  } catch {
    return
  }
  try {
    await traitApi.removeCustom(t.trait.id)
    ElMessage.success('已删除')
    await load()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

// ---------- 导入时的表头（别名） ----------

const aliasTarget = ref<TraitView | null>(null)
const aliasDraft = ref<string[]>([])
const aliasSaving = ref(false)

function editAliases(t: TraitView) {
  aliasTarget.value = t
  aliasDraft.value = [...t.aliases]
}

async function saveAliases() {
  const t = aliasTarget.value
  if (!t) return
  aliasSaving.value = true
  try {
    t.aliases = await traitApi.setAliases(t.trait.id, aliasDraft.value)
    ElMessage.success(`已保存"${t.trait.name}"的表头写法`)
    aliasTarget.value = null
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    aliasSaving.value = false
  }
}

async function onCreated() {
  await load()
  tab.value = 'CUSTOM'
}
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h2>录入项目</h2>
        <div class="page-desc">标准项目按 NY/T 1301—2025《农作物品种试验规范 小麦》；不够用时老师可以新增自定义项目。停用的项目录入时不显示。</div>
      </div>
      <div class="page-actions">
        <el-input v-model="keyword" :prefix-icon="Search" clearable placeholder="按名称或表头找项目" class="search" />
        <el-button v-if="auth.isTeacher" type="primary" :icon="Plus" @click="customOpen = true">新增自定义项目</el-button>
      </div>
    </div>

    <div class="link-card panel">
      <span class="link-icon"><el-icon><Upload /></el-icon></span>
      <div class="link-text">
        <b>和导入历史表格联动</b>
        <span>导入表格时，表头是"导入时的表头"里这些写法的列会自动对应到这个项目；导入时确认过的对应关系也会自动记到这里。
          现在一共记了 <b class="num">{{ aliasCount }}</b> 种写法。</span>
      </div>
      <el-button v-if="auth.isTeacher" @click="router.push('/import')">去导入</el-button>
    </div>

    <div class="layout">
      <nav class="cats panel">
        <button
          v-for="c in categories"
          :key="c"
          type="button"
          class="cat"
          :class="{ active: tab === c }"
          @click="tab = c"
        >
          <span>{{ TRAIT_CATEGORY[c] }}</span>
          <span class="count">{{ counts.get(c) ?? 0 }}</span>
        </button>
        <div v-if="keyword.trim()" class="search-note">正在全部分类里找"{{ keyword.trim() }}"，找到 {{ rows.length }} 个</div>
      </nav>

      <div class="panel table-panel">
        <el-table v-loading="loading" :data="rows" :empty-text="tab === 'CUSTOM' ? '还没有自定义项目' : '没有项目'">
          <el-table-column label="项目" min-width="150">
            <template #default="{ row }">
              <div :class="{ disabled: !row.trait.enabled }">{{ row.trait.name }}</div>
              <div class="muted small">
                <template v-if="row.trait.isComputed">自动计算</template>
                <template v-else-if="row.trait.stdClause">标准 {{ row.trait.stdClause }}</template>
                <template v-else-if="!row.trait.isStandard">自定义</template>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="类型" width="110">
            <template #default="{ row }">
              {{ TRAIT_DATA_TYPE[row.trait.dataType as keyof typeof TRAIT_DATA_TYPE] }}
              <span v-if="row.trait.unit && row.trait.unit !== '级'" class="muted small">（{{ row.trait.unit }}）</span>
            </template>
          </el-table-column>
          <el-table-column label="可选的值" min-width="260">
            <template #default="{ row }">
              <div class="tags">
                <span v-for="o in row.options.slice(0, MAX_TAGS)" :key="o.id" class="opt">{{ optionLabel(o) }}</span>
                <span v-if="row.options.length > MAX_TAGS" class="muted small">等 {{ row.options.length }} 个</span>
                <span v-if="row.options.length === 0" class="muted small">—</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="导入时的表头" min-width="190">
            <template #default="{ row }">
              <div class="tags">
                <span v-for="a in row.aliases.slice(0, 3)" :key="a" class="chip wheat">{{ a }}</span>
                <span v-if="row.aliases.length > 3" class="muted small">等 {{ row.aliases.length }} 种</span>
                <span v-if="row.aliases.length === 0" class="muted small">—</span>
                <el-button v-if="auth.isTeacher" link :icon="EditPen" class="alias-edit" @click="editAliases(row)" />
              </div>
            </template>
          </el-table-column>
          <el-table-column label="调查时间 / 说明" min-width="180" show-overflow-tooltip>
            <template #default="{ row }"><span class="muted">{{ row.trait.surveyTime }}</span></template>
          </el-table-column>
          <el-table-column label="启用" width="70" align="center">
            <template #default="{ row }">
              <el-switch
                :model-value="row.trait.enabled"
                :disabled="!auth.isTeacher"
                size="small"
                @update:model-value="(v: string | number | boolean) => toggle(row, !!v)"
              />
            </template>
          </el-table-column>
          <el-table-column v-if="auth.isTeacher && tab === 'CUSTOM'" label="" width="70">
            <template #default="{ row }">
              <el-button link type="danger" size="small" @click="remove(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </div>

    <CustomTraitDialog v-model="customOpen" @created="onCreated" />

    <el-dialog :model-value="!!aliasTarget" :title="`导入时的表头：${aliasTarget?.trait.name ?? ''}`" width="480px"
      @update:model-value="(v: boolean) => { if (!v) aliasTarget = null }">
      <div class="hint">历史表格里这个项目的表头可能有不同写法，比如"株高(cm)""高度"。输入一种写法后按回车。
        全角半角、空格、大小写不影响识别。</div>
      <el-select v-model="aliasDraft" multiple filterable allow-create default-first-option :reserve-keyword="false"
        placeholder="输入表头写法，按回车添加" class="alias-select" no-data-text="输入后按回车">
        <el-option v-for="a in aliasDraft" :key="a" :value="a" :label="a" />
      </el-select>
      <template #footer>
        <el-button @click="aliasTarget = null">取消</el-button>
        <el-button type="primary" :loading="aliasSaving" @click="saveAliases">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.layout {
  display: grid;
  grid-template-columns: 180px minmax(0, 1fr);
  gap: 16px;
  align-items: start;
}

.cats {
  padding: 8px;
  display: flex;
  flex-direction: column;
  gap: 2px;
  position: sticky;
  top: 0;
}

.cat {
  display: flex;
  justify-content: space-between;
  align-items: center;
  border: none;
  background: none;
  padding: 9px 12px;
  border-radius: 8px;
  font-size: 14px;
  color: var(--app-text);
  cursor: pointer;
  font-family: inherit;
  text-align: left;
}

.cat:hover {
  background: var(--surface-2);
}

.search {
  width: 220px;
}

.search-note {
  margin-top: 8px;
  padding: 8px 10px;
  border-top: 1px dashed var(--line);
  font-size: 12px;
  color: var(--ink-2);
}

.link-card {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 14px 18px;
  margin-bottom: 16px;
  background: linear-gradient(90deg, #fbf3df, var(--surface) 60%);
}

.link-icon {
  width: 38px;
  height: 38px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 18px;
  color: var(--wheat);
  background: var(--wheat-soft);
  flex-shrink: 0;
}

.link-text {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 2px;
  font-size: 13px;
  color: var(--ink-2);
}

.link-text > b {
  color: var(--forest);
  font-family: var(--font-serif);
  font-size: 14px;
}

.alias-edit {
  margin-left: 2px;
}

.alias-select {
  width: 100%;
  margin-top: 12px;
}

.cat.active {
  background: var(--app-accent-soft);
  color: var(--app-accent);
  font-weight: 600;
}

.count {
  font-size: 12px;
  color: var(--app-text-muted);
  font-weight: normal;
}

.table-panel {
  padding: 4px 16px 12px;
}

.small {
  font-size: 12px;
}

.disabled {
  color: var(--app-text-muted);
  text-decoration: line-through;
}

.tags {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  align-items: center;
}

.opt {
  background: var(--surface-2);
  border: 1px solid var(--line);
  border-radius: 4px;
  padding: 0 6px;
  font-size: 12px;
  line-height: 20px;
}
</style>
