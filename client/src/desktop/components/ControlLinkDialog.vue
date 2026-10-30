<script setup lang="ts">
// 设对照关系：勾出哪些小区是对照，保存后平面图上用虚线连起来，虚线上写"对照品种"
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'

import { trialApi, type Layout, type Plot } from '@/shared/api'
import { useCatalogStore } from '@/stores/catalog'

const props = defineProps<{ trialId: number; plots: Plot[]; varietyOf: (plotId: number) => number | null }>()
const visible = defineModel<boolean>({ required: true })
const emit = defineEmits<{ saved: [layout: Layout] }>()

const catalog = useCatalogStore()
const controlIds = ref<number[]>([])
const label = ref('对照品种')
const saving = ref(false)

const rows = computed(() =>
  [...props.plots]
    .sort((a, b) => a.rowNo - b.rowNo || a.colNo - b.colNo)
    .map((p) => {
      const v = catalog.varietyById.get(props.varietyOf(p.id) ?? -1)
      return { plot: p, variety: v?.name ?? '未分配品种', isCk: !!v?.controlType }
    }),
)

watch(visible, (open) => {
  if (open) {
    // 默认把种了对照品种的小区勾上
    controlIds.value = rows.value.filter((r) => r.isCk).map((r) => r.plot.id)
    label.value = '对照品种'
  }
})

async function save() {
  if (props.plots.length < 2) {
    ElMessage.warning('至少要选 2 个小区')
    return
  }
  if (controlIds.value.length === 0) {
    ElMessage.warning('请勾选哪个小区是对照')
    return
  }
  if (controlIds.value.length === props.plots.length) {
    ElMessage.warning('至少要有一个小区不是对照')
    return
  }
  saving.value = true
  try {
    const layout = await trialApi.createLink(props.trialId, props.plots.map((p) => p.id), controlIds.value, label.value)
    ElMessage.success('已设对照关系')
    visible.value = false
    emit('saved', layout)
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <el-dialog v-model="visible" title="设对照关系" width="520px">
    <div class="hint top">勾选的小区是对照，其余小区和它对比。保存后平面图上用虚线把它们连起来。</div>
    <el-form label-width="80px">
      <el-form-item label="虚线文字">
        <el-input v-model="label" maxlength="50" />
      </el-form-item>
    </el-form>
    <el-checkbox-group v-model="controlIds" class="list">
      <div v-for="r in rows" :key="r.plot.id" class="row">
        <el-checkbox :value="r.plot.id">
          {{ r.plot.code || `${r.plot.rowNo}-${r.plot.colNo}` }}
          <span class="muted">· {{ r.variety }}</span>
          <el-tag v-if="r.isCk" size="small" type="danger" effect="plain">CK</el-tag>
        </el-checkbox>
      </div>
    </el-checkbox-group>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="save">保存</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.top {
  margin-bottom: 12px;
}

.list {
  display: block;
  max-height: 320px;
  overflow: auto;
}

.row {
  padding: 2px 0;
}
</style>
