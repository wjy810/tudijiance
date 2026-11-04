import 'fake-indexeddb/auto'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fieldApi } from '@/shared/api'
import { today } from '@/shared/traitValue'
import { MobileDb } from '../db'
import { createLocalField, createLocalRepo, initializeLocal, saveLocalBatch } from '../local'
import { counts, purgeDone, recent, syncPending, unsyncedPlots, type NewBatch } from '../outbox'

let database: MobileDb
let repo: ReturnType<typeof createLocalRepo>
let sequence = 0

beforeEach(() => {
  database = new MobileDb(`standalone-${++sequence}`)
  repo = createLocalRepo(database)
})
afterEach(async () => {
  await database.delete()
  vi.restoreAllMocks()
})

async function entry(overrides: Partial<NewBatch> = {}): Promise<NewBatch> {
  const traits = (await repo.traits()).data
  const trait = traits.find((t) => t.trait.code === 'plant_height')!
  return {
    userId: 1, fieldId: 1, trialId: 1, traitId: trait.trait.id, traitName: trait.trait.name,
    plots: [{ plotId: 1, code: '01', clientUuid: crypto.randomUUID() }],
    value: { valueNum: 78, valueText: null, valueDate: null }, displayValue: '78 cm',
    observedOn: today(), note: null, ...overrides,
  }
}

describe('standalone data', () => {
  it('initializes a usable field and the bundled traits without contacting a server', async () => {
    const request = vi.spyOn(fieldApi, 'list').mockRejectedValue(new Error('server unavailable'))
    await Promise.all([initializeLocal(database), initializeLocal(database)])
    expect((await repo.fields()).data).toHaveLength(1)
    expect((await repo.fieldDetail(1)).data.plots).toHaveLength(24)
    expect((await repo.layout(1)).data.plantings.every((p) => p.varietyId !== null)).toBe(true)
    expect((await repo.traits()).data.some((t) => t.trait.name === '株高')).toBe(true)
    expect((await repo.plotDetail(1, 1)).data.records).toHaveLength(0)
    expect(request).not.toHaveBeenCalled()
  })

  it('keeps saved entries and added fields when re-entering or reopening storage', async () => {
    await saveLocalBatch(await entry(), database)
    const added = await createLocalField({ name: '我的麦田', rowCount: 2, colCount: 3, varietyNames: ['品种一', '品种二'] }, database)
    const name = database.name
    database.close()
    database = new MobileDb(name)
    repo = createLocalRepo(database)
    await initializeLocal(database)
    expect((await repo.fields()).data).toHaveLength(2)
    expect((await repo.fieldDetail(added.id)).data.plots).toHaveLength(6)
    expect((await repo.fieldDetail(added.id)).data.plots[0]!.id).toBeGreaterThan(24)
    expect((await recent(1, 200, database))[0]?.displayValue).toBe('78 cm')
  })

  it('updates plot values and counts distinct plots after a batch is saved', async () => {
    await saveLocalBatch(await entry({ plots: [
      { plotId: 1, code: '01', clientUuid: 'one' }, { plotId: 2, code: '02', clientUuid: 'two' },
    ] }), database)
    await saveLocalBatch(await entry({ value: { valueNum: 80, valueText: null, valueDate: null }, displayValue: '80 cm' }), database)
    const status = (await repo.status(1)).data
    expect(status.indicators.enteredToday).toBe(2)
    expect(status.indicators.weekEntries).toBe(3)
    expect(status.plots.find((p) => p.plotId === 1)?.enteredToday).toBe(true)
    expect((await repo.plotDetail(1, 1)).data.latest[0]?.displayValue).toBe('80 cm')
    expect((await repo.plotDetail(1, 2)).data.latest[0]?.displayValue).toBe('78 cm')
    expect((await repo.plotDetail(1, 3)).data.latest).toHaveLength(0)
  })

  it('does not replace the latest measurement with a backdated entry', async () => {
    await saveLocalBatch(await entry(), database)
    await saveLocalBatch(await entry({ observedOn: '2020-01-01', displayValue: '50 cm', value: { valueNum: 50, valueText: null, valueDate: null } }), database)
    expect((await repo.plotDetail(1, 1)).data.latest[0]?.displayValue).toBe('78 cm')
  })

  it('never uploads or automatically purges local records', async () => {
    const id = await saveLocalBatch(await entry(), database)
    await database.outbox.update(id, { createdAt: Date.now() - 60 * 86_400_000 })
    const sender = vi.fn()
    await syncPending(1, sender, database)
    expect(sender).not.toHaveBeenCalled()
    expect(await purgeDone(30, database)).toBe(0)
    expect((await recent(1, 200, database))[0]?.status).toBe('local')
    expect(await counts(1, database)).toEqual({ pending: 0, failed: 0, others: 0 })
    expect((await unsyncedPlots(1, database)).size).toBe(0)
  })

  it('rejects invalid fields and mismatched plot references without saving partial data', async () => {
    await expect(createLocalField({ name: '麦田', rowCount: 0, colCount: 2, varietyNames: [] }, database)).rejects.toThrow('1 到 30')
    await expect(saveLocalBatch(await entry({ plots: [{ plotId: 9999, code: '?', clientUuid: 'invalid' }] }), database)).rejects.toThrow('不存在')
    expect(await recent(1, 200, database)).toHaveLength(0)
  })
})
