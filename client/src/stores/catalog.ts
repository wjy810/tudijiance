// 品种和录入项目在多个页面都要用，取一次放在这里，改动后调用 reload
import { computed, ref } from 'vue'
import { defineStore } from 'pinia'

import { traitApi, varietyApi, type TraitCategory, type TraitView, type Variety } from '@/shared/api'

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
    return groups
  })

  /** 能做统计图的项目：数值、分级，以及折合亩产等计算项目 */
  const statTraits = computed(() =>
    traits.value.filter(
      (t) => t.trait.enabled && (t.trait.dataType === 'NUMBER' || t.trait.dataType === 'GRADE'
        || t.trait.code === 'yield_per_mu' || t.trait.code === 'std_plot_yield'),
    ),
  )

  function reset() {
    traits.value = []
    varieties.value = []
    traitsLoaded = null
    varietiesLoaded = null
  }

  return { traits, varieties, varietyById, traitById, entryTraits, statTraits, loadTraits, loadVarieties, reset }
})
