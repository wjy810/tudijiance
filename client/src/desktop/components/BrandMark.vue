<script setup lang="ts">
// 品牌标志：一支麦穗。纯 SVG，跟着字号缩放
defineProps<{ size?: number; color?: string }>()
</script>

<template>
  <svg :width="size ?? 28" :height="size ?? 28" viewBox="0 0 32 32" fill="none" aria-hidden="true">
    <path d="M16 30V11" :stroke="color ?? 'currentColor'" stroke-width="2" stroke-linecap="round" />
    <g :fill="color ?? 'currentColor'">
      <ellipse cx="16" cy="6" rx="2.6" ry="4.2" />
      <ellipse cx="12.2" cy="11.5" rx="2.4" ry="3.8" transform="rotate(-35 12.2 11.5)" />
      <ellipse cx="19.8" cy="11.5" rx="2.4" ry="3.8" transform="rotate(35 19.8 11.5)" />
      <ellipse cx="12.2" cy="17.5" rx="2.4" ry="3.8" transform="rotate(-35 12.2 17.5)" />
      <ellipse cx="19.8" cy="17.5" rx="2.4" ry="3.8" transform="rotate(35 19.8 17.5)" />
      <ellipse cx="12.4" cy="23.3" rx="2.2" ry="3.4" transform="rotate(-35 12.4 23.3)" />
      <ellipse cx="19.6" cy="23.3" rx="2.2" ry="3.4" transform="rotate(35 19.6 23.3)" />
    </g>
  </svg>
</template>
