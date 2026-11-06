<script setup lang="ts">
// 设对照：两种方式
//   一个（几个）小区块当对照：选中的小区块里勾出哪几个是对照，其余的和它比；
//   整个区块当对照组：选一个区块，里面的小区块都是对照，别的小区块和这一组的平均比。
// 平面图上不画连线：对照小区块描红边、标 CK，对照区块整块描边、挂"对照组"；点开一组时比较的小区块标出比对照高多少
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'

import { trialApi, type Layout, type Plot } from '@/shared/api'
import type { FieldLayout } from '@/shared/fieldLayout'

const props = withDefaults(defineProps<{
  trialId: number
  /** 选中的试验小区块 */
  plots: Plot[]
  /** 地块里所有小区块（只算试验小区块） */
  allPlots: Plot[]
  layout: FieldLayout | null
  /** 小区块上种的品种（品系）名称 */
  nameOf: (plotId: number) => string
  /** 种的是不是对照品种 */
  isCk: (plotId: number) => boolean
  /** "小区块"还是"小区块" */
  unit?: string
}>(), { unit: '小区块' })
const visible = defineModel<boolean>({ required: true })
const emit = defineEmits<{ saved: [layout: Layout] }>()

type Mode = 'block' | 'plots'
type Scope = 'selected' | 'others'

const mode = ref<Mode>('plots')
const controlBlock = ref<string | null>(null)
const controlIds = ref<number[]>([])
const scope = ref<Scope>('selected')
const label = ref('')
const labelTouched = ref(false)
const saving = ref(false)

const trialPlots = computed(() => props.allPlots.filter((p) => p.kind === 'PLOT'))
const trialIds = computed(() => new Set(trialPlots.value.map((p) => p.id)))
const code = (id: number) => {
  const p = props.allPlots.find((x) => x.id === id)
  return p ? p.code || `${p.rowNo}-${p.colNo}` : `#${id}`
}

/** 能当对照组的区块：里面有试验小区块的种植区块 */
const blocks = computed(() => (props.layout?.blocks ?? [])
  .filter((b) => b.kind === 'PLANT' && b.inner)
  .map((b) => ({ id: b.id, name: b.name, plotIds: Object.values(b.inner!.plots).filter((id) => trialIds.value.has(id)) }))
  .filter((b) => b.plotIds.length > 0))

const selectedIds = computed(() => props.plots.map((p) => p.id))

/** 选中的正好是一个区块的全部试验小区块 */
const selectedBlock = computed(() => {
  const set = new Set(selectedIds.value)
  return blocks.value.find((b) => b.plotIds.length === set.size && b.plotIds.every((id) => set.has(id))) ?? null
})

/** 存的时候：哪些是对照，哪些和它比 */
const plan = computed(() => {
  if (mode.value === 'block') {
    const b = blocks.value.find((x) => x.id === controlBlock.value)
    if (!b) return { controls: [] as number[], compares: [] as number[] }
    const inBlock = new Set(b.plotIds)
    const pool = scope.value === 'others' ? trialPlots.value.map((p) => p.id) : selectedIds.value
    return { controls: b.plotIds, compares: pool.filter((id) => !inBlock.has(id)) }
  }
  const ck = new Set(controlIds.value)
  const pool = scope.value === 'others' ? trialPlots.value.map((p) => p.id) : selectedIds.value
  return { controls: controlIds.value, compares: pool.filter((id) => !ck.has(id)) }
})

const autoLabel = computed(() => {
  if (mode.value === 'block') {
    const b = blocks.value.find((x) => x.id === controlBlock.value)
    return b ? `对照组：${b.name}` : '对照组'
  }
  const names = [...new Set(controlIds.value.map(props.nameOf).filter((n) => n && n !== '—'))]
  return names.length ? `对照：${names.slice(0, 2).join('、')}${names.length > 2 ? ' 等' : ''}` : `对照：${controlIds.value.map(code).join('、')}`
})

watch(autoLabel, (v) => {
  if (!labelTouched.value) label.value = v
})

