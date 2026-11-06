<script setup lang="ts">
// 在季里新建地块：照着以前某块地块的区块划分建（只要划分，不带种植和数据），或者从空白开始画。
// 从空白开始时只定每个区块多大、几行几列、区块之间留不留道路，不定地块的总长宽：
// 地块大小是区块和道路加起来，以后在"编辑地块"里加区块、加道路，地块跟着变大
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'

import { fieldApi, seasonApi, type Field, type FieldDetail, type SeasonView } from '@/shared/api'
import { UNIT_PRESETS, createLayout, fieldSize, fmt, plotCount, type CreateOptions } from '@/shared/fieldLayout'
import LayoutPreview from '@/desktop/components/LayoutPreview.vue'
import { useBreedingStore } from '@/stores/breeding'

const props = defineProps<{ season: SeasonView | null }>()
const visible = defineModel<boolean>({ required: true })
const emit = defineEmits<{ saved: [detail: FieldDetail] }>()

const store = useBreedingStore()
const name = ref('')
const mode = ref<'copy' | 'blank'>('copy')
const copyFrom = ref<number | null>(null)
const comparisonFields = ref<Field[]>([])
const plan = reactive({ blockRows: 1, blockCols: 4, blockW: 6, blockH: 20, rowRoadW: 1, colRoadW: 1, innerRows: 2, innerCols: 2 })
const saving = ref(false)

/** 能照着建的地块：以前各季的地块（最近的在前），以及原来品种比较的实验田 */
const sources = computed(() => {
  const groups = store.seasonViews
    .filter((v) => v.fields.length > 0)
    .map((v) => ({ label: v.season.name, options: v.fields.map((f) => ({ id: f.id, name: f.name, plots: f.plotCount })) }))
  if (comparisonFields.value.length) {
    groups.push({
      label: '没分季的老实验田',
      options: comparisonFields.value.map((f) => ({ id: f.id, name: f.name, plots: f.rowCount * f.colCount })),
    })
  }
  return groups
})

const options = computed<CreateOptions>(() => ({ ...plan, roadW: 0, unit: UNIT_PRESETS[0]! }))
const preview = computed(() => createLayout(options.value))
const count = computed(() => plotCount(preview.value))
const total = computed(() => {
  const s = fieldSize(preview.value)
  return `${fmt(s.w, preview.value.unit)} × ${fmt(s.h, preview.value.unit)}`
})

watch(visible, async (open) => {
  if (!open) return
  name.value = ''
  saving.value = false
  try {
    // 没分季的老实验田（直接用接口建的）也能照着建
    comparisonFields.value = await fieldApi.list('comparison')
  } catch {
    comparisonFields.value = []
  }
  // 默认照着接着的那一季里第一块地块
  const follow = props.season?.followIds ?? []
  const prev = store.seasonViews.find((v) => follow.includes(v.season.id) && v.fields.length > 0)
  copyFrom.value = prev?.fields[0]?.id ?? sources.value[0]?.options[0]?.id ?? null
  mode.value = copyFrom.value ? 'copy' : 'blank'
  if (prev?.fields[0]) name.value = prev.fields[0].name
})

