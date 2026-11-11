<script setup lang="ts">
// 合并几个区块：选好的区块合并成一个，占它们的外框（夹在中间的道路一起选上就并进来）。可以保留原来的小区块（能拼起来时），
// 也可以重新分成几行几列——原来的小区块按位置留下，落不进新格子的去掉
import { computed, reactive, watch } from 'vue'

import {
  BLOCK_KIND, MAX_INNER_TRACKS, boundsOf, mergeBlocks, plotSize, plotSpots, regridInner, removedPlots, renameBlock, setBlockKind,
  type BlockKind, type FieldLayout,
} from '@/shared/fieldLayout'

const props = defineProps<{
  layout: FieldLayout
  blockIds: string[]
  plotCode: (id: number) => string
}>()
const visible = defineModel<boolean>({ required: true })
const emit = defineEmits<{ apply: [layout: FieldLayout, anchorId: string] }>()

const KINDS: BlockKind[] = ['PLANT', 'ROAD', 'GUARD', 'BLANK']

/** 选中的区块合并的结果（还没改类型、行列） */
const base = computed(() => {
  const inside = props.blockIds.map((id) => props.layout.blocks.find((b) => b.id === id)).filter((b) => !!b)
  if (inside.length < 2) return null
  const res = mergeBlocks(props.layout, props.blockIds)
  const anchor = res.anchorId ? res.layout.blocks.find((b) => b.id === res.anchorId) ?? null : null
  // 合并前各个区块里的小区块在哪：重新分小区块时按位置放进新格子
  const spots = plotSpots(props.layout, inside.map((b) => b.id))
  return { res, anchor, inside, count: inside.length, spots, box: boundsOf(inside)! }
})

const form = reactive({ name: '', kind: 'PLANT' as BlockKind, mode: 'keep' as 'keep' | 'regrid', rows: 2, cols: 3 })

const median = (a: number[]) => [...a].sort((x, y) => x - y)[Math.floor(a.length / 2)]!

watch(visible, (open) => {
  const b = base.value
  if (!open || !b?.anchor) return
  form.name = b.anchor.name
  form.kind = b.anchor.kind
  form.mode = b.res.joined && b.anchor.inner ? 'keep' : 'regrid'
  // 重新分时默认按原来小区块的大小分：合并后多宽多长，除以原来每个小区块的宽和长，原来的小区块差不多都有地方放
  const d = props.layout.defaultInner ?? { rows: 2, cols: 3 }
  const sizes = b.inside.filter((x) => x.kind === 'PLANT').map((x) => plotSize(props.layout, x)).filter((v) => !!v)
  const fit = (total: number, one: number) => Math.min(MAX_INNER_TRACKS, Math.max(1, Math.round(total / one)))
  form.rows = sizes.length ? fit(b.box.h, median(sizes.map((v) => v.h))) : d.rows
  form.cols = sizes.length ? fit(b.box.w, median(sizes.map((v) => v.w))) : d.cols
  if (b.res.joined && b.anchor.inner) {
    form.rows = b.anchor.inner.rows.length
    form.cols = b.anchor.inner.cols.length
  }
})

/** 按表单算出来的最终布局 */
const result = computed(() => {
  const b = base.value
  if (!b?.anchor) return null
  const id = b.anchor.id
  let l = b.res.layout
  if (form.kind !== b.anchor.kind) l = setBlockKind(l, [id], form.kind)
  if (form.kind === 'PLANT' && (form.mode === 'regrid' || !b.res.joined)) {
    l = regridInner(l, id, form.rows, form.cols, b.spots).layout
  }
  if (form.name.trim() && form.name.trim() !== b.anchor.name) l = renameBlock(l, id, form.name)
  return { layout: l, id, dropped: removedPlots(props.layout, l) }
})

/** 预览：新区块里的格子，留下原来小区块的标出来 */
const preview = computed(() => {
  const r = result.value
  const blk = r?.layout.blocks.find((b) => b.id === r.id)
  if (!blk?.inner) return null
  const rows = blk.inner.rows.length
  const cols = blk.inner.cols.length
  const cells: { key: string; kept: boolean }[] = []
  for (let i = 0; i < rows; i++) {
    for (let j = 0; j < cols; j++) cells.push({ key: `${i},${j}`, kept: blk.inner.plots[`${i},${j}`] != null })
  }
  return { rows, cols, cells, merged: blk.inner.merges.length > 0 }
})

function apply() {
  const r = result.value
  if (!r) return
  emit('apply', r.layout, r.id)
  visible.value = false
}
</script>

