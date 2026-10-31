<script setup lang="ts">
// 快速跳转（Ctrl + K）：输入几个字找页面、实验田，回车直接打开。
// 条目由外面传进来（AppLayout 按菜单和实验田列表生成），这里只管搜索和键盘操作。
import { computed, nextTick, ref, watch, type Component } from 'vue'
import { Search } from '@element-plus/icons-vue'

export interface PaletteItem {
  id: string
  /** 分组名：页面、实验田…… */
  group: string
  label: string
  desc?: string
  icon?: Component
  /** 额外的搜索词 */
  keywords?: string
  run: () => void
}

const props = defineProps<{ items: PaletteItem[] }>()
const open = defineModel<boolean>({ required: true })

const query = ref('')
const active = ref(0)
const inputRef = ref<HTMLInputElement>()
const listRef = ref<HTMLElement>()

const results = computed(() => {
  const q = query.value.trim().toLowerCase()
  if (!q) return props.items
  return props.items.filter((it) =>
    [it.label, it.desc, it.group, it.keywords].some((s) => s?.toLowerCase().includes(q)))
})

/** 按分组排好，记下每条在 results 里的序号，方便键盘上下选 */
const grouped = computed(() => {
  const map = new Map<string, { item: PaletteItem; index: number }[]>()
  results.value.forEach((item, index) => map.set(item.group, [...(map.get(item.group) ?? []), { item, index }]))
  return [...map.entries()]
})

watch(query, () => { active.value = 0 })

watch(open, async (v) => {
  if (v) {
    query.value = ''
    active.value = 0
    await nextTick()
    inputRef.value?.focus()
  }
})

function move(step: number) {
  const n = results.value.length
  if (n === 0) return
  active.value = (active.value + step + n) % n
  nextTick(() => listRef.value?.querySelector('.pi.on')?.scrollIntoView({ block: 'nearest' }))
}

function choose(i = active.value) {
  const item = results.value[i]
  if (!item) return
  open.value = false
  item.run()
}
</script>

<template>
  <el-dialog
    v-model="open"
    class="palette"
    width="560px"
    top="12vh"
    :show-close="false"
    append-to-body
    destroy-on-close
  >
    <div class="pal-input">
      <el-icon class="pal-search"><Search /></el-icon>
      <input
        ref="inputRef"
        v-model="query"
        placeholder="搜索页面或实验田，例如：统计、东区"
        @keydown.down.prevent="move(1)"
        @keydown.up.prevent="move(-1)"
        @keydown.enter.prevent="choose()"
      />
      <kbd>Esc</kbd>
    </div>
    <div ref="listRef" class="pal-list">
      <div v-if="results.length === 0" class="pal-empty">没有找到"{{ query }}"，换个词试试</div>
      <div v-for="[group, rows] in grouped" :key="group" class="pal-group">
        <div class="pal-group-title">{{ group }}</div>
        <button
          v-for="r in rows"
          :key="r.item.id"
          type="button"
          class="pi"
          :class="{ on: r.index === active }"
          @mouseenter="active = r.index"
          @click="choose(r.index)"
        >
          <span class="pi-icon"><el-icon v-if="r.item.icon"><component :is="r.item.icon" /></el-icon></span>
          <span class="pi-label">{{ r.item.label }}</span>
          <span v-if="r.item.desc" class="pi-desc">{{ r.item.desc }}</span>
          <span class="pi-enter">打开 ↵</span>
        </button>
      </div>
    </div>
    <div class="pal-foot">
      <span><kbd>↑</kbd><kbd>↓</kbd> 选择</span>
      <span><kbd>Enter</kbd> 打开</span>
      <span class="pal-foot-right">随时按 <kbd>Ctrl</kbd> + <kbd>K</kbd> 打开这里</span>
    </div>
  </el-dialog>
</template>

<style>
/* el-dialog 挂在 body 下，样式不能 scoped */
.el-dialog.palette {
  padding: 0;
  overflow: hidden;
}

.el-dialog.palette .el-dialog__header {
  display: none;
}

.el-dialog.palette .el-dialog__body {
  padding: 0;
}
</style>

<style scoped>
.pal-input {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 16px;
  height: 54px;
  border-bottom: 1px solid var(--line);
}

.pal-search {
  font-size: 18px;
  color: var(--ink-3);
}

.pal-input input {
  flex: 1;
  min-width: 0;
  border: none;
  outline: none;
  background: none;
  font: inherit;
  font-size: var(--fs-md);
  color: var(--ink);
}

.pal-input input::placeholder {
  color: var(--ink-4);
}

.pal-list {
  max-height: min(56vh, 420px);
  overflow: auto;
  padding: 6px 8px 8px;
}

.pal-group-title {
  padding: 10px 10px 4px;
  font-size: 11px;
  letter-spacing: 0.12em;
  color: var(--ink-3);
}

.pi {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  height: 40px;
  padding: 0 10px;
  border: none;
  border-radius: 9px;
  background: none;
  font: inherit;
  color: var(--ink);
  text-align: left;
  cursor: pointer;
}

.pi.on {
  background: var(--green-wash);
  box-shadow: inset 0 0 0 1px #d5e4d9;
}

.pi-icon {
  width: 26px;
  height: 26px;
  border-radius: 7px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  background: var(--surface-2);
  color: var(--green);
  flex-shrink: 0;
}

.pi.on .pi-icon {
  background: var(--surface);
}

.pi-label {
  font-weight: 500;
  white-space: nowrap;
}

.pi-desc {
  flex: 1;
  min-width: 0;
  font-size: var(--fs-xs);
  color: var(--ink-3);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.pi-enter {
  margin-left: auto;
  font-size: var(--fs-xs);
  color: var(--green);
  opacity: 0;
}

.pi.on .pi-enter {
  opacity: 1;
}

.pal-empty {
  padding: 28px 12px;
  text-align: center;
  color: var(--ink-3);
}

.pal-foot {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 9px 16px;
  border-top: 1px solid var(--line-soft);
  background: var(--surface-2);
  font-size: var(--fs-xs);
  color: var(--ink-3);
}

.pal-foot kbd {
  margin: 0 2px;
}

.pal-foot-right {
  margin-left: auto;
}
</style>
