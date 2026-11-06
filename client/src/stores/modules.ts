// 功能模块的开关（系统管理员在"功能模块"里设）：菜单、路由、页面上的入口按它显示
import { computed, ref } from 'vue'
import { defineStore } from 'pinia'

import { moduleApi, type AppModule } from '@/shared/api'

/** 内置模块的编码，和服务器 V9__app_module.sql 一致 */
export type ModuleCode =
  | 'field_map' | 'breeding' | 'traits' | 'data' | 'stats' | 'analysis' | 'record_book' | 'import' | 'screen'
  | 'devices' | 'quality' | 'corrections' | 'users' | 'modules'

export const useModuleStore = defineStore('modules', () => {
  const list = ref<AppModule[]>([])
  const loaded = ref(false)
  let loading: Promise<void> | null = null

  const byCode = computed(() => new Map(list.value.map((m) => [m.code, m])))
  /** 给当前用户看、开着的链接模块 */
  const links = computed(() => list.value.filter((m) => m.kind === 'LINK' && m.on))

  /** 模块开没开；还没取到或没登记的模块当作开着（服务器会再挡一次） */
  function isOn(code: ModuleCode | string | undefined): boolean {
    if (!code) return true
    return byCode.value.get(code)?.on ?? true
  }

  function nameOf(code: string): string {
    return byCode.value.get(code)?.name ?? code
  }

  async function load(force = false): Promise<void> {
    if (loaded.value && !force) return
    if (!loading || force) {
      loading = moduleApi.list()
        .then((l) => {
          list.value = l
          loaded.value = true
        })
        .catch(() => {
          // 取不到时按全开处理，接口自己会拒绝停用的功能
          loaded.value = true
        })
        .finally(() => {
          loading = null
        })
    }
    await loading
  }

  function reset() {
    list.value = []
    loaded.value = false
  }

  return { list, links, loaded, isOn, nameOf, load, reset }
})
