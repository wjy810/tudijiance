// 品种和录入项目在多个页面都要用，取一次放在这里，改动后调用 reload
import { computed, ref } from 'vue'
import { defineStore } from 'pinia'

import { traitApi, varietyApi, type TraitCategory, type TraitView, type Variety } from '@/shared/api'

const COMPUTED_CODES = new Set(['whole_growth_days', 'growth_days', 'spike_rate', 'std_plot_yield', 'yield_per_mu'])

export const useCatalogStore = defineStore('catalog', () => {
  const traits = ref<TraitView[]>([])
  const varieties = ref<Variety[]>([])
  let traitsLoaded: Promise<void> | null = null
  let varietiesLoaded: Promise<void> | null = null

  function loadTraits(force = false): Promise<void> {
    if (!traitsLoaded || force) {
      traitsLoaded = traitApi.list().then((list) => {
        traits.value = list
      }).catch((e) => {
        traitsLoaded = null
        throw e
      })
    }
    return traitsLoaded
  }

  function loadVarieties(force = false): Promise<void> {
    if (!varietiesLoaded || force) {
      varietiesLoaded = varietyApi.list().then((list) => {
        varieties.value = list
      }).catch((e) => {
        varietiesLoaded = null
        throw e
      })
    }
    return varietiesLoaded
  }

  const varietyById = computed(() => new Map(varieties.value.map((v) => [v.id, v])))
  /** 原来品种比较用的品种：不含育种里的杂交组合、选株后代（原始材料两边都用） */
  const comparisonVarieties = computed(() =>
    varieties.value.filter((v) => !v.lineKind || v.lineKind === 'FOUNDER'))
  const traitById = computed(() => new Map(traits.value.map((t) => [t.trait.id, t])))

  /** 能手动录入的项目（启用的、不是自动计算的），按分类分组，保持后端给的顺序 */
  const entryTraits = computed(() => {
    const groups = new Map<TraitCategory, TraitView[]>()
    for (const t of traits.value) {
      if (!t.trait.enabled || t.trait.isComputed) {
        continue
      }
      const list = groups.get(t.trait.category) ?? []
      list.push(t)
      groups.set(t.trait.category, list)
    }
    // 重要项目排在前面，其余按原来的顺序
    for (const list of groups.values()) {
      list.sort((a, b) => Number(!!b.trait.important) - Number(!!a.trait.important) || a.trait.sortOrder - b.trait.sortOrder)
    }
    return groups
  })

  /** 能做统计图、按数值着色的项目：数值、分级，以及服务器能算的计算项目（和后端 ComputedTraits 一致） */
  const statTraits = computed(() =>
    traits.value.filter((t) => t.trait.enabled && (t.trait.isComputed
      ? COMPUTED_CODES.has(t.trait.code)
      : t.trait.dataType === 'NUMBER' || t.trait.dataType === 'GRADE')),
  )

  function reset() {
    traits.value = []
    varieties.value = []
    traitsLoaded = null
    varietiesLoaded = null
  }

  return { traits, varieties, varietyById, comparisonVarieties, traitById, entryTraits, statTraits, loadTraits, loadVarieties, reset }
})
