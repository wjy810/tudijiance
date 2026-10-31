<script setup lang="ts">
// 生育期进度条：播种 → 出苗 → 越冬 → 返青 → 拔节 → 抽穗 → 扬花 → 成熟。
// 走过的时期是彩色的，当前时期加金色光圈，还没到的时期变灰。每个时期一棵小麦插画，从左到右越长越高。
import { computed } from 'vue'

import { MAIN_STAGES, stageView } from '@/shared/growthStage'
import sprite from '@/assets/art/growth-stages.webp'

const props = withDefaults(defineProps<{
  /** 后端给的当前生育期（物候期项目名），例如"出苗期"；没有记录传 null */
  current: string | null
  /** 插画高度（像素） */
  size?: number
}>(), { size: 52 })

const view = computed(() => stageView(props.current))
const lastIndex = MAIN_STAGES.length - 1

/** 已走过的那段实线占整条线的比例 */
const doneRatio = computed(() => (view.value.index <= 0 ? 0 : view.value.index / lastIndex))

function state(i: number): 'done' | 'current' | 'todo' {
  if (i === view.value.index) return 'current'
  return i < view.value.index ? 'done' : 'todo'
}
</script>

<template>
  <div class="track" :style="{ '--icon': size + 'px', '--cols': MAIN_STAGES.length }">
    <ol class="stages" aria-label="生育期进度">
      <li
        v-for="(s, i) in MAIN_STAGES"
        :key="s.key"
        class="stage"
        :class="state(i)"
        :aria-current="state(i) === 'current' ? 'step' : undefined"
        :title="`${s.label}期 · 一般在 ${s.months}`"
      >
        <span
          class="art"
          :style="{ backgroundImage: `url(${sprite})`, backgroundPosition: `${(i / lastIndex) * 100}% 100%` }"
          aria-hidden="true"
        />
        <span class="node" aria-hidden="true" />
        <span class="label">{{ s.label }}</span>
      </li>
    </ol>
    <!-- 横线：从第一个点连到最后一个点，走过的部分是实线 -->
    <div class="rail" aria-hidden="true">
      <i class="rail-done" :style="{ width: doneRatio * 100 + '%' }" />
    </div>
  </div>
</template>

<style scoped>
.track {
  position: relative;
  --node-y: calc(var(--icon) + 14px);
}

.stages {
  position: relative;
  z-index: 1;
  display: grid;
  grid-template-columns: repeat(var(--cols), minmax(0, 1fr));
  margin: 0;
  padding: 0;
  list-style: none;
}

.stage {
  display: flex;
  flex-direction: column;
  align-items: center;
  min-width: 0;
}

.art {
  position: relative;
  width: var(--icon);
  height: var(--icon);
  background-repeat: no-repeat;
  background-size: calc(var(--cols) * 100%) 100%;
  transition: filter var(--dur) var(--ease), opacity var(--dur) var(--ease), transform var(--dur) var(--ease);
}

.stage.todo .art {
  filter: grayscale(1) sepia(0.3);
  opacity: 0.32;
}

.stage.done .art {
  opacity: 0.88;
}

.stage.current .art {
  transform: scale(1.12);
  transform-origin: 50% 100%;
}

/* 当前时期身后一圈暖光 */
.stage.current .art::before {
  content: '';
  position: absolute;
  inset: -10% -30% -6%;
  z-index: -1;
  border-radius: 50%;
  background: radial-gradient(closest-side, rgba(226, 182, 90, 0.32), rgba(226, 182, 90, 0));
}

.node {
  width: 9px;
  height: 9px;
  margin-top: 9px;
  border-radius: 50%;
  box-sizing: border-box;
  background: var(--surface);
  border: 2px solid var(--line-strong);
}

.stage.done .node {
  background: var(--green);
  border-color: var(--green);
}

.stage.current .node {
  width: 13px;
  height: 13px;
  margin-top: 7px;
  background: var(--wheat-bright);
  border: 2px solid var(--surface);
  box-shadow: 0 0 0 2px var(--wheat), 0 0 0 7px rgba(226, 182, 90, 0.22);
}

.label {
  margin-top: 7px;
  font-size: var(--fs-sm);
  color: var(--ink-3);
  white-space: nowrap;
}

.stage.done .label {
  color: var(--ink-2);
}

.stage.current .label {
  color: var(--forest);
  font-weight: 700;
}

/* 横线：从第一列中心到最后一列中心 */
.rail {
  position: absolute;
  top: calc(var(--icon) + 9px + 4px - 1px);
  left: calc(100% / var(--cols) / 2);
  right: calc(100% / var(--cols) / 2);
  height: 2px;
  background: repeating-linear-gradient(90deg, var(--line-strong) 0 5px, transparent 5px 9px);
}

.rail-done {
  position: absolute;
  inset: 0 auto 0 0;
  background: var(--green);
  border-radius: 1px;
  transition: width var(--dur-slow) var(--ease);
}
</style>
