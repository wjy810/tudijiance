// 手机端取数据：先找服务器，取到了顺便存一份在本机；没网时用本机存的那份。
// 页面据此显示"离线"和数据是什么时候的
import {
  ApiError, fieldApi, isNetworkError, observationApi, traitApi, trialApi, varietyApi,
  type Field, type FieldDetail, type Layout, type PlotDetail, type Trial, type TrialStatus, type TraitView, type Variety,
} from '@/shared/api'
import { db as defaultDb, type MobileDb } from './db'

export interface Loaded<T> {
  data: T
  /** 这次是从本机缓存里读的（没网） */
  offline: boolean
  /** 数据是什么时候从服务器取的（毫秒） */
  savedAt: number
}

export async function cached<T>(key: string, fetcher: () => Promise<T>, database: MobileDb = defaultDb): Promise<Loaded<T>> {
  try {
    const data = await fetcher()
    const savedAt = Date.now()
    await database.cache.put({ key, value: data, savedAt })
    return { data, offline: false, savedAt }
  } catch (e) {
    if (!isNetworkError(e)) {
      throw e
    }
    const row = await database.cache.get(key)
    if (!row) {
      throw new ApiError('没有网络，这部分数据还没下载到手机上。联网后打开一次，或在"我的"里下载离线数据', null)
    }
    return { data: row.value as T, offline: true, savedAt: row.savedAt }
  }
}

export const repo = {
  fields: () => cached<Field[]>('fields', fieldApi.list),
  fieldDetail: (fieldId: number) => cached<FieldDetail>(`field:${fieldId}`, () => fieldApi.detail(fieldId)),
  trials: (fieldId: number) => cached<Trial[]>(`trials:${fieldId}`, () => trialApi.list(fieldId)),
  layout: (trialId: number) => cached<Layout>(`layout:${trialId}`, () => trialApi.layout(trialId)),
  status: (trialId: number) => cached<TrialStatus>(`status:${trialId}`, () => observationApi.status(trialId)),
  plotDetail: (trialId: number, plotId: number) =>
    cached<PlotDetail>(`plot:${trialId}:${plotId}`, () => observationApi.plotDetail(trialId, plotId)),
  traits: () => cached<TraitView[]>('traits', traitApi.list),
  varieties: () => cached<Variety[]>('varieties', varietyApi.list),
}

/** 最近的试验排在前面 */
export function sortTrials(list: Trial[]): Trial[] {
  return [...list].sort((a, b) => b.seasonYear - a.seasonYear || b.id - a.id)
}

/**
 * 下载离线要用的数据：录入项目、品种、各块实验田的小区，以及每块地最近一个试验的布局和状态。
 * 出发去田里之前联网点一下，田里没信号也能看平面图、录数据。
 */
export async function downloadForOffline(onProgress?: (text: string) => void): Promise<number> {
  let count = 0
  const step = async (text: string, task: () => Promise<unknown>) => {
    onProgress?.(text)
    await task()
    count++
  }
  await step('录入项目', repo.traits)
  await step('品种', repo.varieties)
  const fields = (await repo.fields()).data
  for (const f of fields) {
    await step(`${f.name}：小区`, () => repo.fieldDetail(f.id))
    const trials = sortTrials((await repo.trials(f.id)).data)
    const latest = trials[0]
    if (latest) {
      await step(`${f.name}：试验布局`, () => repo.layout(latest.id))
      await step(`${f.name}：小区状态`, () => repo.status(latest.id))
    }
  }
  return count
}
