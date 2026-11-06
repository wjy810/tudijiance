<script setup lang="ts">
// 选一个品系：按名称、编号、曾用名、系谱式搜
import { computed, ref } from 'vue'

import type { LineRow } from '@/shared/api'
import { LINE_KIND } from '@/shared/labels'
import { useBreedingStore } from '@/stores/breeding'

const props = withDefaults(defineProps<{ placeholder?: string; exclude?: number[] }>(), {
  placeholder: '搜名称、编号、曾用名', exclude: () => [],
})
const model = defineModel<number | null>({ required: true })

const store = useBreedingStore()
const query = ref('')

const options = computed<LineRow[]>(() => {
  const k = query.value.trim()
  const list = store.lines.filter((l) => !props.exclude.includes(l.id) && (!k
    || l.name.includes(k) || l.code.includes(k) || (l.formerNames ?? '').includes(k) || l.pedigree.includes(k)))
  // 选中的放进来，免得搜不到时显示成数字
  return list.slice(0, 200)
})

function filter(q: string) {
  query.value = q
}
</script>

<template>
  <el-select
    v-model="model"
    filterable
    remote
    :remote-method="filter"
    :placeholder="placeholder"
    clearable
    @visible-change="(v: boolean) => v && filter('')"
  >
    <el-option
      v-for="l in options"
      :key="l.id"
      :value="l.id"
      :label="l.name === l.code ? l.name : `${l.name}（${l.code}）`"
    >
      <span>{{ l.name }}</span>
      <span v-if="l.name !== l.code" class="code">{{ l.code }}</span>
      <span class="meta">{{ l.status === 'DROPPED' ? '已淘汰 · ' : '' }}{{ LINE_KIND[l.kind] }}</span>
    </el-option>
    <el-option
      v-if="model != null && !options.some((o) => o.id === model) && store.lineById.get(model)"
      :value="model"
      :label="store.lineById.get(model)!.name"
    />
  </el-select>
</template>

<style scoped>
.code {
  margin-left: 6px;
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  color: var(--ink-3);
}

.meta {
  float: right;
  color: var(--ink-3);
  font-size: var(--fs-xs);
}
</style>
