<script setup lang="ts">
// 图表类型的小图标（24×24 线条图），自助分析里选图表类型用
import type { ChartType } from '@/shared/analysis'

defineProps<{ type: ChartType; size?: number }>()
</script>

<template>
  <svg :width="size ?? 22" :height="size ?? 22" viewBox="0 0 24 24" fill="none" stroke="currentColor"
    stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
    <template v-if="type === 'bar'">
      <path d="M4 20h16" /><rect x="5.5" y="11" width="3" height="9" rx="0.8" /><rect x="10.5" y="6" width="3" height="14" rx="0.8" /><rect x="15.5" y="13" width="3" height="7" rx="0.8" />
    </template>
    <template v-else-if="type === 'hbar'">
      <path d="M4 4v16" /><rect x="4" y="5.5" width="11" height="3" rx="0.8" /><rect x="4" y="10.5" width="15" height="3" rx="0.8" /><rect x="4" y="15.5" width="8" height="3" rx="0.8" />
    </template>
    <template v-else-if="type === 'stack'">
      <path d="M4 20h16" /><rect x="5.5" y="9" width="4" height="11" rx="0.8" /><path d="M5.5 14h4" /><rect x="14.5" y="5" width="4" height="15" rx="0.8" /><path d="M14.5 11h4" />
    </template>
    <template v-else-if="type === 'line'">
      <path d="M4 20h16" /><path d="M4.5 15.5l4.5-5 4 3 6-7" /><circle cx="9" cy="10.5" r="1.2" /><circle cx="13" cy="13.5" r="1.2" />
    </template>
    <template v-else-if="type === 'area'">
      <path d="M4 20h16" /><path d="M4.5 16l4.5-5 4 3 6-7V20H4.5z" fill="currentColor" fill-opacity="0.18" />
    </template>
    <template v-else-if="type === 'pie'">
      <circle cx="12" cy="12" r="8" /><path d="M12 4v8h8" fill="currentColor" fill-opacity="0.2" />
    </template>
    <template v-else-if="type === 'donut'">
      <circle cx="12" cy="12" r="8" /><circle cx="12" cy="12" r="3.6" /><path d="M12 4v4.4M15.6 12H20" />
    </template>
    <template v-else-if="type === 'scatter'">
      <path d="M4 4v16h16" /><circle cx="8.5" cy="14.5" r="1.3" /><circle cx="11.5" cy="10.5" r="1.3" /><circle cx="15" cy="12" r="1.3" /><circle cx="17.5" cy="7" r="1.3" /><circle cx="13" cy="16" r="1.3" />
    </template>
    <template v-else-if="type === 'radar'">
      <path d="M12 3.5l8 5.8-3 9.2H7l-3-9.2z" /><path d="M12 8l4 3.6-1.6 4.4H9.6L8 11.6z" fill="currentColor" fill-opacity="0.2" />
    </template>
    <template v-else-if="type === 'heatmap'">
      <rect x="4" y="4" width="4.5" height="4.5" rx="0.8" fill="currentColor" fill-opacity="0.15" /><rect x="9.75" y="4" width="4.5" height="4.5" rx="0.8" fill="currentColor" fill-opacity="0.5" /><rect x="15.5" y="4" width="4.5" height="4.5" rx="0.8" fill="currentColor" fill-opacity="0.85" />
      <rect x="4" y="9.75" width="4.5" height="4.5" rx="0.8" fill="currentColor" fill-opacity="0.6" /><rect x="9.75" y="9.75" width="4.5" height="4.5" rx="0.8" fill="currentColor" fill-opacity="0.25" /><rect x="15.5" y="9.75" width="4.5" height="4.5" rx="0.8" fill="currentColor" fill-opacity="0.4" />
      <rect x="4" y="15.5" width="4.5" height="4.5" rx="0.8" fill="currentColor" fill-opacity="0.9" /><rect x="9.75" y="15.5" width="4.5" height="4.5" rx="0.8" fill="currentColor" fill-opacity="0.7" /><rect x="15.5" y="15.5" width="4.5" height="4.5" rx="0.8" fill="currentColor" fill-opacity="0.1" />
    </template>
    <template v-else>
      <rect x="4" y="5" width="16" height="14" rx="1.5" /><path d="M4 9.5h16M4 14h16M10 5v14" />
    </template>
  </svg>
</template>
