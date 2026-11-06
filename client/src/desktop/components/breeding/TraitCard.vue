<script setup lang="ts">
// 品系详情里一个项目的卡片：
//   数值  大字写这一季的平均，下面一条横轴画出每个位置的点、平均线、父母（对照）的平均，再列各季的值和比上一季
//   选项  每种有几个位置，一条一条的横条
//   日期  平均日期、最早最晚
// 点"按区块看"展开，列出这一季每个区块里各位置的值
import { computed } from 'vue'

import { TRAIT_CATEGORY } from '@/shared/labels'
import { axisOf, byGroup, type TraitCard } from '@/shared/lineCards'

const props = defineProps<{ card: TraitCard; seasonId: number | null; expanded: boolean }>()
const emit = defineEmits<{ pickSeason: [id: number]; toggle: [] }>()

const c = computed(() => props.card)
const axis = computed(() => (c.value.kind === 'number' ? axisOf(c.value) : null))
const x = (v: number) => (axis.value ? ((v - axis.value.min) / (axis.value.max - axis.value.min)) * 100 : 50)

const fmt = (v: number) => String(Math.round(v * 100) / 100)

/** 大字：数值写平均（不带"平均"两个字），其它写服务器给的 */
const big = computed(() => {
  const r = c.value.current
  if (!r) return null
  if (c.value.kind === 'number' && r.average != null) return fmt(r.average)
  if (c.value.kind === 'date') return r.display.slice(5)
  return null
})

const dates = computed(() => {
  if (c.value.kind !== 'date' || !c.value.current) return null
  const ds = c.value.current.plots.map((p) => p.display).filter((d) => /^\d{4}-\d{2}-\d{2}$/.test(d)).sort()
  return ds.length ? { first: ds[0]!, last: ds[ds.length - 1]! } : null
})

const maxCount = computed(() => Math.max(1, ...c.value.counts.map((x) => x.count)))
const groups = computed(() => (c.value.current ? byGroup(c.value.current) : []))

function seasonText(row: TraitCard['seasons'][number]['row']): string {
  if (!row) return '—'
  if (c.value.kind === 'number' && row.average != null) return fmt(row.average)
  if (c.value.kind === 'date') return row.display.slice(5)
  const counts = new Map<string, number>()
  row.plots.forEach((p) => counts.set(p.display, (counts.get(p.display) ?? 0) + 1))
  const top = [...counts.entries()].sort((a, b) => b[1] - a[1])[0]
  return top ? top[0] : row.display
}

const pctText = (v: number) => `${v > 0 ? '+' : ''}${v}%`
</script>

