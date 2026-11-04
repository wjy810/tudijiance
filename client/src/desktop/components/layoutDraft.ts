// 编辑地块时的草稿：改动先记在草稿里，可以撤销、重做，点"保存布局"才提交到服务器
import { computed, ref, shallowRef, type Ref } from 'vue'

import { plotIds, removedPlots, type FieldLayout } from '@/shared/fieldLayout'

const clone = <T>(v: T): T => JSON.parse(JSON.stringify(v)) as T
const MAX_HISTORY = 100

export function useLayoutDraft(saved: Ref<FieldLayout | null>) {
  const draft = shallowRef<FieldLayout | null>(null)
  const past = ref<FieldLayout[]>([])
  const future = ref<FieldLayout[]>([])

  /** 从保存的布局开始一份新草稿 */
  function begin() {
    draft.value = saved.value ? clone(saved.value) : null
    past.value = []
    future.value = []
  }

  function end() {
    draft.value = null
    past.value = []
    future.value = []
  }

  /** 换成新的布局（操作函数返回原对象表示没变，不记历史） */
  function apply(next: FieldLayout) {
    const cur = draft.value
    if (!cur || next === cur) return
    past.value = [...past.value.slice(-MAX_HISTORY + 1), cur]
    future.value = []
    draft.value = next
  }

  function undo() {
    const prev = past.value[past.value.length - 1]
    if (!prev || !draft.value) return
    future.value = [draft.value, ...future.value]
    past.value = past.value.slice(0, -1)
    draft.value = prev
  }

  function redo() {
    const next = future.value[0]
    if (!next || !draft.value) return
    past.value = [...past.value, draft.value]
    future.value = future.value.slice(1)
    draft.value = next
  }

  const dirty = computed(() => !!draft.value && !!saved.value && JSON.stringify(draft.value) !== JSON.stringify(saved.value))
  const removed = computed(() => (draft.value && saved.value ? removedPlots(saved.value, draft.value) : []))
  /** 保存后要新建的小区个数 */
  const added = computed(() => {
    const d = draft.value
    if (!d) return 0
    let n = 0
    for (const b of d.blocks) {
      if (!b.inner) continue
      const ids = new Set(Object.keys(b.inner.plots))
      const cells = countCells(b.inner.rows.length, b.inner.cols.length, b.inner.merges)
      n += cells - ids.size
    }
    return n
  })
  const keptCount = computed(() => (draft.value ? plotIds(draft.value).size : 0))

  return {
    draft, begin, end, apply, undo, redo, dirty, removed, added, keptCount,
    canUndo: computed(() => past.value.length > 0),
    canRedo: computed(() => future.value.length > 0),
  }
}

function countCells(rows: number, cols: number, merges: { rs: number; cs: number }[]): number {
  return rows * cols - merges.reduce((n, m) => n + m.rs * m.cs - 1, 0)
}
