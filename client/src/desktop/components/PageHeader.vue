<script setup lang="ts">
// 页面标题区：小字眉题（可选）+ 宋体大标题 + 一句说明，右边放这一页的操作按钮。
// 用法：
//   <PageHeader title="数据查看" desc="按条件找录入的数据……">
//     <template #actions><el-button>导出</el-button></template>
//   </PageHeader>
// 说明里要放链接、数字等，用 #desc 插槽。
withDefaults(defineProps<{
  title: string
  desc?: string
  /** 标题上方的小字，例如"日常"、日期 */
  eyebrow?: string
  /** 不要标题下的金色短线（首页这种自己有视觉重点的页面用） */
  plain?: boolean
}>(), { desc: undefined, eyebrow: undefined, plain: false })
</script>

<template>
  <header class="page-header" :class="{ plain }">
    <div class="page-header-text">
      <div v-if="eyebrow || $slots.eyebrow" class="page-eyebrow"><slot name="eyebrow">{{ eyebrow }}</slot></div>
      <h2>{{ title }}</h2>
      <div v-if="desc || $slots.desc" class="page-desc"><slot name="desc">{{ desc }}</slot></div>
    </div>
    <div v-if="$slots.actions" class="page-actions"><slot name="actions" /></div>
  </header>
</template>

<style scoped>
.page-header-text {
  min-width: 0;
}
</style>
