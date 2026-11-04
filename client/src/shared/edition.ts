/** 独立离线包使用单独的数据和会话，不读取联机版的账号或缓存。 */
export const OFFLINE_EDITION = import.meta.env.MODE === 'offline'

export function mobileStorageKey(key: string): string {
  return OFFLINE_EDITION ? `tudijiance.offline.${key}` : `tudijiance.mobile.${key}`
}