<template>
  <article class="card" :class="[c.kind, { imp: c.important }]">
    <header class="top">
      <div class="title">
        <span v-if="c.important" class="star">★</span>{{ c.name }}
        <span v-if="c.unit" class="unit">{{ c.unit }}</span>
      </div>
      <span v-if="c.category" class="cat">{{ TRAIT_CATEGORY[c.category] }}</span>
    </header>

    <!-- 这一季 -->
    <div v-if="c.current" class="now">
      <template v-if="big != null">
        <span class="big num">{{ big }}</span>
        <span v-if="c.kind === 'number' && c.current.dataType === 'GRADE'" class="big-unit">级</span>
      </template>
      <span v-else-if="c.kind === 'choice' && c.counts.length === 1" class="big-text">{{ c.counts[0]!.label }}</span>
      <span class="sub">
        {{ c.current.count }} 个位置
        <template v-if="c.kind === 'number' && c.range && c.range.max > c.range.min"> · {{ fmt(c.range.min) }}–{{ fmt(c.range.max) }}</template>
        <template v-if="dates && dates.first !== dates.last"> · {{ dates.first.slice(5) }} 到 {{ dates.last.slice(5) }}</template>
      </span>
      <span v-if="c.change" class="change" :class="c.change.pct >= 0 ? 'up' : 'down'" :title="`和 ${c.change.from} 比`">
        {{ c.change.pct >= 0 ? '↑' : '↓' }} {{ Math.abs(c.change.pct) }}%
      </span>
    </div>
    <div v-else class="now none">{{ c.seasons.length ? '这一季没录' : '还没种过，下面是选出时量的这一株' }}</div>

    <!-- 数值：横轴上每个位置一个点，竖线是平均，三角是对比的品系、菱形是选出时的单株 -->
    <div v-if="c.kind === 'number' && axis && c.current" class="strip" :title="`${fmt(axis.min)} 到 ${fmt(axis.max)}`">
      <i class="track" />
      <i
        v-for="p in c.current.plots.filter((q) => q.value != null)" :key="p.plotId" class="dot"
        :style="{ left: `${x(p.value!)}%` }" :title="`${p.place}：${p.display}`"
      />
      <i v-if="c.current.average != null" class="mean" :style="{ left: `${x(c.current.average)}%` }" :title="`平均 ${fmt(c.current.average)}`" />
      <i
        v-for="cmp in c.compares.filter((q) => q.row.average != null)" :key="cmp.lineId" class="tri"
        :style="{ left: `${x(cmp.row.average!)}%` }" :title="`${cmp.role} ${cmp.name}：${fmt(cmp.row.average!)}`"
      />
      <i
        v-if="c.plant?.valueNum != null" class="dia" :style="{ left: `${x(c.plant.valueNum)}%` }"
        :title="`选出时的单株：${c.plant.display}`"
      />
    </div>

    <!-- 选项：每种几个 -->
    <div v-if="c.kind === 'choice' && c.counts.length > 1" class="counts">
      <div v-for="k in c.counts.slice(0, 4)" :key="k.label" class="count">
        <span class="count-label">{{ k.label }}</span>
        <span class="count-bar"><i :style="{ width: `${(k.count / maxCount) * 100}%` }" /></span>
        <span class="count-n num">{{ k.count }} 个</span>
      </div>
      <div v-if="c.counts.length > 4" class="more">还有 {{ c.counts.length - 4 }} 种</div>
    </div>

    <!-- 和谁比 -->
    <div v-if="c.compares.length || c.plant" class="cmps">
      <div v-if="c.plant" class="cmp">
        <span class="who"><i class="dia-icon" />选出时单株</span>
        <span class="v num">{{ c.plant.display }}</span>
      </div>
      <div v-for="cmp in c.compares" :key="cmp.lineId" class="cmp" :title="cmp.sameSeason ? '同一季' : `它没和这个品系同一季种，用的是 ${cmp.seasonName}`">
        <span class="who"><i class="tri-icon" />{{ cmp.role }} {{ cmp.name }}<em v-if="!cmp.sameSeason">（{{ cmp.seasonName.replace(/20(\d\d)/g, '$1') }}）</em></span>
        <span class="v num">{{ c.kind === 'number' && cmp.row.average != null ? fmt(cmp.row.average) : cmp.row.display }}</span>
        <span v-if="cmp.pct != null" class="pct num" :class="cmp.pct >= 0 ? 'up' : 'down'">{{ pctText(cmp.pct) }}</span>
      </div>
    </div>

    <!-- 各季 -->
    <div v-if="c.seasons.length > 1" class="seasons">
      <button
        v-for="s in c.seasons" :key="s.seasonId" type="button" class="season" :class="{ on: s.seasonId === seasonId, empty: !s.row }"
        :title="s.seasonName" @click="emit('pickSeason', s.seasonId)"
      >
        <span class="s-name">{{ s.short }}<template v-if="s.generation"> F{{ s.generation }}</template></span>
        <span class="s-val num">{{ seasonText(s.row) }}</span>
      </button>
    </div>

    <button v-if="c.current && c.current.count > 1" type="button" class="toggle" @click="emit('toggle')">
      {{ expanded ? '收起' : '按区块看各位置' }}
    </button>
    <div v-if="expanded && c.current" class="groups">
      <div v-for="g in groups" :key="g.group" class="group">
        <div class="g-name">{{ g.group }}</div>
        <div class="g-items">
          <span v-for="it in g.items" :key="it.code" class="g-item"><em>{{ it.code }}</em>{{ it.display }}</span>
        </div>
      </div>
    </div>
  </article>
</template>

<style scoped>
.card {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 14px 16px 12px;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: var(--surface);
  box-shadow: 0 1px 0 rgba(255, 255, 255, 0.7) inset;
}

.card.imp {
  border-top: 3px solid var(--wheat);
  padding-top: 12px;
}

.top {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  gap: 8px;
}

.title {
  font-weight: 700;
  color: var(--ink);
  font-size: var(--fs-md);
}

.star {
  color: var(--wheat);
  font-size: 12px;
  margin-right: 3px;
}

.unit {
  margin-left: 4px;
  font-size: var(--fs-xs);
  font-weight: 400;
  color: var(--ink-3);
}

.cat {
  font-size: 11px;
  color: var(--ink-4);
}

.now {
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: 6px 10px;
}

.now.none {
  font-size: var(--fs-sm);
  color: var(--ink-4);
}

.big {
  font-family: var(--font-serif);
  font-size: 30px;
  line-height: 1;
  font-weight: 700;
  color: var(--forest);
}

.big-unit {
  font-size: var(--fs-sm);
  color: var(--ink-3);
  margin-left: -6px;
}

.big-text {
  font-size: 20px;
  font-weight: 700;
  color: var(--forest);
}

