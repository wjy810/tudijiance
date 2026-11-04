// 独立离线版的数据源：所有读写只经过本机数据库，首次安装即可使用。
import type {
  Field, FieldDetail, Layout, ObservationView, Plot, PlotDetail, TraitDef, TraitView, Trial, TrialStatus, Variety,
} from '@/shared/api'
import { today } from '@/shared/traitValue'
import traitSeed from '../../../server/src/main/resources/db/seed/traits-ny1301-2025.json'
import { db as defaultDb, type MobileDb, type OutboxBatch } from './db'
import type { NewBatch } from './outbox'

interface SeedTrait {
  code: string
  name: string
  category: TraitDef['category']
  dataType: TraitDef['dataType']
  agg: TraitDef['aggMethod']
  unit?: string
  precision?: number
  survey?: string
  clause?: string
  standard?: boolean
  computed?: boolean
  options?: { v: string; label: string; desc?: string }[]
}

const INITIALIZED = 'local:initialized'

export interface LocalFieldInput {
  name: string
  rowCount: number
  colCount: number
  varietyNames: string[]
}

async function value<T>(key: string, database: MobileDb): Promise<T> {
  const row = await database.cache.get(key)
  if (!row) throw new Error('本机没有找到这份数据，请返回首页重新选择实验田')
  return row.value as T
}

/** 在同一个事务里创建田块、试验和布局，避免中途退出留下半份数据。 */
async function insertField(input: LocalFieldInput, database: MobileDb): Promise<Field> {
  const fields = await value<Field[]>('fields', database)
  const varieties = await value<Variety[]>('varieties', database)
  const fieldId = Math.max(0, ...fields.map((f) => f.id)) + 1
  const plotStart = (await database.cache.get('local:nextPlotId'))?.value as number | undefined ?? 1
  const now = new Date().toISOString()
  const selectedVarieties = input.varietyNames.map((name) => {
    let variety = varieties.find((v) => v.name === name)
    if (!variety) {
      variety = {
        id: Math.max(0, ...varieties.map((v) => v.id)) + 1, name, code: null, breeder: null,
        ecotype: null, qualityType: null, controlType: null, remark: null, createdAt: now,
      }
      varieties.push(variety)
    }
    return variety
  })
  const field: Field = {
    id: fieldId, name: input.name.trim(), rowCount: input.rowCount, colCount: input.colCount,
    location: null, latitude: null, longitude: null, altitudeM: null, areaMu: null,
    soilType: null, irrigated: null, previousCrop: null, remark: '保存在本机的实验田',
    createdAt: now, updatedAt: now,
  }
  const plots: Plot[] = Array.from({ length: input.rowCount * input.colCount }, (_, i) => ({
    id: plotStart + i, fieldId, rowNo: Math.floor(i / input.colCount) + 1, colNo: i % input.colCount + 1,
    code: String(i + 1).padStart(2, '0'), kind: 'PLOT', lengthM: null, widthM: null,
    plantingRows: null, rowSpacingCm: null,
  }))
  const trial: Trial = {
    id: fieldId, fieldId, seasonYear: new Date().getFullYear(), name: '本机录入试验', trialType: 'OBSERVATION',
    wheatZone: null, replicateCount: null, remark: null, createdAt: now,
  }
  const layout: Layout = {
    trial,
    plantings: plots.map((p, i) => ({
      plotId: p.id, varietyId: selectedVarieties[i % selectedVarieties.length]?.id ?? null, replicateNo: null,
    })),
    links: [],
  }
  const savedAt = Date.now()
  await database.cache.bulkPut([
    { key: 'fields', value: [...fields, field], savedAt },
    { key: 'varieties', value: varieties, savedAt },
    { key: `field:${fieldId}`, value: { field, plots }, savedAt },
    { key: `trials:${fieldId}`, value: [trial], savedAt },
    { key: `layout:${trial.id}`, value: layout, savedAt },
    { key: 'local:nextPlotId', value: plotStart + plots.length, savedAt },
  ])
  return field
}

