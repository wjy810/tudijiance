<script setup lang="ts">
// 一个指标：小字名称 + 宋体大数字 + 单位，可选"/ 总数"进度条、一句提示、图标、点击跳转。
// 用法：
//   <StatCard label="今天已录" :value="7" :total="36" unit="个小区" hint="还有 29 个没录" />
//   <StatCard label="异常小区" :value="2" unit="个" tone="clay" :icon="Warning" to="/fields/1" />
// bare：放在别的卡片里时不要边框和阴影（几个指标并排，用竖线隔开）。
import { computed, type Component } from 'vue'
import { useRouter, type RouteLocationRaw } from 'vue-router'

const props = withDefaults(defineProps<{
  label: string
  value: number | string | null
  unit?: string
  /** 有总数时显示"值 / 总数"和一条细进度条 */
  total?: number | null
  hint?: string
  icon?: Component
  tone?: 'green' | 'wheat' | 'clay' | 'sky' | 'ink'
  to?: RouteLocationRaw
  bare?: boolean
}>(), { unit: undefined, total: null, hint: undefined, icon: undefined, tone: 'ink', to: undefined, bare: false })

const router = useRouter()

const percent = computed(() => {
  if (props.total == null || props.total <= 0 || typeof props.value !== 'number') return null
  return Math.min(100, Math.round((props.value / props.total) * 100))
})

function go() {
  if (props.to) router.push(props.to)
}
</script>

<template>
  <component
    :is="to ? 'button' : 'div'"
    :type="to ? 'button' : undefined"
    class="stat"
    :class="[`tone-${tone}`, { bare, link: !!to }]"
    @click="go"
  >
    <div class="stat-top">
      <span v-if="icon" class="stat-icon"><el-icon><component :is="icon" /></el-icon></span>
      <span class="stat-label">{{ label }}</span>
    </div>
    <div class="stat-value num">
      {{ value ?? '—' }}<span v-if="total != null" class="stat-total"> / {{ total }}</span><span v-if="unit" class="stat-unit">{{ unit }}</span>
    </div>
    <div v-if="percent != null" class="meter stat-meter" :class="{ wheat: tone === 'wheat' }"><i :style="{ width: percent + '%' }" /></div>
    <div v-if="hint || $slots.hint" class="stat-hint"><slot name="hint">{{ hint }}</slot></div>
  </component>
</template>

<style scoped>
.stat {
  display: flex;
  flex-direction: column;
  min-width: 0;
  padding: 14px 16px;
  text-align: left;
  font: inherit;
  color: inherit;
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  box-shadow: var(--shadow);
}

.stat.bare {
  padding: 2px 0;
  background: none;
  border: none;
  border-radius: 0;
  box-shadow: none;
}

.stat.link {
  cursor: pointer;
  transition: border-color var(--dur-fast), box-shadow var(--dur-fast), transform var(--dur-fast);
}

.stat.link:not(.bare):hover {
  border-color: #d9c48f;
  box-shadow: var(--shadow-lift);
  transform: translateY(-1px);
}

.stat-top {
  display: flex;
  align-items: center;
  gap: 6px;
}

.stat-icon {
  display: inline-flex;
  font-size: 14px;
  color: var(--ink-3);
}

.stat-label {
  font-size: var(--fs-xs);
  color: var(--ink-2);
  letter-spacing: 0.02em;
}

.stat-value {
  margin-top: 4px;
  font-family: var(--font-serif);
  font-size: 26px;
  font-weight: 700;
  line-height: 1.15;
  color: var(--forest);
  white-space: nowrap;
}

.stat-total {
  font-size: 15px;
  color: var(--ink-3);
}

.stat-unit {
  margin-left: 4px;
  font-family: var(--font-sans);
  font-size: var(--fs-xs);
  font-weight: normal;
  color: var(--ink-3);
}

.stat-meter {
  margin-top: 8px;
  max-width: 160px;
}

.stat-hint {
  margin-top: 6px;
  font-size: var(--fs-xs);
  color: var(--ink-3);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.tone-green .stat-icon { color: var(--green); }
.tone-wheat .stat-icon { color: var(--wheat); }
.tone-sky .stat-icon { color: var(--sky); }
.tone-clay .stat-icon,
.tone-clay .stat-value { color: var(--clay); }
</style>