watch(visible, (open) => {
  if (!open) return
  labelTouched.value = false
  if (selectedBlock.value && blocks.value.length > 1) {
    // 选的是整个区块：这个区块当对照组，和地块里别的都比
    mode.value = 'block'
    controlBlock.value = selectedBlock.value.id
    scope.value = 'others'
  } else {
    mode.value = 'plots'
    controlBlock.value = blocks.value[0]?.id ?? null
    const ck = selectedIds.value.filter(props.isCk)
    controlIds.value = ck.length ? ck : selectedIds.value.slice(0, 1)
    scope.value = selectedIds.value.length > controlIds.value.length ? 'selected' : 'others'
  }
  label.value = autoLabel.value
})

function toggleControl(id: number) {
  controlIds.value = controlIds.value.includes(id) ? controlIds.value.filter((x) => x !== id) : [...controlIds.value, id]
}

const problem = computed(() => {
  if (mode.value === 'block' && !controlBlock.value) return '选一个区块当对照组'
  if (plan.value.controls.length === 0) return `点一下要当对照的${props.unit}`
  if (plan.value.compares.length === 0) {
    return scope.value === 'selected' ? `没有要比较的${props.unit}：多选几个，或者选"地块里其他所有${props.unit}"` : `没有别的${props.unit}可以比较`
  }
  return null
})

async function save() {
  if (problem.value) {
    ElMessage.warning(problem.value)
    return
  }
  saving.value = true
  try {
    const all = [...plan.value.controls, ...plan.value.compares]
    const layout = await trialApi.createLink(props.trialId, all, plan.value.controls, label.value.trim() || autoLabel.value)
    ElMessage.success('已设对照')
    visible.value = false
    emit('saved', layout)
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <el-dialog v-model="visible" title="设对照" width="600px" class="ck-dialog">
    <div class="modes">
      <button type="button" class="mode" :class="{ on: mode === 'plots' }" @click="mode = 'plots'">
        <i class="pic plots"><b /><b class="ck" /><b /><b /></i>
        <span>
          <strong>{{ unit }}当对照</strong>
          <small>一个或几个{{ unit }}是对照，别的和它比</small>
        </span>
      </button>
      <button
        type="button" class="mode" :class="{ on: mode === 'block' }" :disabled="blocks.length < 2"
        :title="blocks.length < 2 ? '这块地块只有一个区块，没有别的区块可以和它比' : ''" @click="mode = 'block'"
      >
        <i class="pic block"><b class="ck" /><b /><b /><b /></i>
        <span>
          <strong>整个区块当对照组</strong>
          <small>区块里都是对照，别的和这一组的平均比</small>
        </span>
      </button>
    </div>

    <template v-if="mode === 'plots'">
      <div class="step">点一下设成对照（红色），再点一下取消</div>
      <div class="chips">
        <button
          v-for="p in plots" :key="p.id" type="button" class="chip" :class="{ ck: controlIds.includes(p.id) }"
          @click="toggleControl(p.id)"
        >
          <span class="num">{{ p.code || `${p.rowNo}-${p.colNo}` }}</span>
          <span class="nm">{{ nameOf(p.id) }}</span>
          <em v-if="controlIds.includes(p.id)">CK</em>
        </button>
      </div>
    </template>

    <template v-else>
      <div class="step">哪个区块当对照组</div>
      <div class="blocks">
        <button
          v-for="b in blocks" :key="b.id" type="button" class="blk" :class="{ ck: controlBlock === b.id }"
          @click="controlBlock = b.id"
        >
          <strong>{{ b.name }}</strong>
          <small>{{ b.plotIds.length }} 个{{ unit }}</small>
        </button>
      </div>
    </template>

    <div class="step">和谁比</div>
    <el-radio-group v-model="scope" class="scope">
      <el-radio value="selected" :disabled="mode === 'block' && plots.every((p) => blocks.find((b) => b.id === controlBlock)?.plotIds.includes(p.id))">
        选中的其余{{ unit }}
      </el-radio>
      <el-radio value="others">地块里其他所有{{ unit }}</el-radio>
    </el-radio-group>

    <div class="summary" :class="{ bad: !!problem }">
      <template v-if="problem">{{ problem }}</template>
      <template v-else>
        对照 <b>{{ plan.controls.length }}</b> 个{{ unit }}{{ mode === 'block' ? `（${blocks.find((b) => b.id === controlBlock)?.name} 全部）` : '' }}，
        和 <b>{{ plan.compares.length }}</b> 个{{ unit }}比较{{ plan.controls.length > 1 ? '，按对照的平均值算比对照高多少' : '' }}。
      </template>
    </div>

    <el-form label-width="52px" class="name-form">
      <el-form-item label="名称">
        <el-input v-model="label" maxlength="50" @input="labelTouched = true" />
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="saving" :disabled="!!problem" @click="save">设好对照</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.modes {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
  margin-bottom: 16px;
}

.mode {
  display: flex;
  gap: 12px;
  align-items: center;
  padding: 12px;
  border: 1.5px solid var(--line-strong);
  border-radius: var(--radius);
  background: var(--surface);
  text-align: left;
  font: inherit;
  cursor: pointer;
}

.mode:hover:not(:disabled) {
  border-color: var(--green-2);
}

.mode.on {
  border-color: var(--green);
  background: var(--green-wash);
}

.mode:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.mode strong {
  display: block;
  color: var(--forest);
  font-size: var(--fs-md);
}

.mode small {
  color: var(--ink-3);
  font-size: var(--fs-xs);
}

/* 小示意图：2 × 2 的格子，红的是对照 */
.pic {
  flex-shrink: 0;
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 3px;
  width: 38px;
  height: 38px;
  padding: 3px;
  border-radius: 6px;
  background: #e7dcc2;
}

.pic b {
  border-radius: 2px;
  background: #fbf7ee;
}

.pic.plots b.ck {
  background: #fbe3d8;
  box-shadow: inset 0 0 0 2px #c0562f;
}

.pic.block {
  grid-template-columns: 1fr 1fr;
}

.pic.block b.ck {
  grid-row: span 2;
  background: #fbe3d8;
  box-shadow: inset 0 0 0 2px #c0562f;
}

.step {
  margin: 4px 0 8px;
  font-size: var(--fs-sm);
  font-weight: 600;
  color: var(--ink-2);
}

.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  max-height: 180px;
  overflow: auto;
  margin-bottom: 14px;
}

.chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 5px 9px;
  border: 1px solid var(--line-strong);
  border-radius: 999px;
  background: var(--surface);
  font: inherit;
  font-size: var(--fs-sm);
  cursor: pointer;
}

