<script setup lang="ts">
// 学生申请更正：选要更正哪一条，填改成什么（或者删掉）和原因。要联网，老师审核通过后才改
import { computed, ref, watch } from 'vue'
import { showToast } from 'vant'

import { correctionApi, type CorrectionKind, type ObservationView, type TraitValue, type TraitView } from '@/shared/api'
import { missingValue, normalizeValue, observedOnFor } from '@/shared/traitValue'
import { useFieldStore } from '../stores/field'
import { repo } from '../repo'
import ValueEditor from './ValueEditor.vue'

/** 同一个小区、同一个项目的几条数据（最新的在前） */
const props = defineProps<{ observations: ObservationView[] }>()
const show = defineModel<boolean>('show', { required: true })
const emit = defineEmits<{ submitted: [] }>()

const field = useFieldStore()
const pickedId = ref<number | null>(null)
const kind = ref<CorrectionKind>('UPDATE')
const value = ref<TraitValue>({ valueNum: null, valueText: null, valueDate: null })
const reason = ref('')
const saving = ref(false)

/** 直接打开小区页时实验田的数据还没取，项目从缓存或服务器单独取 */
const extraTraits = ref<TraitView[]>([])
const picked = computed(() => props.observations.find((o) => o.id === pickedId.value) ?? null)
const trait = computed(() => {
  const id = picked.value?.traitId
  if (id == null) return null
  return field.traitById.get(id) ?? extraTraits.value.find((t) => t.trait.id === id) ?? null
})

function pick(o: ObservationView) {
  pickedId.value = o.id
  value.value = { valueNum: o.valueNum, valueText: o.valueText, valueDate: o.valueDate }
}

watch(show, async (open) => {
  if (!open) return
  kind.value = 'UPDATE'
  reason.value = ''
  const first = props.observations[0]
  if (first) pick(first)
  if (first && !field.traitById.has(first.traitId) && extraTraits.value.length === 0) {
    try {
      extraTraits.value = (await repo.traits()).data
    } catch (e) {
      showToast((e as Error).message)
    }
  }
})

async function submit() {
  const o = picked.value
  const t = trait.value
  if (!o || !t) return
  const why = reason.value.trim()
  if (!why) {
    showToast('写一下为什么要改，老师审核时要看')
    return
  }
  if (kind.value === 'UPDATE') {
    const missing = missingValue(t, value.value)
    if (missing) {
      showToast(missing)
      return
    }
  }
  saving.value = true
  try {
    if (kind.value === 'DELETE') {
      await correctionApi.create({ observationId: o.id, version: o.version, kind: 'DELETE', reason: why })
    } else {
      const v = normalizeValue(t.trait, value.value)
      await correctionApi.create({
        observationId: o.id, version: o.version, kind: 'UPDATE', ...v,
        observedOn: observedOnFor(t.trait, v, o.observedOn), note: o.note, reason: why,
      })
    }
    showToast({ message: '已提交，老师审核通过后才会改', type: 'success' })
    show.value = false
    emit('submitted')
  } catch (e) {
    showToast((e as Error).message)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <van-popup v-model:show="show" position="bottom" round closeable :style="{ height: '90%' }" teleport="body">
    <div class="wrap">
      <header class="head">
        <div class="m-title title">申请更正</div>
        <div class="m-muted sub">发现录错了？老师审核通过后数据才会改。</div>
      </header>

      <div v-if="picked" class="body">
        <div v-if="observations.length > 1" class="pick">
          <button v-for="o in observations" :key="o.id" type="button" class="pick-item" :class="{ on: o.id === pickedId }" @click="pick(o)">
            <b>{{ o.displayValue }}</b><span>{{ o.observedOn.slice(5) }} · {{ o.recordedBy }}</span>
          </button>
        </div>

        <div class="current m-card">
          <span class="m-muted">{{ picked.traitName }} 现在是</span>
          <b class="old">{{ picked.displayValue }}</b>
          <span class="m-muted">{{ picked.observedOn }} · {{ picked.recordedBy }} 录入</span>
        </div>

        <div class="kinds">
          <button type="button" class="kind" :class="{ on: kind === 'UPDATE' }" @click="kind = 'UPDATE'">改成别的值</button>
          <button type="button" class="kind" :class="{ on: kind === 'DELETE' }" @click="kind = 'DELETE'">多录了 / 录错小区，删掉</button>
        </div>

        <ValueEditor v-if="kind === 'UPDATE' && trait" v-model="value" :trait="trait" />

        <van-field
          v-model="reason" type="textarea" rows="2" autosize maxlength="500" show-word-limit
          label="为什么" label-align="top" placeholder="例如：看错了刻度，应该是 78" class="reason"
        />
      </div>

      <footer class="foot">
        <van-button block round class="btn-wheat" :loading="saving" :disabled="!trait" @click="submit">提交申请</van-button>
      </footer>
    </div>
  </van-popup>
</template>

<style scoped>
.wrap {
  height: 100%;
  display: flex;
  flex-direction: column;
}

.head {
  padding: 18px 18px 10px;
  border-bottom: 1px solid var(--line);
}

.title {
  font-size: 19px;
}

.sub {
  margin-top: 4px;
  font-size: 13px;
}

.body {
  flex: 1;
  overflow-y: auto;
  -webkit-overflow-scrolling: touch;
  padding: 12px 14px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.pick {
  display: flex;
  gap: 8px;
  overflow-x: auto;
}

.pick-item {
  flex: none;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 2px;
  padding: 8px 12px;
  border-radius: 12px;
  border: 1px solid var(--line-strong);
  background: var(--surface);
  font: inherit;
  font-size: 13px;
  color: var(--ink-2);
}

.pick-item.on {
  border-color: var(--forest);
  background: var(--green-soft);
}

.current {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 12px 14px;
}

.old {
  font-size: 22px;
  color: var(--clay);
  text-decoration: line-through;
  text-decoration-color: rgba(176, 84, 58, 0.45);
}

.kinds {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
}

.kind {
  min-height: 44px;
  padding: 6px 10px;
  border-radius: 12px;
  border: 1px solid var(--line-strong);
  background: var(--surface);
  font: inherit;
  font-size: 14px;
  color: var(--ink);
}

.kind.on {
  background: var(--forest);
  border-color: var(--forest);
  color: #fbf3df;
}

.reason {
  border-radius: 12px;
  border: 1px solid var(--line);
}

.foot {
  padding: 10px 14px calc(10px + env(safe-area-inset-bottom));
  border-top: 1px solid var(--line);
  background: var(--surface);
}
</style>
