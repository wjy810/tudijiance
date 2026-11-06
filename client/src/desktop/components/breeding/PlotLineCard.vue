<script setup lang="ts">
// 选中一个小区块时：它种的品系（系谱式、来源、世代），一键打开父母当季的地块并高亮父母种的位置
import { ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { Location } from '@element-plus/icons-vue'

import { lineApi, type LineDetail } from '@/shared/api'
import { LINE_KIND } from '@/shared/labels'

const props = defineProps<{ lineId: number; generation: number | null }>()
const router = useRouter()
const detail = ref<LineDetail | null>(null)

watch(() => props.lineId, async (id) => {
  detail.value = null
  try {
    const d = await lineApi.detail(id)
    if (props.lineId === id) detail.value = d
  } catch {
    // 取不到只是少了来源
  }
}, { immediate: true })

function goMap(fieldId: number, plotIds: number[]) {
  router.push({ path: `/fields/${fieldId}`, query: { highlight: plotIds.join(',') } })
}
</script>

<template>
  <div class="line-card">
    <div v-if="detail" class="rows">
      <div><span class="k">品系</span><b>{{ detail.line.name }}</b><span v-if="detail.line.name !== detail.line.code" class="code">{{ detail.line.code }}</span></div>
      <div><span class="k">类型</span>{{ LINE_KIND[detail.line.kind] }}<span v-if="generation" class="gen">F{{ generation }}</span></div>
      <div><span class="k">系谱式</span>{{ detail.line.pedigree }}</div>
      <div><span class="k">来源</span>{{ detail.originText }}</div>
      <div v-for="p in detail.parentPlaces" :key="`${p.lineId}-${p.fieldId}`" class="parent">
        <span class="k">{{ p.role }}</span>
        <span class="where">{{ p.lineName }} · {{ p.seasonName }} {{ p.place }}</span>
        <el-button link type="primary" size="small" :icon="Location" title="打开那块地块，高亮当时种的位置" @click="goMap(p.fieldId, p.plotIds)" />
      </div>
    </div>
    <div v-else class="skeleton" style="height: 70px" />
    <el-button link type="primary" size="small" @click="router.push(`/breeding/lines/${lineId}`)">品系详情</el-button>
  </div>
</template>

<style scoped>
.line-card {
  margin-top: 8px;
  padding-top: 8px;
  border-top: 1px dashed var(--line);
  font-size: var(--fs-sm);
}

.rows > div {
  margin-bottom: 3px;
}

.k {
  display: inline-block;
  min-width: 44px;
  margin-right: 6px;
  color: var(--ink-3);
  font-size: var(--fs-xs);
}

.code {
  margin-left: 6px;
  font-family: var(--font-mono);
  color: var(--ink-3);
  font-size: var(--fs-xs);
}

.gen {
  margin-left: 6px;
  padding: 0 4px;
  border-radius: 3px;
  background: var(--sky-soft);
  color: var(--sky);
  font-weight: 600;
  font-size: var(--fs-xs);
}

.parent {
  display: flex;
  align-items: center;
}

.where {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--ink-2);
}
</style>
