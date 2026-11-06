<script setup lang="ts">
// 新建或修改季：名字、起止日期、接着哪一季（或几季）。接着的季种过的品系，种植时排在下拉框最前面
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'

import { seasonApi, type SeasonRequest, type SeasonView } from '@/shared/api'
import { useBreedingStore } from '@/stores/breeding'

const props = defineProps<{ season?: SeasonView | null }>()
const visible = defineModel<boolean>({ required: true })
const emit = defineEmits<{ saved: [view: SeasonView] }>()

const store = useBreedingStore()
const form = reactive<SeasonRequest>({ name: '', startDate: '', endDate: null, remark: null, followIds: [] })
const fresh = ref(false)
const saving = ref(false)

/** 能接着的季：比这一季开始得早的（新建时是全部） */
const candidates = computed(() => store.seasons.filter((s) =>
  s.id !== props.season?.season.id && (!form.startDate || s.startDate <= form.startDate)).slice().reverse())

function guessName(start: string, end: string | null): string {
  if (!start) return ''
  const y1 = start.slice(0, 4)
  const y2 = end?.slice(0, 4)
  return y2 && y2 !== y1 ? `${y1}-${y2} 年度大田` : `${y1} 季`
}

watch(visible, (open) => {
  if (!open) return
  const s = props.season
  if (s) {
    Object.assign(form, {
      name: s.season.name, startDate: s.season.startDate, endDate: s.season.endDate,
      remark: s.season.remark, followIds: [...s.followIds],
    })
    fresh.value = s.followIds.length === 0
  } else {
    // 默认接着时间上最近的一季
    const last = store.seasons[store.seasons.length - 1]
    const y = new Date().getFullYear()
    Object.assign(form, {
      name: '', startDate: `${y}-10-01`, endDate: `${y + 1}-06-10`, remark: null,
      followIds: last ? [last.id] : [],
    })
    form.name = guessName(form.startDate, form.endDate)
    fresh.value = !last
  }
})

const nameTouched = ref(false)
watch(() => [form.startDate, form.endDate], () => {
  if (!props.season && !nameTouched.value) form.name = guessName(form.startDate, form.endDate)
})

async function save() {
  if (!form.name.trim() || !form.startDate) {
    ElMessage.warning('请填写名称和开始日期')
    return
  }
  if (!fresh.value && form.followIds.length === 0) {
    ElMessage.warning('请选择接着哪一季，或者选"全新开始"')
    return
  }
  saving.value = true
  try {
    const req = { ...form, followIds: fresh.value ? [] : form.followIds }
    const view = props.season ? await seasonApi.update(props.season.season.id, req) : await seasonApi.create(req)
    ElMessage.success('已保存')
    visible.value = false
    await store.loadSeasons(true)
    emit('saved', view)
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <el-dialog v-model="visible" :title="season ? '修改季' : '新建季'" width="560px">
    <el-form label-width="96px">
      <el-form-item label="起止日期" required>
        <el-date-picker v-model="form.startDate" type="date" value-format="YYYY-MM-DD" placeholder="开始" style="width: 150px" />
        <span class="sep">到</span>
        <el-date-picker v-model="form.endDate" type="date" value-format="YYYY-MM-DD" placeholder="结束（可不填）" style="width: 150px" />
      </el-form-item>
      <el-form-item label="名称" required>
        <el-input v-model="form.name" maxlength="100" placeholder="例如：2025-2026 年度大田、2026 夏繁" @input="nameTouched = true" />
      </el-form-item>
      <el-form-item label="接着哪一季">
        <el-radio-group v-model="fresh">
          <el-radio :value="false" :disabled="candidates.length === 0">接着以前的季</el-radio>
          <el-radio :value="true">全新开始</el-radio>
        </el-radio-group>
        <div v-if="!fresh" class="follows">
          <el-checkbox-group v-model="form.followIds">
            <el-checkbox v-for="s in candidates" :key="s.id" :value="s.id">{{ s.name }}</el-checkbox>
          </el-checkbox-group>
          <div class="hint">
            种植时，这些季种过的品系和它们的子代排在下拉框最前面。夏繁只带走一部分品系时，下一季大田可以同时接着去年大田和夏繁。
          </div>
        </div>
        <div v-else class="hint">第一季或者另起炉灶：种植时直接搜索品系、新建原始材料。</div>
      </el-form-item>
      <el-form-item label="备注">
        <el-input v-model="form.remark" type="textarea" maxlength="500" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="save">保存</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.sep {
  margin: 0 8px;
  color: var(--ink-3);
}

.follows {
  width: 100%;
  margin-top: 6px;
  padding: 8px 12px;
  border-radius: var(--radius-sm);
  background: var(--surface-2);
}

.follows .el-checkbox {
  margin-right: 18px;
}

.hint {
  width: 100%;
}
</style>