.sub {
  font-size: 12px;
  color: var(--ink-3);
}

.change {
  margin-left: auto;
  padding: 1px 7px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 600;
}

.up {
  color: #2f7a48;
}

.down {
  color: #b0462a;
}

.change.up {
  background: #e6f2e9;
}

.change.down {
  background: #f9e6df;
}

/* ---------- 横轴 ---------- */

.strip {
  position: relative;
  height: 22px;
  margin: 0 6px;
}

.strip i {
  position: absolute;
}

.track {
  left: 0;
  right: 0;
  top: 10px;
  height: 2px;
  border-radius: 1px;
  background: var(--line-strong);
}

.dot {
  top: 6px;
  width: 10px;
  height: 10px;
  margin-left: -5px;
  border-radius: 50%;
  background: rgba(46, 107, 79, 0.55);
  border: 1.5px solid #fff;
}

.mean {
  top: 1px;
  width: 3px;
  height: 20px;
  margin-left: -1.5px;
  border-radius: 2px;
  background: var(--forest);
}

.tri {
  top: 0;
  width: 0;
  height: 0;
  margin-left: -6px;
  border-left: 6px solid transparent;
  border-right: 6px solid transparent;
  border-top: 9px solid var(--sky);
}

.dia {
  top: 5px;
  width: 9px;
  height: 9px;
  margin-left: -4.5px;
  transform: rotate(45deg);
  background: var(--wheat);
  border: 1px solid #fff;
}

/* ---------- 选项计数 ---------- */

.counts {
  display: flex;
  flex-direction: column;
  gap: 5px;
}

.count {
  display: grid;
  grid-template-columns: minmax(0, 88px) minmax(0, 1fr) 40px;
  gap: 8px;
  align-items: center;
  font-size: 12.5px;
}

.count-label {
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
  color: var(--ink-2);
}

.count-bar {
  height: 8px;
  border-radius: 4px;
  background: var(--surface-3);
  overflow: hidden;
}

.count-bar i {
  display: block;
  height: 100%;
  border-radius: 4px;
  background: var(--green-2, #4f8a64);
}

.count-n {
  text-align: right;
  color: var(--ink-3);
}

.more {
  font-size: 11.5px;
  color: var(--ink-4);
}

/* ---------- 对比 ---------- */

.cmps {
  display: flex;
  flex-direction: column;
  gap: 3px;
  padding-top: 8px;
  border-top: 1px dashed var(--line);
}

.cmp {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto 52px;
  gap: 8px;
  align-items: center;
  font-size: 12.5px;
}

.who {
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
  color: var(--ink-2);
}

.who em {
  font-style: normal;
  color: var(--ink-4);
  font-size: 11px;
}

.v {
  color: var(--ink);
}

.pct {
  text-align: right;
  font-weight: 600;
}

.tri-icon {
  display: inline-block;
  margin-right: 5px;
  border-left: 5px solid transparent;
  border-right: 5px solid transparent;
  border-top: 7px solid var(--sky);
}

.dia-icon {
  display: inline-block;
  width: 7px;
  height: 7px;
  margin-right: 6px;
  transform: rotate(45deg);
  background: var(--wheat);
}

/* ---------- 各季 ---------- */

.seasons {
  display: flex;
  gap: 4px;
  overflow-x: auto;
  padding-bottom: 2px;
}

.season {
  flex: 1 0 auto;
  min-width: 56px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 1px;
  padding: 4px 6px;
  border: 1px solid var(--line);
  border-radius: 7px;
  background: var(--surface-2);
  font: inherit;
  cursor: pointer;
}

.season:hover {
  border-color: var(--green-2);
}

.season.on {
  border-color: var(--green);
  background: var(--green-wash);
}

.season.empty .s-val {
  color: var(--ink-4);
}

.s-name {
  font-size: 10.5px;
  color: var(--ink-3);
  white-space: nowrap;
}

.s-val {
  font-size: 12.5px;
  font-weight: 600;
  color: var(--ink);
  white-space: nowrap;
  max-width: 90px;
  overflow: hidden;
  text-overflow: ellipsis;
}

.toggle {
  align-self: flex-start;
  padding: 0;
  border: none;
  background: none;
  font: inherit;
  font-size: 12px;
  color: var(--green);
  cursor: pointer;
}

.toggle:hover {
  text-decoration: underline;
}

.groups {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 8px 10px;
  border-radius: 8px;
  background: var(--surface-2);
}

.g-name {
  font-size: 11.5px;
  font-weight: 700;
  color: var(--ink-3);
}

.g-items {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 10px;
  font-size: 12.5px;
}

.g-item em {
  font-style: normal;
  color: var(--ink-4);
  margin-right: 4px;
}
</style>