export async function initializeLocal(database: MobileDb = defaultDb): Promise<void> {
  await database.transaction('rw', database.cache, async () => {
    if (await database.cache.get(INITIALIZED)) return
    const traits: TraitView[] = (traitSeed as SeedTrait[]).map((t, i) => ({
      trait: {
        id: i + 1, code: t.code, name: t.name, category: t.category, dataType: t.dataType,
        unit: t.unit ?? null, precisionDigits: t.precision ?? null, aggMethod: t.agg,
        surveyTime: t.survey ?? null, stdClause: t.clause ?? null, isStandard: t.standard !== false,
        isComputed: t.computed === true, enabled: true, sortOrder: (i + 1) * 10, description: null,
      },
      options: (t.options ?? []).map((o, n) => ({
        id: (i + 1) * 100 + n, traitId: i + 1, valueCode: o.v, label: o.label,
        description: o.desc ?? null, sortOrder: n + 1,
      })),
      aliases: [],
    }))
    const savedAt = Date.now()
    await database.cache.bulkPut([
      { key: 'fields', value: [], savedAt }, { key: 'varieties', value: [], savedAt },
      { key: 'traits', value: traits, savedAt },
    ])
    await insertField({ name: '示例实验田', rowCount: 4, colCount: 6, varietyNames: ['示例品种 A', '示例品种 B', '示例品种 C'] }, database)
    await database.cache.put({ key: INITIALIZED, value: 1, savedAt })
  })
}

export async function createLocalField(input: LocalFieldInput, database: MobileDb = defaultDb): Promise<Field> {
  if (!input.name.trim()) throw new Error('请填写实验田名称')
  if (![input.rowCount, input.colCount].every((n) => Number.isInteger(n) && n >= 1 && n <= 30)) {
    throw new Error('行数和列数应为 1 到 30 的整数')
  }
  const varietyNames = [...new Set(input.varietyNames.map((n) => n.trim()).filter(Boolean))]
  await initializeLocal(database)
  return database.transaction('rw', database.cache, () => insertField({ ...input, varietyNames }, database))
}

/** local 状态永不上传，也不受联机版“清理已上传记录”的规则影响。 */
export async function saveLocalBatch(batch: NewBatch, database: MobileDb = defaultDb): Promise<number> {
  await initializeLocal(database)
  const detail = await value<FieldDetail>(`field:${batch.fieldId}`, database)
  const layout = await value<Layout>(`layout:${batch.trialId}`, database)
  const traits = await value<TraitView[]>('traits', database)
  if (layout.trial.fieldId !== batch.fieldId || !traits.some((t) => t.trait.id === batch.traitId)
    || !batch.plots.length || batch.plots.some((p) => !detail.plots.some((d) => d.id === p.plotId && d.kind === 'PLOT'))) {
    throw new Error('录入对应的实验田、小区或项目不存在，请重新选择')
  }
  return database.outbox.add({
    ...batch, createdAt: Date.now(), status: 'local', error: null, attempts: 0, syncedAt: null,
  })
}

function newestFirst(a: OutboxBatch, b: OutboxBatch): number {
  return b.observedOn.localeCompare(a.observedOn) || b.createdAt - a.createdAt || (b.id ?? 0) - (a.id ?? 0)
}

