// 育种：季和品系在多个页面都要用（季和地块、品系库、系谱图、平面图的种植），取一次放在这里，改动后调用 reload
import { computed, ref } from 'vue'
import { defineStore } from 'pinia'

import { lineApi, seasonApi, type LineGraph, type LineRow, type Season, type SeasonView } from '@/shared/api'
import { byCode } from '@/shared/pedigree'
import { useCatalogStore } from '@/stores/catalog'

export const useBreedingStore = defineStore('breeding', () => {
  const seasonViews = ref<SeasonView[]>([])
  const graph = ref<LineGraph>({ seasons: [], lines: [] })
  let seasonsLoaded: Promise<void> | null = null
  let linesLoaded: Promise<void> | null = null

  function loadSeasons(force = false): Promise<void> {
    if (!seasonsLoaded || force) {
      seasonsLoaded = seasonApi.list().then((list) => {
        seasonViews.value = list
      }).catch((e) => {
        seasonsLoaded = null
        throw e
      })
    }
    return seasonsLoaded
  }

  /** 系谱图接口一次给全部品系和季 */
  function loadLines(force = false): Promise<void> {
    if (!linesLoaded || force) {
      linesLoaded = lineApi.graph().then((g) => {
        graph.value = g
      }).catch((e) => {
        linesLoaded = null
        throw e
      })
    }
    return linesLoaded
  }

  /** 品系改了（新建、杂交、选株、改名、种植）：品系、季、品种名称都重新取 */
  async function reload() {
    await Promise.all([loadLines(true), loadSeasons(true), useCatalogStore().loadVarieties(true)])
  }

  /** 季按开始日期从早到晚 */
  const seasons = computed<Season[]>(() =>
    seasonViews.value.map((v) => v.season).sort((a, b) => a.startDate.localeCompare(b.startDate) || a.id - b.id))
  const seasonById = computed(() => new Map(seasonViews.value.map((v) => [v.season.id, v.season])))
  const lines = computed<LineRow[]>(() => [...graph.value.lines].sort(byCode))
  const lineById = computed(() => new Map(graph.value.lines.map((l) => [l.id, l])))
  /** 地块属于哪一季 */
  const seasonOfField = computed(() => {
    const map = new Map<number, Season>()
    seasonViews.value.forEach((v) => v.fields.forEach((f) => map.set(f.id, v.season)))
    return map
  })

  function reset() {
    seasonViews.value = []
    graph.value = { seasons: [], lines: [] }
    seasonsLoaded = null
    linesLoaded = null
  }

  return {
    seasonViews, graph, seasons, seasonById, lines, lineById, seasonOfField,
    loadSeasons, loadLines, reload, reset,
  }
})
