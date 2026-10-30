<script setup lang="ts">
// 一条数据的修改记录：谁在什么时候录入、修改、删除
import { ref, watch } from 'vue'
import { ElMessage } from 'element-plus'

import { observationApi, type HistoryView } from '@/shared/api'

const props = defineProps<{ observationId: number | null; title?: string }>()
const visible = defineModel<boolean>({ required: true })

const rows = ref<HistoryView[]>([])
const loading = ref(false)

const ACTION: Record<HistoryView['action'], { label: string; type: 'success' | 'warning' | 'danger' }> = {
  CREATE: { label: '录入', type: 'success' },
  UPDATE: { label: '修改', type: 'warning' },
  DELETE: { label: '删除', type: 'danger' },
}

watch(visible, async (open) => {
  if (!open || !props.observationId) {
    return
  }
  loading.value = true
  rows.value = []
  try {
    rows.value = await observationApi.history(props.observationId)
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <el-dialog v-model="visible" :title="title || '修改记录'" width="560px" append-to-body>
    <el-timeline v-loading="loading" class="timeline">
      <el-timeline-item
        v-for="(r, i) in rows"
        :key="i"
        :timestamp="r.at.replace('T', ' ').slice(0, 19)"
        :type="ACTION[r.action].type"
      >
        <el-tag size="small" :type="ACTION[r.action].type">{{ ACTION[r.action].label }}</el-tag>
        <span class="user">{{ r.user }}</span>
        <div v-if="r.action === 'UPDATE'" class="change">
          <span class="old">{{ r.oldValue }}</span> → <span>{{ r.newValue }}</span>
        </div>
        <div v-else class="change">{{ r.action === 'DELETE' ? r.oldValue : r.newValue }}</div>
      </el-timeline-item>
    </el-timeline>
    <el-empty v-if="!loading && rows.length === 0" description="没有记录" :image-size="60" />
  </el-dialog>
</template>

<style scoped>
.timeline {
  padding: 4px 8px 0;
  min-height: 60px;
}

.user {
  margin-left: 8px;
}

.change {
  margin-top: 6px;
  color: #1f2a24;
  word-break: break-all;
}

.old {
  color: var(--app-text-secondary);
  text-decoration: line-through;
}
</style>
