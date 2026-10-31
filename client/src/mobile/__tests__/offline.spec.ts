import 'fake-indexeddb/auto'

import { beforeEach, describe, expect, it } from 'vitest'

import { ApiError } from '@/shared/api'
import { MobileDb, type OutboxBatch } from '@/mobile/db'
import { cached } from '@/mobile/repo'
import { addBatch, counts, discard, purgeDone, recent, retry, syncPending, unsyncedPlots, type NewBatch } from '@/mobile/outbox'

let db: MobileDb
let n = 0

beforeEach(() => {
  db = new MobileDb(`test-${++n}`)
})

const offline = () => Promise.reject(new ApiError('连不上服务器', null))

function batch(over: Partial<NewBatch> = {}): NewBatch {
  return {
    userId: 1, fieldId: 1, trialId: 10, traitId: 5, traitName: '株高',
    plots: [{ plotId: 101, code: '101', clientUuid: `u-${Math.random()}` }],
    value: { valueNum: 78, valueText: null, valueDate: null }, displayValue: '78 cm',
    observedOn: '2026-05-01', note: null, ...over,
  }
}

describe('cached', () => {
  it('stores what the server returns', async () => {
    const r = await cached('fields', async () => [{ id: 1 }], db)
    expect(r.offline).toBe(false)
    expect((await db.cache.get('fields'))?.value).toEqual([{ id: 1 }])
  })

  it('falls back to the stored copy when offline', async () => {
    await cached('fields', async () => [{ id: 1 }], db)
    const r = await cached('fields', offline, db)
    expect(r.offline).toBe(true)
    expect(r.data).toEqual([{ id: 1 }])
  })

  it('explains when nothing was downloaded yet', async () => {
    await expect(cached('trials:9', offline, db)).rejects.toThrow('还没下载到手机上')
  })

  it('does not hide errors from the server', async () => {
    await cached('fields', async () => [{ id: 1 }], db)
    await expect(cached('fields', () => Promise.reject(new ApiError('没有权限', 403)), db)).rejects.toThrow('没有权限')
  })
})

describe('outbox', () => {
  it('uploads pending batches in order and marks them done', async () => {
    await addBatch(batch({ traitName: '第一条' }), db)
    await addBatch(batch({ traitName: '第二条' }), db)
    const sent: string[] = []
    const r = await syncPending(1, async (b: OutboxBatch) => { sent.push(b.traitName) }, db)
    expect(r).toEqual({ sent: 2, failed: 0, stoppedBy: null })
    expect(sent).toEqual(['第一条', '第二条'])
    expect(await counts(1, db)).toEqual({ pending: 0, failed: 0, others: 0 })
    expect((await recent(1, 10, db))[0]!.status).toBe('done')
  })

  it('stops when offline and keeps the rest for later', async () => {
    await addBatch(batch(), db)
    await addBatch(batch(), db)
    let calls = 0
    const r = await syncPending(1, async () => {
      calls++
      if (calls === 2) throw new ApiError('连不上服务器', null)
    }, db)
    expect(r).toEqual({ sent: 1, failed: 0, stoppedBy: 'offline' })
    expect((await counts(1, db)).pending).toBe(1)
  })

  it('stops on an expired login without marking anything failed', async () => {
    await addBatch(batch(), db)
    const r = await syncPending(1, () => Promise.reject(new ApiError('登录过期', 401)), db)
    expect(r.stoppedBy).toBe('auth')
    expect((await counts(1, db)).pending).toBe(1)
  })

  it('marks batches the server rejects as failed, which can be retried or discarded', async () => {
    const id = await addBatch(batch(), db)
    await syncPending(1, () => Promise.reject(new ApiError('株高已停用', 400)), db)
    const [b] = await recent(1, 10, db)
    expect(b!.status).toBe('failed')
    expect(b!.error).toBe('株高已停用')
    await retry(id, db)
    expect((await counts(1, db)).pending).toBe(1)
    await discard(id, db)
    expect(await recent(1, 10, db)).toHaveLength(0)
  })

  it('only uploads the current user\'s entries', async () => {
    await addBatch(batch({ userId: 2 }), db)
    const r = await syncPending(1, async () => {}, db)
    expect(r.sent).toBe(0)
    expect(await counts(1, db)).toEqual({ pending: 0, failed: 0, others: 1 })
  })

  it('lists plots that still have unsynced entries', async () => {
    await addBatch(batch({ plots: [{ plotId: 7, code: '7', clientUuid: 'a' }, { plotId: 8, code: '8', clientUuid: 'b' }] }), db)
    expect([...await unsyncedPlots(10, db)].sort()).toEqual([7, 8])
    await syncPending(1, async () => {}, db)
    expect((await unsyncedPlots(10, db)).size).toBe(0)
  })

  it('purges old uploaded records', async () => {
    const id = await addBatch(batch(), db)
    await db.outbox.update(id, { status: 'done', syncedAt: Date.now() - 40 * 86_400_000 })
    expect(await purgeDone(30, db)).toBe(1)
  })
})