async function save() {
  if (!props.season) return
  if (!name.value.trim()) {
    ElMessage.warning('请填写地块名称')
    return
  }
  if (mode.value === 'copy' && !copyFrom.value) {
    ElMessage.warning('请选择照着哪块地块建')
    return
  }
  if (mode.value === 'blank' && count.value > 3000) {
    ElMessage.warning('小区块太多了：一块地块最多 3000 个')
    return
  }
  saving.value = true
  try {
    const detail = await seasonApi.createLand(props.season.season.id, {
      name: name.value.trim(),
      copyFromFieldId: mode.value === 'copy' ? copyFrom.value : null,
      layout: mode.value === 'blank' ? preview.value : null,
    })
    ElMessage.success('地块建好了')
    visible.value = false
    await store.loadSeasons(true)
    emit('saved', detail)
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <el-dialog v-model="visible" :title="`在“${season?.season.name ?? ''}”里新建地块`" width="720px">
    <el-form label-width="96px">
      <el-form-item label="地块名称" required>
        <el-input v-model="name" maxlength="100" placeholder="例如：东地、温室" />
      </el-form-item>
      <el-form-item label="区块划分">
        <el-radio-group v-model="mode">
          <el-radio-button value="copy" :disabled="sources.length === 0">照着以前的地块</el-radio-button>
          <el-radio-button value="blank">从空白开始</el-radio-button>
        </el-radio-group>
      </el-form-item>
      <el-form-item v-if="mode === 'copy'" label="照着">
        <el-select v-model="copyFrom" filterable placeholder="选一块地块" style="width: 320px">
          <el-option-group v-for="g in sources" :key="g.label" :label="g.label">
            <el-option v-for="o in g.options" :key="o.id" :value="o.id" :label="`${o.name}（${g.label}）`">
              <span>{{ o.name }}</span>
              <span class="opt-meta">{{ o.plots }} 个小区块</span>
            </el-option>
          </el-option-group>
        </el-select>
        <div class="hint full">只照着它的区块和小区块划分，不带种植和数据。建好后可以在平面图的"编辑地块"里接着改。</div>
      </el-form-item>
      <template v-else>
        <div class="blank">
          <div class="blank-form">
            <div class="line">
              <span class="lbl">区块</span>
              <el-input-number v-model="plan.blockRows" :min="1" :max="20" controls-position="right" class="count" />
              <span class="sep">行 ×</span>
              <el-input-number v-model="plan.blockCols" :min="1" :max="20" controls-position="right" class="count" />
              <span class="sep">列</span>
            </div>
            <div class="line">
              <span class="lbl">每个区块</span>
              <el-input-number v-model="plan.blockW" :min="0.1" :max="10000" :precision="2" :controls="false" class="dim" />
              <span class="sep">宽 ×</span>
              <el-input-number v-model="plan.blockH" :min="0.1" :max="10000" :precision="2" :controls="false" class="dim" />
              <span class="sep">长（米）</span>
            </div>
            <div class="line">
              <span class="lbl">区块里</span>
              <el-input-number v-model="plan.innerRows" :min="1" :max="60" controls-position="right" class="count" />
              <span class="sep">行 ×</span>
              <el-input-number v-model="plan.innerCols" :min="1" :max="60" controls-position="right" class="count" />
              <span class="sep">列小区块</span>
            </div>
            <div class="line">
              <span class="lbl">道路</span>
              <span class="sep first">横向</span>
              <el-input-number v-model="plan.rowRoadW" :min="0" :max="1000" :precision="2" :controls="false" class="dim" />
              <span class="sep">纵向</span>
              <el-input-number v-model="plan.colRoadW" :min="0" :max="1000" :precision="2" :controls="false" class="dim" />
              <span class="sep">米宽</span>
            </div>
            <div class="hint note">
              横向道路在上下两行区块之间，纵向道路在左右两列之间，填 0 不留。
              不用先定地块总长宽：以后在“编辑地块”里用道路工具加道路、插区块，别的区块不变小，地块跟着变大。
            </div>
          </div>
          <div class="blank-preview">
            <LayoutPreview :layout="preview" :height="150" />
            <div class="hint">{{ plan.blockRows * plan.blockCols }} 个区块 · {{ count }} 个小区块</div>
            <div class="hint">地块现在 {{ total }}</div>
          </div>
        </div>
      </template>
    </el-form>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="save">建好地块</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.line {
  display: flex;
  align-items: center;
  flex-wrap: nowrap;
  margin-bottom: 12px;
  white-space: nowrap;
}

.lbl {
  width: 64px;
  flex-shrink: 0;
  color: var(--ink-2);
  font-size: var(--fs-sm);
}

.sep {
  margin: 0 6px;
  color: var(--ink-3);
  font-size: var(--fs-sm);
  white-space: nowrap;
}

.sep.first {
  margin-left: 0;
}

.note {
  margin: -2px 0 12px;
  line-height: 1.6;
}

.count {
  width: 84px;
}

.dim {
  width: 72px;
}

.full {
  width: 100%;
}

.opt-meta {
  float: right;
  color: var(--ink-3);
  font-size: var(--fs-xs);
}

.blank {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 190px;
  gap: 14px;
  padding: 14px 14px 2px;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--surface-2);
}

.blank-preview {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
</style>
