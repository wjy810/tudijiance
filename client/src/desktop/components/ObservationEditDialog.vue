<script setup lang="ts">
// 老师修改一条录入的数据。别人刚改过时服务器会拒绝，提示刷新后再改
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'

import { observationApi, type ObservationView, type TraitValue } from '@/shared/api'
import { missingValue, normalizeValue, observedOnFor } from '@/shared/traitValue'
import { useCatalogStore } from '@/stores/catalog'
import TraitValueInput from './TraitValueInput.vue'

const props = defineProps<{ observation: ObservationView | null }>()
const visible = defineModel<boolean>({ required: true })
const emit = defineEmits<{ saved: [view: ObservationView] }>()

const catalog = useCatalogStore()
const value = ref<TraitValue>({ valueNum: null, valueText: null, valueDate: null })
const observedOn = ref('')
const note = ref('')
const saving = ref(false)

const trait = computed(() => (props.observation ? catalog.traitById.get(props.observation.traitId) ?? null : null))

watch(visible, async (open) => {
  const o = props.observation
  if (!open || !o) {
    return
  }
  await catalog.loadTraits().catch((e: Error) => ElMessage.error(e.message))
  value.value = { valueNum: o.valueNum, valueText: o.valueText, valueDate: o.valueDate }
  observedOn.value = o.observedOn
  note.value = o.note ?? ''
})

async function save() {
  const o = props.observation
  const t = trait.value
  if (!o || !t) {
    return
  }
  const missing = missingValue(t, value.value)
  if (missing) {
    ElMessage.warning(missing)
    return
  }
  const v = normalizeValue(t.trait, value.value)
  saving.value = true
  try {
    const saved = await observationApi.update(o.id, o.version, observedOnFor(t.trait, v, observedOn.value), v,
      note.value.trim() || null)
    ElMessage.success('已修改')
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
  <el-dialog v-model="visible" title="修改数据" width="620px" append-to-body :close-on-click-modal="false">
    <template v-if="observation && trait">
      <div class="who muted">
        小区 {{ observation.plotCode || '-' }} · {{ observation.varietyName || '未分配品种' }} ·
        {{ observation.recordedBy }} 录入于 {{ observation.recordedAt.replace('T', ' ').slice(0, 16) }}
      </div>
      <el-form label-width="80px">
        <el-form-item :label="trait.trait.name">
          <TraitValueInput v-model="value" :trait="trait" />
        </el-form-item>
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
          <el-input v-model="note" maxlength="200" />
        </el-form-item>
      </el-form>
      <div class="hint">修改会留下记录，可以在"修改记录"里看到谁在什么时候把什么改成了什么。</div>
    </template>
    <el-empty v-else-if="observation" description="这个项目已经被删除或停用，不能修改" :image-size="60" />
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="saving" :disabled="!trait" @click="save">保存</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.who {
  margin-bottom: 14px;
}
</style>
