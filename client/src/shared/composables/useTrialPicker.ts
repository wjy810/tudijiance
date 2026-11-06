// 数据查看、统计页面共用的"选地块 → 选试验"。记住上次选的，下次打开还是它。
// 地块带上季名，最新一季的在前；季里的地块只有一个试验
import { computed, ref, watch } from 'vue'

import { fieldApi, seasonApi, trialApi, type Field, type Season, type Trial } from '@/shared/api'
import { landLabel, seasonMap, sortLands } from '@/shared/lands'

const FIELD_KEY = 'tudijiance.lastField'
const TRIAL_KEY = 'tudijiance.lastTrial'

function readNumber(key: string): number | null {
  try {
    const v = Number(localStorage.getItem(key))
    return Number.isFinite(v) && v > 0 ? v : null
  } catch {
    return null
  }
}

function writeNumber(key: string, value: number | null): void {
  try {
    if (value) {
      localStorage.setItem(key, String(value))
    } else {
      localStorage.removeItem(key)
    }
  } catch {
    // 本机存储不可用时不记忆
  }
}

/** 记住在平面图上打开的试验，切到数据查看、统计页面时默认选它 */
export function rememberTrial(fieldId: number, trialId: number | null): void {
  writeNumber(FIELD_KEY, fieldId)
  writeNumber(TRIAL_KEY, trialId)
}

export function useTrialPicker() {
  const fields = ref<Field[]>([])
  const seasons = ref(new Map<number, Season>())
  const trials = ref<Trial[]>([])
  const fieldId = ref<number | null>(null)
  const trialId = ref<number | null>(null)

  const field = computed(() => fields.value.find((f) => f.id === fieldId.value) ?? null)
  const trial = computed(() => trials.value.find((t) => t.id === trialId.value) ?? null)

  async function loadTrials(preferred: number | null) {
    if (!fieldId.value) {
      trials.value = []
      trialId.value = null
      return
    }
    const list = await trialApi.list(fieldId.value)
    trials.value = [...list].sort((a, b) => b.seasonYear - a.seasonYear || b.id - a.id)
    trialId.value = trials.value.find((t) => t.id === preferred)?.id ?? trials.value[0]?.id ?? null
  }

  async function init() {
    const [list, views] = await Promise.all([fieldApi.list(), seasonApi.list().catch(() => [])])
    seasons.value = seasonMap(views)
    fields.value = sortLands(list, seasons.value)
    const last = readNumber(FIELD_KEY)
    fieldId.value = fields.value.find((f) => f.id === last)?.id ?? fields.value[0]?.id ?? null
    await loadTrials(readNumber(TRIAL_KEY))
  }

  async function selectField(id: number | null) {
    fieldId.value = id
    await loadTrials(null)
  }

  watch([fieldId, trialId], ([f, t]) => {
    if (f) {
      rememberTrial(f, t)
    }
  })

  const fieldLabel = (f: Field) => landLabel(f, seasons.value)

  return { fields, trials, fieldId, trialId, field, trial, init, selectField, fieldLabel }
}