export function createLocalRepo(database: MobileDb = defaultDb) {
  async function read<T>(key: string) {
    await initializeLocal(database)
    return { data: await value<T>(key, database), offline: false, savedAt: Date.now() }
  }

  async function batches(trialId: number) {
    return (await database.outbox.where('trialId').equals(trialId).toArray())
      .filter((b) => b.status === 'local').sort(newestFirst)
  }

  return {
    fields: () => read<Field[]>('fields'),
    fieldDetail: (id: number) => read<FieldDetail>(`field:${id}`),
    trials: (id: number) => read<Trial[]>(`trials:${id}`),
    layout: (id: number) => read<Layout>(`layout:${id}`),
    traits: () => read<TraitView[]>('traits'),
    varieties: () => read<Variety[]>('varieties'),
    async status(trialId: number) {
      const layout = (await read<Layout>(`layout:${trialId}`)).data
      const detail = (await read<FieldDetail>(`field:${layout.trial.fieldId}`)).data
      const traits = (await read<TraitView[]>('traits')).data
      const entries = await batches(trialId)
      const todayPlots = new Set(entries.filter((b) => b.observedOn === today()).flatMap((b) => b.plots.map((p) => p.plotId)))
      const harvestId = traits.find((t) => t.trait.code === 'harvest_date')?.trait.id
      const harvested = new Set(entries.filter((b) => b.traitId === harvestId && b.value.valueDate && b.value.valueDate <= today()).flatMap((b) => b.plots.map((p) => p.plotId)))
      const planted = new Set(layout.plantings.filter((p) => p.varietyId !== null).map((p) => p.plotId))
      const stage = entries.find((b) => b.observedOn <= today() && traits.some((t) => t.trait.id === b.traitId && t.trait.category === 'PHENOLOGY'))
      const data: TrialStatus = {
        plots: detail.plots.map((p) => ({
          plotId: p.id, status: harvested.has(p.id) ? 'HARVESTED' : planted.has(p.id) ? 'GROWING' : 'NONE',
          enteredToday: todayPlots.has(p.id), abnormalNote: null,
        })),
        indicators: {
          totalPlots: detail.plots.length, planted: planted.size, enteredToday: todayPlots.size,
          abnormal: 0, harvested: harvested.size,
          weekEntries: entries.filter((b) => b.createdAt >= Date.now() - 7 * 86_400_000).reduce((n, b) => n + b.plots.length, 0),
          currentStage: stage?.traitName ?? null,
        },
      }
      return { data, offline: false, savedAt: Date.now() }
    },
    async plotDetail(trialId: number, plotId: number) {
      const layout = (await read<Layout>(`layout:${trialId}`)).data
      const detail = (await read<FieldDetail>(`field:${layout.trial.fieldId}`)).data
      const plot = detail.plots.find((p) => p.id === plotId)
      if (!plot) throw new Error('没有找到这个小区')
      const planting = layout.plantings.find((p) => p.plotId === plotId)
      const varieties = (await read<Variety[]>('varieties')).data
      const variety = varieties.find((v) => v.id === planting?.varietyId) ?? null
      const traits = (await read<TraitView[]>('traits')).data
      const entries = (await batches(trialId)).filter((b) => b.plots.some((p) => p.plotId === plotId))
      const records: ObservationView[] = entries.map((b) => {
        const trait = traits.find((t) => t.trait.id === b.traitId)!.trait
        return {
          ...b.value, id: b.id!, trialId, plotId, plotCode: plot.code, varietyId: variety?.id ?? null,
          varietyName: variety?.name ?? null, replicateNo: planting?.replicateNo ?? null,
          traitId: trait.id, traitName: trait.name, traitCategory: trait.category, dataType: trait.dataType,
          unit: trait.unit, standardTrait: trait.isStandard, observedOn: b.observedOn, displayValue: b.displayValue,
          note: b.note, source: 'LOCAL', recordedBy: '本机用户', recordedAt: new Date(b.createdAt).toISOString(),
          updatedBy: null, updatedAt: null, version: 1,
        }
      })
      const seen = new Set<number>()
      const latest = records.filter((r) => {
        if (seen.has(r.traitId)) return false
        seen.add(r.traitId)
        return true
      }).map((r) => ({
        traitId: r.traitId, traitName: r.traitName, category: r.traitCategory, standard: r.standardTrait,
        sortOrder: traits.find((t) => t.trait.id === r.traitId)!.trait.sortOrder,
        displayValue: r.displayValue, observedOn: r.observedOn, computed: false,
      })).sort((a, b) => a.sortOrder - b.sortOrder)
      const data: PlotDetail = {
        plot, plantingId: planting ? plotId : null, variety, replicateNo: planting?.replicateNo ?? null,
        abnormal: false, abnormalNote: null, latest, records,
      }
      return { data, offline: false, savedAt: Date.now() }
    },
  }
}
