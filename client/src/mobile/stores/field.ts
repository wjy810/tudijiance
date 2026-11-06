// 手机端当前在看哪块地、哪个试验，以及录入要用的项目、品种、布局、小区状态。
// 都通过 repo 取：有网取服务器的并存一份，没网用本机存的
import { computed, ref } from 'vue'
import { defineStore } from 'pinia'

import type {
  Field, Layout, Plot, PlotStatus, SeasonView, TraitCategory, TraitView, Trial, TrialStatus, Variety,
} from '@/shared/api'
import type { FieldLayout } from '@/shared/fieldLayout'
import { useAuthStore } from '@/stores/auth'
import { mobileStorageKey } from '@/shared/edition'
import { loadSeasons, repo, sortTrials } from '../repo'
import { unsyncedPlots } from '../outbox'

const FIELD_KEY = mobileStorageKey('field')
const TRIAL_KEY = mobileStorageKey('trial')
const RECENT_KEY = mobileStorageKey('recentTraits')

const readNum = (k: string) => Number(localStorage.getItem(k)) || null

export const useFieldStore = defineStore('mobile-field', () => {
  const auth = useAuthStore()
  const fields = ref<Field[]>([])
  /** 育种的季：地块属于哪一季 */
  const seasons = ref<SeasonView[]>([])
  const trials = ref<Trial[]>([])
  const plots = ref<Plot[]>([])
  const layout = ref<Layout | null>(null)
  /** 实验田的区块布局（老师在电脑上保存过才有）；没有时按行列显示 */
  const blockLayout = ref<FieldLayout | null>(null)
  const status = ref<TrialStatus | null>(null)
  const traits = ref<TraitView[]>([])
  const varieties = ref<Variety[]>([])
  const fieldId = ref<number | null>(readNum(FIELD_KEY))
  const trialId = ref<number | null>(readNum(TRIAL_KEY))
  /** 有数据是从本机缓存读的（没网），值是那份数据的时间 */
  const offlineSince = ref<number | null>(null)
  /** 还没传上去的录入涉及的小区 */
  const unsynced = ref(new Set<number>())
  const loading = ref(false)
  const error = ref<string | null>(null)

  const field = computed(() => fields.value.find((f) => f.id === fieldId.value) ?? null)
  const seasonNameById = computed(() => new Map(seasons.value.map((v) => [v.season.id, v.season.name])))

  /** 换地时显示的名字：育种的地块带上季，例如"2026-2027 年度大田 · 东地" */
  function fieldLabel(f: Field): string {
    const s = f.seasonId ? seasonNameById.value.get(f.seasonId) : null
    return s ? `${s} · ${f.name}` : f.name
  }
  const trial = computed(() => trials.value.find((t) => t.id === trialId.value) ?? null)
  const varietyById = computed(() => new Map(varieties.value.map((v) => [v.id, v])))
  const traitById = computed(() => new Map(traits.value.map((t) => [t.trait.id, t])))
  const plantingByPlot = computed(() => new Map((layout.value?.plantings ?? []).map((p) => [p.plotId, p])))
  const statusByPlot = computed(() => new Map<number, PlotStatus>((status.value?.plots ?? []).map((p) => [p.plotId, p])))

  /** 能录入的项目（启用的、不是自动计算的），按分类分组 */
  const entryGroups = computed(() => {
    const groups = new Map<TraitCategory, TraitView[]>()
    for (const t of traits.value) {
      if (t.trait.enabled && !t.trait.isComputed) {
        groups.set(t.trait.category, [...(groups.get(t.trait.category) ?? []), t])
      }
    }
    // 重要项目排在前面
    for (const list of groups.values()) {
      list.sort((a, b) => Number(!!b.trait.important) - Number(!!a.trait.important) || a.trait.sortOrder - b.trait.sortOrder)
    }
    return groups
  })

  function noteOffline(r: { offline: boolean; savedAt: number }) {
    if (r.offline) {
      offlineSince.value = Math.min(offlineSince.value ?? r.savedAt, r.savedAt)
    }
  }

  async function loadCatalog() {
    const [t, v] = await Promise.all([repo.traits(), repo.varieties()])
    traits.value = t.data
    varieties.value = v.data
    noteOffline(t)
    noteOffline(v)
  }

  async function loadTrial() {
    if (!trialId.value) {
      layout.value = null
      status.value = null
      return
    }
    const [l, s] = await Promise.all([repo.layout(trialId.value), repo.status(trialId.value)])
    layout.value = l.data
    status.value = s.data
    noteOffline(l)
    noteOffline(s)
    await refreshUnsynced()
  }

  async function loadField() {
    if (!fieldId.value) {
      plots.value = []
      blockLayout.value = null
      trials.value = []
      return
    }
    const [d, t] = await Promise.all([repo.fieldDetail(fieldId.value), repo.trials(fieldId.value)])
    plots.value = d.data.plots
    blockLayout.value = d.data.layoutSaved && d.data.layout ? d.data.layout : null
    trials.value = sortTrials(t.data)
    noteOffline(d)
    noteOffline(t)
    if (!trials.value.some((x) => x.id === trialId.value)) {
      trialId.value = trials.value[0]?.id ?? null
    }
    localStorage.setItem(TRIAL_KEY, String(trialId.value ?? ''))
    await loadTrial()
  }

  /** 进入 APP 时加载全部；出错（例如没网又没下载过）时记下原因 */
  async function load() {
    loading.value = true
    error.value = null
    offlineSince.value = null
    try {
      await loadCatalog()
      const f = await repo.fields()
      // 季取不到（例如育种模块停用了）不影响录入
      seasons.value = (await loadSeasons().catch(() => null))?.data ?? []
      // 最近一季的地块排在最前面，原来的实验田在后面
      const start = new Map(seasons.value.map((v) => [v.season.id, v.season.startDate]))
      fields.value = [...f.data].sort((a, b) => {
        const sa = a.seasonId ? start.get(a.seasonId) ?? '' : ''
        const sb = b.seasonId ? start.get(b.seasonId) ?? '' : ''
        return sb.localeCompare(sa) || a.id - b.id
      })
      noteOffline(f)
      if (!fields.value.some((x) => x.id === fieldId.value)) {
        fieldId.value = fields.value[0]?.id ?? null
      }
      localStorage.setItem(FIELD_KEY, String(fieldId.value ?? ''))
      await loadField()
    } catch (e) {
      error.value = (e as Error).message
    } finally {
      loading.value = false
    }
  }

  async function selectField(id: number) {
    fieldId.value = id
    localStorage.setItem(FIELD_KEY, String(id))
    trialId.value = null
    await loadField()
  }

  async function selectTrial(id: number) {
    trialId.value = id
    localStorage.setItem(TRIAL_KEY, String(id))
    await loadTrial()
  }

  async function refreshUnsynced() {
    unsynced.value = trialId.value ? await unsyncedPlots(trialId.value) : new Set()
  }

  // ---------- 最近用过的项目，录入时排在最前面 ----------

  const recentKey = computed(() => `${RECENT_KEY}.${auth.user?.id ?? 0}`)
  const recentTraitIds = ref<number[]>(JSON.parse(localStorage.getItem(recentKey.value) || '[]'))

  function rememberTrait(id: number) {
    recentTraitIds.value = [id, ...recentTraitIds.value.filter((x) => x !== id)].slice(0, 8)
    localStorage.setItem(recentKey.value, JSON.stringify(recentTraitIds.value))
  }

  const recentTraits = computed(() => recentTraitIds.value
    .map((id) => traitById.value.get(id))
    .filter((t): t is TraitView => !!t && t.trait.enabled && !t.trait.isComputed))

  return {
    fields, seasons, trials, plots, layout, blockLayout, status, traits, varieties, fieldId, trialId, offlineSince, unsynced, loading, error,
    field, trial, fieldLabel, varietyById, traitById, plantingByPlot, statusByPlot, entryGroups, recentTraits,
    load, loadTrial, selectField, selectTrial, refreshUnsynced, rememberTrait,
  }
})
