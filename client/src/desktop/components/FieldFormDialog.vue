<script setup lang="ts">
// 新建或修改实验田。新建时设地块布局：几行几列区块、每个区块多宽多高（单位可以自己定）、
// 区块之间留不留道路、每个区块里几行几列小区。建好后在平面图的"编辑地块"里还能合并、改大小、设特殊区块
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'

import { fieldApi, type Field, type FieldDetail, type FieldRequest } from '@/shared/api'
import {
  UNIT_PRESETS, createLayout, fieldSize, plotCount, round, type CreateOptions, type Unit,
} from '@/shared/fieldLayout'
import LayoutPreview from '@/desktop/components/LayoutPreview.vue'

const props = defineProps<{
  field?: Field | null
  /** 这块地已经按区块布局管理：不再改行列 */
  layoutSaved?: boolean
}>()
const visible = defineModel<boolean>({ required: true })
const emit = defineEmits<{ saved: [detail: FieldDetail] }>()

function emptyForm(): FieldRequest {
  return {
    name: '', location: null, latitude: null, longitude: null, altitudeM: null, areaMu: null,
    rowCount: 6, colCount: 10, soilType: null, irrigated: true, previousCrop: null, remark: null,
    plotLengthM: 6.67, plotWidthM: 2,
  }
}

const form = reactive<FieldRequest>(emptyForm())
const saving = ref(false)

// ---------- 新建：地块布局 ----------

const CUSTOM = '__custom'
const unitName = ref('米')
const custom = reactive({ name: '', meters: 1 })
const plan = reactive({
  blockRows: 1, blockCols: 3, blockW: 6, blockH: 26.68, roadW: 1, innerRows: 4, innerCols: 3,
})

const unit = computed<Unit>(() => {
  if (unitName.value === CUSTOM) {
    const n = custom.name.trim() || '单位'
    return { name: n, symbol: n, meters: custom.meters > 0 ? custom.meters : 1 }
  }
  return UNIT_PRESETS.find((u) => u.name === unitName.value) ?? UNIT_PRESETS[0]!
})

/** 换单位时把数字换算过去，地块实际大小不变 */
let lastMeters = 1
watch(unit, (u) => {
  const k = lastMeters / u.meters
  if (Math.abs(k - 1) > 1e-9) {
    plan.blockW = round(plan.blockW * k, 3)
    plan.blockH = round(plan.blockH * k, 3)
    plan.roadW = round(plan.roadW * k, 3)
  }
  lastMeters = u.meters
})

const options = computed<CreateOptions>(() => ({ ...plan, unit: unit.value }))
const preview = computed(() => createLayout(options.value))
const summary = computed(() => {
  const l = preview.value
  const s = fieldSize(l)
  const m = unit.value.meters
  const plotW = plan.blockW / plan.innerCols
  const plotH = plan.blockH / plan.innerRows
  return {
    blocks: plan.blockRows * plan.blockCols,
    plots: plotCount(l),
    w: round(s.w, 2), h: round(s.h, 2),
    wm: round(s.w * m, 2), hm: round(s.h * m, 2),
    mu: round((s.w * m * s.h * m) / 666.67, 2),
    plotW: round(plotW, 3), plotH: round(plotH, 3),
    plotArea: round(plotW * m * plotH * m, 2),
  }
})

const tooMany = computed(() => summary.value.plots > 3000)

/** 常用做法：一键填好 */
const PRESETS = [
  { label: '区试 3 个重复', desc: '1 行 3 个区块，每块 4 行 × 3 列小区，区块之间 1 m 道路', apply: () =>
    Object.assign(plan, { blockRows: 1, blockCols: 3, blockW: 6, blockH: 26.68, roadW: 1, innerRows: 4, innerCols: 3 }) },
  { label: '品比 4 个重复', desc: '2 行 2 列区块，每块 3 行 × 4 列小区', apply: () =>
    Object.assign(plan, { blockRows: 2, blockCols: 2, blockW: 8, blockH: 20.01, roadW: 1, innerRows: 3, innerCols: 4 }) },
  { label: '整块地一个区块', desc: '6 行 × 10 列小区，不留道路', apply: () =>
    Object.assign(plan, { blockRows: 1, blockCols: 1, blockW: 20, blockH: 40.02, roadW: 0, innerRows: 6, innerCols: 10 }) },
]

function applyPreset(p: (typeof PRESETS)[number]) {
  unitName.value = '米'
  lastMeters = 1
  p.apply()
}

watch(visible, (open) => {
  if (!open) return
  Object.assign(form, props.field ? { ...props.field, plotLengthM: null, plotWidthM: null } : emptyForm())
  if (!props.field) {
    unitName.value = '米'
    lastMeters = 1
    PRESETS[0]!.apply()
  }
})

