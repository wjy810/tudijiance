// 手机端的本机数据库（IndexedDB，用 Dexie 操作）。
// cache：从服务器取来的数据（实验田、试验、布局、小区状态、录入项目、品种……），没网时用它；
// outbox：录入的数据先存在这里，联网后上传。上传成功的保留一段时间，在"记录"里能看到
import Dexie, { type Table } from 'dexie'

import type { TraitValue } from '@/shared/api'
import { OFFLINE_EDITION } from '@/shared/edition'

export interface CacheRow {
  key: string
  value: unknown
  /** 存进来的时间（毫秒） */
  savedAt: number
}

export type OutboxStatus = 'pending' | 'failed' | 'done' | 'local'

/** 一次录入：同一个项目、同一个值、同一天，给一个或多个小区 */
export interface OutboxBatch {
  id?: number
  /** 录入人：只上传当前登录用户自己的，换了账号不会用别人的身份上传 */
  userId: number
  fieldId: number
  trialId: number
  traitId: number
  traitName: string
  /** 每个小区一个客户端编号，服务器按它去重，重复上传不会产生重复数据 */
  plots: { plotId: number; code: string; clientUuid: string }[]
  value: TraitValue
  /** 显示用的值，例如"78 cm""3 级" */
  displayValue: string
  observedOn: string
  note: string | null
  createdAt: number
  status: OutboxStatus
  /** 服务器不收时的原因 */
  error: string | null
  attempts: number
  syncedAt: number | null
}

export class MobileDb extends Dexie {
  cache!: Table<CacheRow, string>
  outbox!: Table<OutboxBatch, number>

  constructor(name = 'tudijiance') {
    super(name)
    this.version(1).stores({
      cache: 'key',
      outbox: '++id, status, userId, trialId, createdAt',
    })
  }
}

export const db = new MobileDb(OFFLINE_EDITION ? 'tudijiance-offline' : 'tudijiance')
