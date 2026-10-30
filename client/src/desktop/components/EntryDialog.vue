<script setup lang="ts">
// 给选中的小区录入数据：先选分类，再选项目，最后填值。一次可以给很多个小区录同一个值
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'

import { observationApi, type Plot, type TraitCategory, type TraitValue, type TraitView } from '@/shared/api'
import { TRAIT_CATEGORY } from '@/shared/labels'
import { emptyValue, missingValue, newUuid, normalizeValue, observedOnFor, today } from '@/shared/traitValue'
import { useAuthStore } from '@/stores/auth'
import { useCatalogStore } from '@/stores/catalog'
import TraitValueInput from './TraitValueInput.vue'
import CustomTraitDialog from './CustomTraitDialog.vue'

const props = defineProps<{
  trialId: number
  plots: Plot[]
  /** 打开时默认选中的项目 */
  initialTraitId?: number | null
}>()
const visible = defineModel<boolean>({ required: true })
const emit = defineEmits<{ saved: [] }>()

const auth = useAuthStore()
const catalog = useCatalogStore()

const category = ref<TraitCategory>('OPERATION')
const traitId = ref<number | null>(null)
const value = ref<TraitValue>(emptyValue())
const observedOn = ref(today())
const note = ref('')
const saving = ref(false)
const customOpen = ref(false)
// 同一次提交重试时用同一组编号，服务器据此去掉重复
let uuids = new Map<number, string>()

const categories = computed(() => [...catalog.entryTraits.keys()])
const traitsInCategory = computed(() => catalog.entryTraits.get(category.value) ?? [])
const trait = computed<TraitView | null>(() => catalog.traitById.get(traitId.value ?? -1) ?? null)
const plotCodes = computed(() => props.plots.map((p) => p.code || `${p.rowNo}-${p.colNo}`))

watch(visible, async (open) => {
  if (!open) {
    return
  }
  try {
    await catalog.loadTraits()
  } catch (e) {
    ElMessage.error((e as Error).message)
    return
  }
  const initial = props.initialTraitId ? catalog.traitById.get(props.initialTraitId) : null
  if (initial) {
    category.value = initial.trait.category
    traitId.value = initial.trait.id
  } else if (!categories.value.includes(category.value)) {
    category.value = categories.value[0] ?? 'OPERATION'
  }
  if (!traitsInCategory.value.some((t) => t.trait.id === traitId.value)) {
    traitId.value = traitsInCategory.value[0]?.trait.id ?? null
  }
  resetValue()
})

watch(category, () => {
  if (!traitsInCategory.value.some((t) => t.trait.id === traitId.value)) {
    traitId.value = traitsInCategory.value[0]?.trait.id ?? null
  }
})

watch(traitId, resetValue)

// 改了任何内容就当作新的一次录入
watch([value, observedOn, note, () => props.plots], () => {
  uuids = new Map()
}, { deep: true })

function resetValue() {
  value.value = emptyValue()
  note.value = ''
}

async function save(keepOpen: boolean) {
  const t = trait.value
  if (!t) {
    ElMessage.warning('请选择录入项目')
    return
  }
  const missing = missingValue(t, value.value)
  if (missing) {
    ElMessage.warning(missing)
    return
  }
  if (props.plots.length === 0) {
    ElMessage.warning('没有选中小区')
    return
  }
  const v = normalizeValue(t.trait, value.value)
  for (const p of props.plots) {
    if (!uuids.has(p.id)) {
      uuids.set(p.id, newUuid())
    }
  }
  saving.value = true
  try {
    const created = await observationApi.create({
      trialId: props.trialId,
      traitId: t.trait.id,
      items: props.plots.map((p) => ({ plotId: p.id, clientUuid: uuids.get(p.id)! })),
      observedOn: observedOnFor(t.trait, v, observedOn.value),
      note: note.value.trim() || null,
      ...v,
    })
    uuids = new Map()
    ElMessage.success(`已录入 ${created.length} 个小区的"${t.trait.name}"`)
    emit('saved')
    if (keepOpen) {
      nextTrait()
    } else {
      visible.value = false
    }
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    saving.value = false
  }
}

