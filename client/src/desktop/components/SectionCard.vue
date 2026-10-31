<script setup lang="ts">
// 内容卡片：标题行（标题 + 小字说明 + 右侧操作）+ 内容 + 可选的底部。
// 用法：
//   <SectionCard title="最新录入" sub="最近 8 条" flush>
//     <template #actions><el-button link>查看全部</el-button></template>
//     <table>…</table>
//   </SectionCard>
// flush：内容贴边（表格、列表），不留内边距。
import type { Component } from 'vue'

withDefaults(defineProps<{
  title?: string
  sub?: string
  /** 标题前的小图标 */
  icon?: Component
  flush?: boolean
  /** 强调色：标题前的图标底色 */
  tone?: 'green' | 'wheat' | 'clay' | 'sky'
}>(), { title: undefined, sub: undefined, icon: undefined, flush: false, tone: 'green' })
</script>

<template>
  <section class="section-card">
    <header v-if="title || $slots.title || $slots.actions" class="sc-head">
      <div class="sc-title-wrap">
        <span v-if="icon" class="sc-icon" :class="tone"><el-icon><component :is="icon" /></el-icon></span>
        <h3 class="sc-title"><slot name="title">{{ title }}</slot></h3>
        <span v-if="sub || $slots.sub" class="sc-sub"><slot name="sub">{{ sub }}</slot></span>
      </div>
      <div v-if="$slots.actions" class="sc-actions"><slot name="actions" /></div>
    </header>
    <div class="sc-body" :class="{ flush }"><slot /></div>
    <footer v-if="$slots.footer" class="sc-foot"><slot name="footer" /></footer>
  </section>
</template>

<style scoped>
.section-card {
  display: flex;
  flex-direction: column;
  min-width: 0;
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  box-shadow: var(--shadow);
}

.sc-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-3);
  min-height: 24px;
  padding: 16px 20px 0;
}

.sc-title-wrap {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.sc-icon {
  width: 28px;
  height: 28px;
  border-radius: 8px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  font-size: 15px;
}

.sc-icon.green { background: var(--green-soft); color: var(--green); }
.sc-icon.wheat { background: var(--wheat-soft); color: var(--wheat); }
.sc-icon.clay { background: var(--clay-soft); color: var(--clay); }
.sc-icon.sky { background: var(--sky-soft); color: var(--sky); }

.sc-title {
  margin: 0;
  font-family: var(--font-serif);
  font-size: 16px;
  font-weight: 700;
  color: var(--forest);
  letter-spacing: 0.03em;
  white-space: nowrap;
}

.sc-sub {
  font-size: var(--fs-xs);
  color: var(--ink-3);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.sc-actions {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  flex-shrink: 0;
}

.sc-body {
  flex: 1;
  min-height: 0;
  padding: 14px 20px 18px;
}

.sc-body.flush {
  padding: 10px 0 0;
}

.sc-foot {
  padding: 10px 20px;
  border-top: 1px solid var(--line-soft);
  font-size: var(--fs-sm);
}
</style>