.chip .nm {
  color: var(--ink-3);
}

.chip.ck {
  border-color: #c0562f;
  background: #fbe9e1;
}

.chip.ck .nm {
  color: #8f3a1c;
}

.chip em {
  font-style: normal;
  font-size: 11px;
  font-weight: 700;
  color: #fff;
  background: #c0562f;
  border-radius: 4px;
  padding: 0 4px;
}

.blocks {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(110px, 1fr));
  gap: 6px;
  max-height: 180px;
  overflow: auto;
  margin-bottom: 14px;
}

.blk {
  padding: 8px 10px;
  border: 1px solid var(--line-strong);
  border-radius: var(--radius-sm);
  background: var(--surface);
  text-align: left;
  font: inherit;
  cursor: pointer;
}

.blk strong {
  display: block;
  font-size: var(--fs-sm);
}

.blk small {
  color: var(--ink-3);
  font-size: var(--fs-xs);
}

.blk.ck {
  border: 2px solid #c0562f;
  background: #fbe9e1;
}

.scope {
  margin-bottom: 12px;
}

.summary {
  padding: 9px 12px;
  border-radius: var(--radius-sm);
  background: var(--surface-2);
  font-size: var(--fs-sm);
  color: var(--ink-2);
  margin-bottom: 12px;
}

.summary.bad {
  background: #fbefe7;
  color: #8f3a1c;
}

.name-form :deep(.el-form-item) {
  margin-bottom: 0;
}
</style>
