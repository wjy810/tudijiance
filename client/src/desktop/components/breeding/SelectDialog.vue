<script setup lang="ts">
// 选株：从一个品系里选出几株，每株建一个选株后代，编号"父编号-第几株"。
// 在平面图上选时记下从哪个小区块（或整个区块）选的；在系谱图、品系库里选时要说是哪一季。
// 建好后可以当场录每一株的数据（选出时的单株数据）
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'

import { lineApi, type LineRow } from '@/shared/api'
import { lineLabel } from '@/shared/pedigree'
import { useBreedingStore } from '@/stores/breeding'
import { useCatalogStore } from '@/stores/catalog'
import type { SelectLocation } from './types'

const props = defineProps<{ lineId: number | null; location?: SelectLocation | null }>()
const visible = defineModel<boolean>({ required: true })
const emit = defineEmits<{ saved: [rows: LineRow[]] }>()

const store = useBreedingStore()
const catalog = useCatalogStore()
const step = ref<1 | 2>(1)
const count = ref(1)
const tags = ref('')
const seasonId = ref<number | null>(null)
const saving = ref(false)
const created = ref<LineRow[]>([])

const line = computed(() => (props.lineId ? store.lineById.get(props.lineId) ?? null : null))
const tagList = computed(() => tags.value.split(/[,，、\s]+/).map((s) => s.trim()).filter(Boolean))
watch(tagList, (t) => {
  if (t.length > count.value) count.value = t.length
})

/** 单株数据能录的项目：数值项目 */
const numberTraits = computed(() => catalog.traits.filter((t) =>
  t.trait.enabled && !t.trait.isComputed && t.trait.dataType === 'NUMBER'))
const DEFAULT_CODES = ['plant_height', 'grains_per_spike', 'thousand_grain_weight']
const traitIds = ref<number[]>([])
const values = reactive<Record<string, number | null>>({})

watch(visible, (open) => {
  if (!open) return
  step.value = 1
  count.value = 1
  tags.value = ''
  created.value = []
  const last = store.seasons[store.seasons.length - 1]
  seasonId.value = props.location?.seasonId ?? last?.id ?? null
  void catalog.loadTraits().then(() => {
    traitIds.value = numberTraits.value.filter((t) => DEFAULT_CODES.includes(t.trait.code)).map((t) => t.trait.id)
  })
})

async function create() {
  if (!props.lineId) return
  if (!props.location && !seasonId.value) {
    ElMessage.warning('请选择是哪一季选的')
    return
  }
  saving.value = true
  try {
    created.value = await lineApi.select(props.lineId, {
      count: count.value,
      tagNos: tagList.value,
      fieldId: props.location?.plotId ? null : props.location?.fieldId ?? null,
      blockId: props.location?.plotId ? null : props.location?.blockId ?? null,
      plotId: props.location?.plotId ?? null,
      seasonId: props.location ? null : seasonId.value,
    })
    ElMessage.success(`选出 ${created.value.length} 株：${created.value.map((r) => r.code).join('、')}`)
    await store.reload()
    emit('saved', created.value)
    Object.keys(values).forEach((k) => delete values[k])
    step.value = 2
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    saving.value = false
  }
}

async function saveValues() {
  saving.value = true
  try {
    let n = 0
    for (const r of created.value) {
      const list = traitIds.value
        .map((tid) => ({ traitId: tid, valueNum: values[`${r.id}:${tid}`] ?? null, valueText: null, valueDate: null }))
        .filter((v) => v.valueNum != null)
      if (list.length) {
        await lineApi.savePlantValues(r.id, list)
        n++
      }
    }
    ElMessage.success(n ? `已保存 ${n} 株的数据` : '没有填数据')
    visible.value = false
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <el-dialog v-model="visible" :title="step === 1 ? '选株' : '选出时的单株数据'" :width="step === 1 ? '520px' : '760px'">
    <template v-if="step === 1">
      <el-form label-width="96px">
        <el-form-item label="从">
          <b>{{ line ? lineLabel(line) : '' }}</b>
          <span v-if="location" class="where">{{ location.text }}</span>
        </el-form-item>
        <el-form-item v-if="!location" label="哪一季选的" required>
          <el-select v-model="seasonId" style="width: 260px">
            <el-option v-for="s in [...store.seasons].reverse()" :key="s.id" :value="s.id" :label="s.name" />
          </el-select>
        </el-form-item>
        <el-form-item label="挂牌号">
          <el-input v-model="tags" placeholder="可不填；多株用逗号或空格隔开，如 7, 12" />
          <div class="hint full">挂牌号另记一栏，不放进编号。</div>
        </el-form-item>
        <el-form-item label="选几株">
          <el-input-number v-model="count" :min="1" :max="200" />
        </el-form-item>
        <div v-if="line" class="preview">
          编号：{{ line.code }}-…（接着以前选过的往下数），系谱式和父本一样：{{ line.pedigree }}
        </div>
      </el-form>
    </template>
    <template v-else>
      <div class="hint lead">
        当场量的这一株的数据，记在子代名下，和以后种下去的大田数据分开显示，不进大田的汇总和统计。也可以以后在品系详情里录。
      </div>
      <el-form-item label="录哪些项目">
        <el-select v-model="traitIds" multiple filterable style="width: 100%">
          <el-option v-for="t in numberTraits" :key="t.trait.id" :value="t.trait.id"
                     :label="t.trait.unit ? `${t.trait.name}（${t.trait.unit}）` : t.trait.name" />
        </el-select>
      </el-form-item>
      <el-table :data="created" max-height="380">
        <el-table-column label="编号" width="140">
          <template #default="{ row }"><b>{{ row.code }}</b><span v-if="row.tagNo" class="where">挂牌 {{ row.tagNo }}</span></template>
        </el-table-column>
        <el-table-column v-for="tid in traitIds" :key="tid" :label="catalog.traitById.get(tid)?.trait.name" min-width="110">
          <template #default="{ row }">
            <el-input-number
              v-model="values[`${row.id}:${tid}`]"
              :controls="false"
              :precision="catalog.traitById.get(tid)?.trait.precisionDigits ?? undefined"
              size="small"
              style="width: 100%"
            />
          </template>
        </el-table-column>
      </el-table>
    </template>
    <template #footer>
      <template v-if="step === 1">
        <el-button @click="visible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="create">选出 {{ count }} 株</el-button>
      </template>
      <template v-else>
        <el-button @click="visible = false">以后再录</el-button>
        <el-button type="primary" :loading="saving" @click="saveValues">保存单株数据</el-button>
      </template>
    </template>
  </el-dialog>
</template>

<style scoped>
.where {
  margin-left: 8px;
  color: var(--ink-3);
  font-size: var(--fs-xs);
}

.full {
  width: 100%;
}

.preview {
  margin-left: 96px;
  padding: 8px 12px;
  border-radius: var(--radius-sm);
  background: var(--wheat-wash);
  font-size: var(--fs-sm);
}

.lead {
  margin-bottom: 10px;
}
</style>
