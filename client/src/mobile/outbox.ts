// 待上传队列：录入的数据先存在本机，联网后按录入的先后上传。
// 服务器按客户端编号去重，所以上传到一半断网、再传一次也不会重复
import { ApiError, isNetworkError, observationApi } from '@/shared/api'
import { db as defaultDb, type MobileDb, type OutboxBatch } from './db'

export type NewBatch = Omit<OutboxBatch, 'id' | 'status' | 'error' | 'attempts' | 'syncedAt' | 'createdAt'>

/** 把一批录入发给服务器；出错时抛出 ApiError */
export type Sender = (batch: OutboxBatch) => Promise<void>

export const sendToServer: Sender = async (b) => {
  await observationApi.create({
    trialId: b.trialId,
    traitId: b.traitId,
    items: b.plots.map((p) => ({ plotId: p.plotId, clientUuid: p.clientUuid })),
    observedOn: b.observedOn,
    note: b.note,
    ...b.value,
  })
}

export async function addBatch(batch: NewBatch, database: MobileDb = defaultDb): Promise<number> {
  return database.outbox.add({
    ...batch,
    createdAt: Date.now(),
    status: 'pending',
    error: null,
    attempts: 0,
    syncedAt: null,
  })
}

export interface SyncResult {
  sent: number
  failed: number
  /** 为什么没传完：offline 没网，auth 登录过期 */
  stoppedBy: 'offline' | 'auth' | null
}

/**
 * 上传这个用户待上传的录入。没网时停下，留着下次再传；登录过期时停下，等重新登录；
 * 服务器不收的（例如项目已停用）标成失败，原因记下来，由用户决定重试还是删掉。
 */
export async function syncPending(userId: number, send: Sender = sendToServer,
  database: MobileDb = defaultDb): Promise<SyncResult> {
  const batches = await database.outbox.where('status').equals('pending')
    .filter((b) => b.userId === userId).sortBy('createdAt')
  const result: SyncResult = { sent: 0, failed: 0, stoppedBy: null }
  for (const b of batches) {
    try {
      await send(b)
      await database.outbox.update(b.id!, { status: 'done', error: null, attempts: b.attempts + 1, syncedAt: Date.now() })
      result.sent++
    } catch (e) {
      if (isNetworkError(e)) {
        result.stoppedBy = 'offline'
        break
      }
      if (e instanceof ApiError && e.status === 401) {
        result.stoppedBy = 'auth'
        break
      }
      await database.outbox.update(b.id!, {
        status: 'failed', error: (e as Error).message, attempts: b.attempts + 1,
      })
      result.failed++
    }
  }
  return result
}

export async function retry(id: number, database: MobileDb = defaultDb): Promise<void> {
  await database.outbox.update(id, { status: 'pending', error: null })
}

export async function discard(id: number, database: MobileDb = defaultDb): Promise<void> {
  await database.outbox.delete(id)
}

export interface OutboxCounts {
  pending: number
  failed: number
  /** 别的账号录的、还没上传的（要那个账号登录后才传） */
  others: number
}

export async function counts(userId: number, database: MobileDb = defaultDb): Promise<OutboxCounts> {
  const open = await database.outbox.where('status').anyOf('pending', 'failed').toArray()
  const mine = open.filter((b) => b.userId === userId)
  return {
    pending: mine.filter((b) => b.status === 'pending').length,
    failed: mine.filter((b) => b.status === 'failed').length,
    others: open.length - mine.length,
  }
}

/** 最近的录入（新的在前），"记录"页面用 */
export async function recent(userId: number, limit = 200, database: MobileDb = defaultDb): Promise<OutboxBatch[]> {
  const list = await database.outbox.where('userId').equals(userId).toArray()
  return list.sort((a, b) => b.createdAt - a.createdAt).slice(0, limit)
}

/** 某个试验里还没传上去的小区（平面图上标出来） */
export async function unsyncedPlots(trialId: number, database: MobileDb = defaultDb): Promise<Set<number>> {
  const list = await database.outbox.where('trialId').equals(trialId)
    .filter((b) => b.status !== 'done').toArray()
  return new Set(list.flatMap((b) => b.plots.map((p) => p.plotId)))
}

/** 删掉上传成功超过 days 天的记录，本机不无限增长 */
export async function purgeDone(days = 30, database: MobileDb = defaultDb): Promise<number> {
  const before = Date.now() - days * 86_400_000
  return database.outbox.where('status').equals('done').filter((b) => (b.syncedAt ?? 0) < before).delete()
}
