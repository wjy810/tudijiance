<script setup lang="ts">
// 学生申请更正一条数据：改成什么（或者这条是多录的，删掉）、为什么。老师审核通过后才改
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'

import { correctionApi, type CorrectionKind, type ObservationView, type TraitValue } from '@/shared/api'
import { missingValue, normalizeValue, observedOnFor } from '@/shared/traitValue'
import { useCatalogStore } from '@/stores/catalog'
import { useCorrectionStore } from '@/stores/corrections'
import TraitValueInput from './TraitValueInput.vue'

/** 一条或几条数据；几条时（例如同一小区录了两个不同的日期）先选要更正哪一条 */
const props = defineProps<{ observations: ObservationView[] }>()
const visible = defineModel<boolean>({ required: true })
const emit = defineEmits<{ submitted: [] }>()

const catalog = useCatalogStore()
const corrections = useCorrectionStore()
const pickedId = ref<number | null>(null)
const kind = ref<CorrectionKind>('UPDATE')
const value = ref<TraitValue>({ valueNum: null, valueText: null, valueDate: null })
const observedOn = ref('')
const note = ref('')
const reason = ref('')
const saving = ref(false)

const picked = computed(() => props.observations.find((o) => o.id === pickedId.value) ?? null)
const trait = computed(() => (picked.value ? catalog.traitById.get(picked.value.traitId) ?? null : null))

function pick(o: ObservationView) {
  pickedId.value = o.id
  value.value = { valueNum: o.valueNum, valueText: o.valueText, valueDate: o.valueDate }
  observedOn.value = o.observedOn
  note.value = o.note ?? ''
}

watch(visible, async (open) => {
  if (!open) return
  await catalog.loadTraits().catch((e: Error) => ElMessage.error(e.message))
  kind.value = 'UPDATE'
  reason.value = ''
  const first = props.observations[0]
  if (first) pick(first)
  else pickedId.value = null
})

async function submit() {
  const o = picked.value
  const t = trait.value
  if (!o || !t) return
  const why = reason.value.trim()
  if (!why) {
    ElMessage.warning('请写一下为什么要改，老师审核时要看')
    return
  }
  saving.value = true
  try {
    if (kind.value === 'DELETE') {
      await correctionApi.create({ observationId: o.id, version: o.version, kind: 'DELETE', reason: why })
    } else {
      const missing = missingValue(t, value.value)
      if (missing) {
        ElMessage.warning(missing)
        return
      }
      const v = normalizeValue(t.trait, value.value)
      await correctionApi.create({
        observationId: o.id, version: o.version, kind: 'UPDATE', ...v,
        observedOn: observedOnFor(t.trait, v, observedOn.value), note: note.value.trim() || null, reason: why,
      })
    }
    ElMessage.success('已提交，老师审核通过后才会改')
    visible.value = false
    void corrections.refresh()
    emit('submitted')
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <el-dialog v-model="visible" title="申请更正" width="640px" append-to-body :close-on-click-modal="false">
    <div class="intro">
      发现录错了？写上应该是多少、为什么，老师审核通过后数据才会改，修改记录里会记下是你申请的。
    </div>

    <div v-if="observations.length > 1" class="pick">
      <div class="pick-label">要更正哪一条？</div>
      <button
        v-for="o in observations"
        :key="o.id"
        type="button"
        class="pick-item"
        :class="{ on: o.id === pickedId }"
        @click="pick(o)"
      >
        <b>{{ o.displayValue }}</b>
        <span class="muted">{{ o.observedOn }} · {{ o.recordedBy }} 录入</span>
      </button>
    </div>

    <template v-if="picked && trait">
      <div class="current">
        <span class="label">现在是</span>
        <span class="plot">小区 {{ picked.plotCode || '-' }}</span>
        <span>{{ picked.traitName }}</span>
        <b class="val">{{ picked.displayValue }}</b>
        <span class="muted">{{ picked.observedOn }} · {{ picked.recordedBy }} 录入</span>
      </div>

      <el-radio-group v-model="kind" class="kinds">
        <el-radio-button value="UPDATE">改成别的值</el-radio-button>
        <el-radio-button value="DELETE">这条是多录的 / 录错小区了，删掉</el-radio-button>
      </el-radio-group>

      <el-form label-width="80px" class="form">
        <template v-if="kind === 'UPDATE'">
          <el-form-item :label="`改成`">
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
        </template>
        <el-form-item label="为什么" required>
          <el-input
            v-model="reason"
            type="textarea"
            :rows="2"
            maxlength="500"
            show-word-limit
            placeholder="例如：看错了刻度，应该是 78；这条录到隔壁小区了"
          />
        </el-form-item>
      </el-form>
    </template>
    <el-empty v-else-if="picked" description="这个项目已经被删除或停用" :image-size="60" />

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="saving" :disabled="!trait" @click="submit">提交申请</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.intro {
  margin-bottom: 14px;
  padding: 10px 12px;
  border-radius: 10px;
  background: var(--sky-soft, #e8f0f4);
  border: 1px solid #cfdfe8;
  color: #2d4f62;
  font-size: 13px;
  line-height: 1.7;
}

.pick {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 14px;
}

.pick-label {
  width: 100%;
  font-size: 13px;
  color: var(--ink-2);
}

.pick-item {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 2px;
  padding: 8px 12px;
  border-radius: 10px;
  border: 1px solid var(--line-strong);
  background: var(--surface);
  font: inherit;
  font-size: 13px;
  cursor: pointer;
}

.pick-item.on {
  border-color: var(--forest);
  box-shadow: 0 0 0 2px rgba(43, 100, 73, 0.18);
}

.current {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 8px;
  padding: 10px 12px;
  border-radius: 10px;
  background: var(--surface-2);
  margin-bottom: 14px;
}

.current .label {
  font-size: 12px;
  color: var(--ink-3);
}

.current .plot {
  font-weight: 600;
}

.current .val {
  font-size: 16px;
  color: var(--clay, #a34a2b);
  text-decoration: line-through;
  text-decoration-color: rgba(163, 74, 43, 0.4);
}

.kinds {
  margin-bottom: 16px;
}

.form {
  margin-top: 4px;
}
</style>
