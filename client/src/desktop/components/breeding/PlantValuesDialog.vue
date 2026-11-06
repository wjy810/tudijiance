<script setup lang="ts">
// 选出时的单株数据：选株时当场量的这一株，记在子代名下，不进大田的汇总和统计
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Delete } from '@element-plus/icons-vue'

import { lineApi, type PlantValueView, type TraitValue } from '@/shared/api'
import TraitValueInput from '@/desktop/components/TraitValueInput.vue'
import { useCatalogStore } from '@/stores/catalog'

const props = defineProps<{ lineId: number | null; values: PlantValueView[] }>()
const visible = defineModel<boolean>({ required: true })
const emit = defineEmits<{ saved: [values: PlantValueView[]] }>()

const catalog = useCatalogStore()
const rows = reactive<{ traitId: number; value: TraitValue }[]>([])
const adding = ref<number | null>(null)
const saving = ref(false)

const choices = computed(() => catalog.traits.filter((t) =>
  t.trait.enabled && !t.trait.isComputed && t.trait.dataType !== 'OPERATION' && !rows.some((r) => r.traitId === t.trait.id)))

watch(visible, async (open) => {
  if (!open) return
  await catalog.loadTraits()
  rows.splice(0, rows.length, ...props.values.map((v) => ({
    traitId: v.traitId, value: { valueNum: v.valueNum, valueText: v.valueText, valueDate: v.valueDate },
  })))
  if (rows.length === 0) {
    for (const code of ['plant_height', 'grains_per_spike', 'thousand_grain_weight']) {
      const t = catalog.traits.find((x) => x.trait.code === code)
      if (t) rows.push({ traitId: t.trait.id, value: { valueNum: null, valueText: null, valueDate: null } })
    }
  }
})

function add(id: number | null) {
  if (id == null) return
  rows.push({ traitId: id, value: { valueNum: null, valueText: null, valueDate: null } })
  adding.value = null
}

async function save() {
  if (!props.lineId) return
  saving.value = true
  try {
    const saved = await lineApi.savePlantValues(props.lineId, rows.map((r) => ({ traitId: r.traitId, ...r.value })))
    ElMessage.success('已保存')
    visible.value = false
    emit('saved', saved)
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <el-dialog v-model="visible" title="选出时的单株数据" width="640px">
    <div class="hint lead">选株时当场量的这一株的数据。空着的项目不记。</div>
    <div v-for="(r, i) in rows" :key="r.traitId" class="row">
      <div class="label">{{ catalog.traitById.get(r.traitId)?.trait.name }}</div>
      <TraitValueInput v-if="catalog.traitById.get(r.traitId)" v-model="r.value" :trait="catalog.traitById.get(r.traitId)!" class="input" />
      <el-button :icon="Delete" link @click="rows.splice(i, 1)" />
    </div>
    <el-select v-model="adding" filterable placeholder="加一个项目" style="width: 260px; margin-top: 8px" @change="add">
      <el-option v-for="t in choices" :key="t.trait.id" :value="t.trait.id" :label="t.trait.name" />
    </el-select>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="save">保存</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.lead {
  margin-bottom: 10px;
}

.row {
  display: grid;
  grid-template-columns: 110px minmax(0, 1fr) 32px;
  gap: 10px;
  align-items: center;
  padding: 6px 0;
  border-bottom: 1px solid var(--line-soft);
}

.label {
  font-weight: 600;
  font-size: var(--fs-sm);
}
</style>
