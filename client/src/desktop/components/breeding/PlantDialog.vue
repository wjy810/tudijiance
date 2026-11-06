<script setup lang="ts">
// 种植：选一个品系种到选中的小区块。最前面是接着的季种过的品系、它们的子代，后面是这一季已经种了的、最近种过的；
// 也可以搜全部品系，或者新建原始材料
import { computed, ref, watch } from 'vue'
import { Plus, Search } from '@element-plus/icons-vue'

import type { Candidates, LineRow } from '@/shared/api'
import { useBreedingStore } from '@/stores/breeding'
import LineChip from './LineChip.vue'
import FounderDialog from './FounderDialog.vue'

const props = defineProps<{ candidates: Candidates | null; count: number; title?: string }>()
const visible = defineModel<boolean>({ required: true })
const emit = defineEmits<{ pick: [lineId: number] }>()

const store = useBreedingStore()
const search = ref('')
const founderOpen = ref(false)

watch(visible, (open) => {
  if (open) search.value = ''
})

const hits = computed<LineRow[]>(() => {
  const k = search.value.trim()
  if (!k) return []
  return store.lines.filter((l) => l.name.includes(k) || l.code.includes(k) || (l.formerNames ?? '').includes(k)
    || l.pedigree.includes(k)).slice(0, 60)
})

function pick(id: number) {
  visible.value = false
  emit('pick', id)
}

function onFounders(rows: LineRow[]) {
  if (rows.length === 1) pick(rows[0]!.id)
}
</script>

<template>
  <el-dialog v-model="visible" :title="title ?? `种植：${count} 个小区块`" width="620px" append-to-body>
    <el-input v-model="search" :prefix-icon="Search" placeholder="搜全部品系：名称、编号、曾用名、系谱式" clearable class="search" />
    <div v-if="search.trim()" class="group">
      <div class="group-title">搜到 {{ hits.length }} 个</div>
      <div class="chips">
        <LineChip v-for="l in hits" :key="l.id" :line="l" @click="pick(l.id)" />
      </div>
      <div v-if="hits.length === 0" class="hint">没找到。可以新建原始材料。</div>
    </div>
    <template v-else>
      <div v-if="candidates?.fresh" class="hint fresh">这一季是"全新开始"，没有上一季：直接搜品系，或者新建原始材料。</div>
      <div v-for="g in candidates?.groups ?? []" :key="g.key" class="group">
        <template v-if="g.lines.length">
          <div class="group-title">{{ g.title }}<span class="n">{{ g.lines.length }}</span></div>
          <div class="chips">
            <LineChip v-for="l in g.lines" :key="l.id" :line="l" @click="pick(l.id)" />
          </div>
        </template>
      </div>
    </template>
    <template #footer>
      <el-button :icon="Plus" @click="founderOpen = true">新建原始材料</el-button>
      <el-button @click="visible = false">取消</el-button>
    </template>
    <FounderDialog v-model="founderOpen" @saved="onFounders" />
  </el-dialog>
</template>

<style scoped>
.search {
  margin-bottom: 12px;
}

.group {
  margin-bottom: 12px;
}

.group-title {
  margin-bottom: 6px;
  font-weight: 600;
  font-size: var(--fs-sm);
  color: var(--ink-2);
}

.n {
  margin-left: 6px;
  color: var(--ink-4);
  font-weight: 400;
}

.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  max-height: 180px;
  overflow: auto;
}

.fresh {
  margin-bottom: 10px;
}
</style>
