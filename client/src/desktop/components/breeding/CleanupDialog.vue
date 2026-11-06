<script setup lang="ts">
// 淘汰整理：新的一季布置好以后，接着的季里种过、这一季没再种、也没有子代的在选品系，老师一个个定淘汰还是保留
import { reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'

import { lineApi, seasonApi, type LineRow, type SeasonView } from '@/shared/api'
import { lineLabel } from '@/shared/pedigree'
import { useBreedingStore } from '@/stores/breeding'

const props = defineProps<{ season: SeasonView | null }>()
const visible = defineModel<boolean>({ required: true })

const store = useBreedingStore()
const rows = ref<LineRow[]>([])
const loading = ref(false)
const saving = ref(false)
const choice = reactive<Record<number, 'DROPPED' | 'KEPT' | 'LATER'>>({})

watch(visible, async (open) => {
  if (!open || !props.season) return
  loading.value = true
  try {
    rows.value = await seasonApi.cleanup(props.season.season.id)
    rows.value.forEach((r) => { choice[r.id] = 'DROPPED' })
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    loading.value = false
  }
})

function setAll(v: 'DROPPED' | 'KEPT' | 'LATER') {
  rows.value.forEach((r) => { choice[r.id] = v })
}

async function save() {
  const drop = rows.value.filter((r) => choice[r.id] === 'DROPPED').map((r) => r.id)
  const keep = rows.value.filter((r) => choice[r.id] === 'KEPT').map((r) => r.id)
  saving.value = true
  try {
    if (drop.length) await lineApi.setStatus(drop, 'DROPPED')
    if (keep.length) await lineApi.setStatus(keep, 'KEPT')
    ElMessage.success(`淘汰 ${drop.length} 个，保留 ${keep.length} 个`)
    visible.value = false
    await store.reload()
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <el-dialog v-model="visible" :title="`淘汰整理：${season?.season.name ?? ''}`" width="720px">
    <div class="hint lead">
      接着的季里种过、这一季没再种、也没有子代的在选品系。淘汰的在系谱图里默认不显示，以后可以恢复；保留的以后不再列出来。
    </div>
    <div v-if="rows.length" class="bulk">
      全部设成：
      <el-button size="small" @click="setAll('DROPPED')">淘汰</el-button>
      <el-button size="small" @click="setAll('KEPT')">保留</el-button>
      <el-button size="small" @click="setAll('LATER')">先不管</el-button>
    </div>
    <el-table v-loading="loading" :data="rows" max-height="420" empty-text="没有要整理的品系">
      <el-table-column label="品系" min-width="180">
        <template #default="{ row }"><b>{{ lineLabel(row) }}</b></template>
      </el-table-column>
      <el-table-column prop="pedigree" label="系谱式" min-width="180" show-overflow-tooltip />
      <el-table-column prop="parentText" label="父母" min-width="140" show-overflow-tooltip />
      <el-table-column label="怎么办" width="210">
        <template #default="{ row }">
          <el-radio-group v-model="choice[row.id]" size="small">
            <el-radio-button value="DROPPED">淘汰</el-radio-button>
            <el-radio-button value="KEPT">保留</el-radio-button>
            <el-radio-button value="LATER">先不管</el-radio-button>
          </el-radio-group>
        </template>
      </el-table-column>
    </el-table>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="saving" :disabled="rows.length === 0" @click="save">确定</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.lead {
  margin-bottom: 10px;
}

.bulk {
  margin-bottom: 8px;
  font-size: var(--fs-sm);
  color: var(--ink-2);
}
</style>