/** 保存后自动跳到同一分类的下一个项目，方便按顺序连续录 */
function nextTrait() {
  const list = traitsInCategory.value
  const i = list.findIndex((t) => t.trait.id === traitId.value)
  if (i >= 0 && i < list.length - 1) {
    traitId.value = list[i + 1]!.trait.id
  } else {
    resetValue()
  }
}

async function onCustomCreated(created: TraitView) {
  await catalog.loadTraits(true)
  category.value = 'CUSTOM'
  traitId.value = created.trait.id
}
</script>

<template>
  <el-dialog v-model="visible" title="录入数据" width="760px" top="6vh" :close-on-click-modal="false">
    <div class="plots">
      <span class="muted">给 {{ plots.length }} 个小区录入：</span>
      <el-tag v-for="c in plotCodes.slice(0, 12)" :key="c" size="small" type="info">{{ c }}</el-tag>
      <span v-if="plotCodes.length > 12" class="muted">等</span>
    </div>

    <div class="section-title">分类</div>
    <el-radio-group v-model="category" class="wrap">
      <el-radio-button v-for="c in categories" :key="c" :value="c">{{ TRAIT_CATEGORY[c] }}</el-radio-button>
    </el-radio-group>

    <div class="section-title">
      项目
      <el-button v-if="auth.isTeacher" text type="primary" size="small" :icon="Plus" @click="customOpen = true">
        新增自定义项目
      </el-button>
    </div>
    <el-empty v-if="traitsInCategory.length === 0" :image-size="60" description="这个分类下还没有项目" />
    <el-radio-group v-else v-model="traitId" class="wrap">
      <el-radio-button v-for="t in traitsInCategory" :key="t.trait.id" :value="t.trait.id">
        {{ t.trait.name }}
      </el-radio-button>
    </el-radio-group>

    <template v-if="trait">
      <div class="section-title">
        {{ trait.trait.name }}<span v-if="trait.trait.unit && trait.trait.unit !== '级'" class="muted">（{{ trait.trait.unit }}）</span>
      </div>
      <div v-if="trait.trait.surveyTime || trait.trait.stdClause" class="hint std">
        <template v-if="trait.trait.surveyTime">{{ trait.trait.isStandard ? '调查时间：' : '' }}{{ trait.trait.surveyTime }}</template>
        <template v-if="trait.trait.stdClause"> · 标准条款 {{ trait.trait.stdClause }}</template>
      </div>
      <TraitValueInput v-model="value" :trait="trait" />

      <el-form label-width="80px" class="extra">
        <el-form-item v-if="trait.trait.dataType !== 'DATE'" label="调查日期">
          <el-date-picker
            v-model="observedOn"
            type="date"
            value-format="YYYY-MM-DD"
            :clearable="false"
            :disabled-date="(d: Date) => d.getTime() > Date.now()"
          />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="note" maxlength="200" placeholder="可不填" />
        </el-form-item>
      </el-form>
    </template>

    <template #footer>
      <el-button @click="visible = false">关闭</el-button>
      <el-button :loading="saving" :disabled="!trait" @click="save(true)">保存，接着录下一项</el-button>
      <el-button type="primary" :loading="saving" :disabled="!trait" @click="save(false)">保存</el-button>
    </template>

    <CustomTraitDialog v-model="customOpen" @created="onCustomCreated" />
  </el-dialog>
</template>

<style scoped>
.plots {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
  margin-bottom: 4px;
}

.section-title {
  margin: 16px 0 8px;
  font-weight: 600;
  display: flex;
  align-items: center;
  gap: 8px;
}

.wrap {
  flex-wrap: wrap;
  row-gap: 6px;
}

.std {
  margin: -4px 0 8px;
}

.extra {
  margin-top: 16px;
}
</style>