<template>
  <el-dialog v-model="visible" title="合并成一个区块" width="560px" append-to-body>
    <div v-if="base && base.res.problem" class="warn">合并不了：{{ base.res.problem }}。</div>
    <template v-if="base && base.anchor && result">
      <div class="summary">
        把选中的 <b>{{ base.count }}</b> 个合并成一个区块，占它们围成的整片地方（夹在中间的道路、空当一起并进来）。
      </div>

      <el-form label-width="76px" class="form">
        <el-form-item label="名称">
          <el-input v-model="form.name" maxlength="30" />
        </el-form-item>
        <el-form-item label="类型">
          <div class="kinds">
            <button v-for="k in KINDS" :key="k" type="button" class="kind" :class="[k.toLowerCase(), { on: form.kind === k }]" @click="form.kind = k">
              <i />{{ BLOCK_KIND[k] }}
            </button>
          </div>
        </el-form-item>
        <template v-if="form.kind === 'PLANT'">
          <el-form-item label="小区块">
            <el-radio-group v-model="form.mode" class="modes">
              <el-radio value="keep" :disabled="!base.res.joined || !base.anchor.inner">
                保留原来的小区块，拼起来
                <span v-if="base.res.joined && base.anchor.inner" class="muted">
                  （{{ base.anchor.inner.rows.length }} 行 × {{ base.anchor.inner.cols.length }} 列）
                </span>
                <span v-else class="muted">（没有排整齐，或者行列对不上，拼不起来）</span>
              </el-radio>
              <el-radio value="regrid">重新分</el-radio>
            </el-radio-group>
          </el-form-item>
          <el-form-item v-if="form.mode === 'regrid' || !base.res.joined" label="">
            <div class="grid-input">
              <el-input-number v-model="form.rows" :min="1" :max="MAX_INNER_TRACKS" controls-position="right" />
              <span>行 ×</span>
              <el-input-number v-model="form.cols" :min="1" :max="MAX_INNER_TRACKS" controls-position="right" />
              <span>列</span>
            </div>
          </el-form-item>
        </template>
      </el-form>

      <div v-if="preview" class="preview">
        <div class="grid" :style="{ gridTemplateColumns: `repeat(${preview.cols}, 1fr)`, gridTemplateRows: `repeat(${preview.rows}, 1fr)` }">
          <span v-for="c in preview.cells" :key="c.key" :class="{ kept: c.kept }" />
        </div>
        <div class="legend">
          <span><i class="kept" />原来的小区块（编号、品种、数据都留着）</span>
          <span><i />新小区块（保存后编号）</span>
        </div>
      </div>

      <div v-if="result.dropped.length" class="warn">
        会去掉 {{ result.dropped.length }} 个小区块（{{ result.dropped.slice(0, 8).map(plotCode).join('、') }}{{ result.dropped.length > 8 ? ' 等' : '' }}）。
        已经分配了品种、录了数据的小区块保存时不能去掉，会提示你先清掉。
      </div>
    </template>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :disabled="!result" @click="apply">合并</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.summary {
  margin-bottom: 14px;
  padding: 10px 12px;
  border-radius: 10px;
  background: var(--surface-2);
  font-size: 13.5px;
  line-height: 1.7;
}

.kinds {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 6px;
  width: 100%;
}

.kind {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 8px;
  border-radius: 8px;
  border: 1px solid var(--line-strong);
  background: var(--surface);
  font: inherit;
  font-size: 13px;
  cursor: pointer;
}

.kind i {
  width: 14px;
  height: 14px;
  border-radius: 3px;
  border: 1px solid rgba(90, 70, 30, 0.25);
  background: #e7dcc2;
}

.kind.road i {
  background: #d9cfb9;
}

.kind.guard i {
  background: repeating-linear-gradient(45deg, #cfe0bf 0 3px, #b9d0a6 3px 6px);
}

.kind.blank i {
  background: #eee6d4;
}

.kind.on {
  border-color: var(--forest);
  background: var(--green-wash);
  font-weight: 600;
}

.modes {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
}

.grid-input {
  display: flex;
  align-items: center;
  gap: 8px;
}

.grid-input :deep(.el-input-number) {
  width: 110px;
}

.preview {
  display: flex;
  align-items: center;
  gap: 18px;
  margin: 4px 0 10px 76px;
}

.grid {
  display: grid;
  gap: 2px;
  width: 140px;
  height: 120px;
  padding: 4px;
  border-radius: 8px;
  border: 1.5px solid var(--forest);
  background: #e7dcc2;
}

.grid span {
  border-radius: 2px;
  background: #fbf7ee;
  border: 1px dashed rgba(46, 107, 79, 0.45);
}

.grid span.kept {
  background: #cfe2c2;
  border: 1px solid rgba(28, 66, 49, 0.35);
}

.legend {
  display: flex;
  flex-direction: column;
  gap: 6px;
  font-size: 12.5px;
  color: var(--ink-2);
}

.legend i {
  display: inline-block;
  width: 12px;
  height: 12px;
  margin-right: 6px;
  vertical-align: -1px;
  border-radius: 2px;
  background: #fbf7ee;
  border: 1px dashed rgba(46, 107, 79, 0.45);
}

.legend i.kept {
  background: #cfe2c2;
  border: 1px solid rgba(28, 66, 49, 0.35);
}

.warn {
  padding: 8px 12px;
  border-radius: 10px;
  background: #f8e6df;
  color: #7d3520;
  font-size: 13px;
  line-height: 1.6;
}
</style>