const isCreate = computed(() => !props.field)
const showLegacyGrid = computed(() => !!props.field && !props.layoutSaved)

async function save() {
  if (!form.name.trim()) {
    ElMessage.warning('请填写实验田名称')
    return
  }
  if (isCreate.value && tooMany.value) {
    ElMessage.warning('小区太多了：一块地最多 3000 个小区')
    return
  }
  saving.value = true
  try {
    let detail: FieldDetail
    if (props.field) {
      detail = await fieldApi.update(props.field.id, form)
    } else {
      const layout = preview.value
      detail = await fieldApi.create({
        ...form,
        rowCount: Math.max(1, plan.blockRows * plan.innerRows),
        colCount: Math.max(1, plan.blockCols * plan.innerCols),
        areaMu: form.areaMu ?? summary.value.mu,
        plotLengthM: null, plotWidthM: null,
        layout,
      })
    }
    ElMessage.success('已保存')
    visible.value = false
    emit('saved', detail)
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <el-dialog v-model="visible" :title="field ? '修改实验田' : '新建实验田'" :width="isCreate ? '760px' : '560px'" top="6vh">
    <el-form label-width="96px">
      <el-form-item label="名称" required>
        <el-input v-model="form.name" maxlength="100" placeholder="例如：东区品种试验田" />
      </el-form-item>

      <!-- 新建：地块布局 -->
      <section v-if="isCreate" class="plan">
        <div class="plan-head">
          <div>
            <div class="plan-title">地块布局</div>
            <div class="hint">先定好有几行几列区块、每块多大、里面分几行几列小区。建好后还能合并区块、改大小、设特殊区块。</div>
          </div>
          <div class="presets">
            <el-tooltip v-for="p in PRESETS" :key="p.label" :content="p.desc" placement="top">
              <el-button size="small" @click="applyPreset(p)">{{ p.label }}</el-button>
            </el-tooltip>
          </div>
        </div>

        <div class="plan-body">
          <div class="plan-form">
            <el-form-item label="长度单位">
              <el-select v-model="unitName" style="width: 132px">
                <el-option v-for="u in UNIT_PRESETS" :key="u.name" :value="u.name" :label="`${u.name}（${u.symbol}）`" />
                <el-option :value="CUSTOM" label="自定义…" />
              </el-select>
              <template v-if="unitName === CUSTOM">
                <el-input v-model="custom.name" maxlength="10" placeholder="名称，如：垄" class="unit-name" />
                <span class="sep">1 {{ custom.name || '单位' }} =</span>
                <el-input-number v-model="custom.meters" :min="0.001" :max="1000" :precision="3" :controls="false" class="dim" />
                <span class="sep">米</span>
              </template>
            </el-form-item>
            <el-form-item label="区块">
              <el-input-number v-model="plan.blockRows" :min="1" :max="20" controls-position="right" class="count" />
              <span class="sep">行 ×</span>
              <el-input-number v-model="plan.blockCols" :min="1" :max="20" controls-position="right" class="count" />
              <span class="sep">列</span>
            </el-form-item>
            <el-form-item label="每个区块">
              <span class="sep first">宽</span>
              <el-input-number v-model="plan.blockW" :min="0.01" :max="100000" :precision="2" :controls="false" class="dim" />
              <span class="sep">× 高</span>
              <el-input-number v-model="plan.blockH" :min="0.01" :max="100000" :precision="2" :controls="false" class="dim" />
              <span class="sep">{{ unit.symbol }}</span>
            </el-form-item>
            <el-form-item label="区块间道路">
              <el-input-number v-model="plan.roadW" :min="0" :max="10000" :precision="2" :controls="false" class="dim" />
              <span class="sep">{{ unit.symbol }} 宽</span>
              <span class="hint">填 0 不留道路</span>
            </el-form-item>
            <el-form-item label="区块里的小区">
              <el-input-number v-model="plan.innerRows" :min="1" :max="60" controls-position="right" class="count" />
              <span class="sep">行 ×</span>
              <el-input-number v-model="plan.innerCols" :min="1" :max="60" controls-position="right" class="count" />
              <span class="sep">列</span>
            </el-form-item>
          </div>
          <div class="plan-preview">
            <LayoutPreview :layout="preview" :height="190" />
            <div class="facts">
              <div><b class="num">{{ summary.blocks }}</b> 个区块 · <b class="num">{{ summary.plots }}</b> 个小区</div>
              <div class="muted">
                整块地 {{ summary.w }} × {{ summary.h }} {{ unit.symbol }}
                <template v-if="unit.meters !== 1">（{{ summary.wm }} × {{ summary.hm }} m）</template>
                ，约 {{ summary.mu }} 亩
              </div>
              <div class="muted">
                每个小区 {{ summary.plotW }} × {{ summary.plotH }} {{ unit.symbol }}，{{ summary.plotArea }} m²
              </div>
              <div v-if="tooMany" class="danger">小区太多了：一块地最多 3000 个</div>
            </div>
          </div>
        </div>
      </section>

      <!-- 老的按行列建的地：还能改行列 -->
      <template v-if="showLegacyGrid">
        <el-form-item label="网格" required>
          <el-input-number v-model="form.rowCount" :min="1" :max="100" />
          <span class="sep">行 ×</span>
          <el-input-number v-model="form.colCount" :min="1" :max="100" />
          <span class="sep">列</span>
        </el-form-item>
        <div class="hint grid-hint">
          缩小网格会删掉网格外的小区；已经分配了品种的小区不能删。也可以在平面图的“编辑地块”里改成区块布局。
        </div>
        <el-form-item label="新小区尺寸">
          <span class="sep first">长</span>
          <el-input-number v-model="form.plotLengthM" :min="0.1" :max="200" :precision="2" :controls="false" class="dim" />
          <span class="sep">m × 宽</span>
          <el-input-number v-model="form.plotWidthM" :min="0.1" :max="200" :precision="2" :controls="false" class="dim" />
          <span class="sep">m</span>
        </el-form-item>
      </template>
      <div v-else-if="field" class="hint layout-note">
        这块地按区块布局管理：区块、小区的行列和大小在平面图的“编辑地块”里改。
      </div>

      <el-form-item label="地点">
        <el-input v-model="form.location" maxlength="200" />
      </el-form-item>
      <el-form-item label="经纬度">
        <el-input-number v-model="form.latitude" :precision="6" :controls="false" placeholder="纬度" />
        <span class="sep" />
        <el-input-number v-model="form.longitude" :precision="6" :controls="false" placeholder="经度" />
      </el-form-item>
      <el-form-item label="海拔（m）">
        <el-input-number v-model="form.altitudeM" :precision="1" :controls="false" />
      </el-form-item>
      <el-form-item label="面积（亩）">
        <el-input-number v-model="form.areaMu" :precision="2" :min="0" :controls="false" :placeholder="isCreate ? `按布局约 ${summary.mu}` : ''" />
      </el-form-item>
      <el-form-item label="土质">
        <el-input v-model="form.soilType" maxlength="50" placeholder="例如：壤土" />
      </el-form-item>
      <el-form-item label="水地/旱地">
        <el-radio-group v-model="form.irrigated">
          <el-radio :value="true">水地</el-radio>
          <el-radio :value="false">旱地</el-radio>
        </el-radio-group>
      </el-form-item>
      <el-form-item label="前茬作物">
        <el-input v-model="form.previousCrop" maxlength="50" placeholder="例如：玉米" />
      </el-form-item>
      <el-form-item label="备注">
        <el-input v-model="form.remark" type="textarea" maxlength="500" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="saving" :disabled="isCreate && tooMany" @click="save">
        {{ isCreate ? `建好 ${summary.plots} 个小区` : '保存' }}
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.sep {
  margin: 0 8px;
  color: var(--app-text-secondary);
}

.sep.first {
  margin-left: 0;
}

.dim {
  width: 96px;
}

.count {
  width: 92px;
}

.unit-name {
  width: 110px;
  margin-left: 8px;
}

.grid-hint,
.layout-note {
  margin: -10px 0 12px 96px;
}

.layout-note {
  margin-top: 0;
  padding: 8px 12px;
  border-radius: var(--radius-sm);
  background: var(--sky-wash);
  color: var(--ink-2);
}

.plan {
  margin: 4px 0 18px;
  padding: 14px 16px 4px;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--surface-2);
}

.plan-head {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}

.plan-title {
  font-family: var(--font-serif);
  font-weight: 700;
  color: var(--forest);
  font-size: var(--fs-md);
  margin-bottom: 2px;
}

.presets {
  display: flex;
  gap: 6px;
  flex-shrink: 0;
  align-items: flex-start;
}

.presets .el-button + .el-button {
  margin-left: 0;
}

.plan-body {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 230px;
  gap: 16px;
}

.plan-form :deep(.el-form-item) {
  margin-bottom: 14px;
}

.plan-preview {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.facts {
  font-size: var(--fs-xs);
  line-height: 1.7;
}

.facts b {
  font-family: var(--font-serif);
  color: var(--forest);
  font-size: var(--fs-md);
}

.danger {
  color: var(--clay);
}
</style>
