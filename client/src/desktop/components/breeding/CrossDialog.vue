<script setup lang="ts">
// 杂交：选母本和父本（可以对调），选哪一季做的。编号自动给"杂交年份后两位-当年第几个"，名称不填就用编号
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Sort } from '@element-plus/icons-vue'

import { lineApi, type CrossResult } from '@/shared/api'
import { useBreedingStore } from '@/stores/breeding'
import LineSelect from './LineSelect.vue'

const props = defineProps<{ motherId?: number | null; fatherId?: number | null; seasonId?: number | null }>()
const visible = defineModel<boolean>({ required: true })
const emit = defineEmits<{ saved: [result: CrossResult] }>()

const store = useBreedingStore()
const form = reactive({
  motherId: null as number | null, fatherId: null as number | null, seasonId: null as number | null,
  name: '', remark: '',
})
const saving = ref(false)

watch(visible, (open) => {
  if (!open) return
  const last = store.seasons[store.seasons.length - 1]
  Object.assign(form, {
    motherId: props.motherId ?? null, fatherId: props.fatherId ?? null,
    seasonId: props.seasonId ?? last?.id ?? null, name: '', remark: '',
  })
})

function swap() {
  ;[form.motherId, form.fatherId] = [form.fatherId, form.motherId]
}

const mother = computed(() => (form.motherId ? store.lineById.get(form.motherId) : null))
const father = computed(() => (form.fatherId ? store.lineById.get(form.fatherId) : null))
const preview = computed(() => {
  if (!mother.value || !father.value) return ''
  return `${mother.value.name} × ${father.value.name}`
})

async function save() {
  if (!form.motherId || !form.fatherId || !form.seasonId) {
    ElMessage.warning('请选择母本、父本和哪一季做的')
    return
  }
  saving.value = true
  try {
    const result = await lineApi.cross({
      motherId: form.motherId, fatherId: form.fatherId, seasonId: form.seasonId,
      name: form.name.trim() || null, remark: form.remark.trim() || null,
    })
    visible.value = false
    await store.reload()
    emit('saved', result)
    if (result.previous.length) {
      void ElMessageBox.alert(
        `建好了 ${result.line.code}。这对父母以前做过：${result.previous.join('、')}，这次是另一批种子，编号不同。`,
        '这对父母以前做过', { type: 'info', confirmButtonText: '知道了' })
    } else {
      ElMessage.success(`建好了杂交组合 ${result.line.code}（${result.line.pedigree}）`)
    }
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <el-dialog v-model="visible" title="杂交" width="560px">
    <el-form label-width="88px">
      <el-form-item label="母本" required>
        <LineSelect v-model="form.motherId" placeholder="搜母本" style="width: 300px" />
      </el-form-item>
      <el-form-item>
        <el-button :icon="Sort" size="small" @click="swap">母本、父本对调</el-button>
      </el-form-item>
      <el-form-item label="父本" required>
        <LineSelect v-model="form.fatherId" placeholder="搜父本" style="width: 300px" />
      </el-form-item>
      <el-form-item label="哪一季做的" required>
        <el-select v-model="form.seasonId" style="width: 300px">
          <el-option v-for="s in [...store.seasons].reverse()" :key="s.id" :value="s.id" :label="s.name" />
        </el-select>
      </el-form-item>
      <el-form-item label="名称">
        <el-input v-model="form.name" maxlength="100" placeholder="不填就用编号，如 25-001" />
      </el-form-item>
      <el-form-item label="备注">
        <el-input v-model="form.remark" maxlength="500" />
      </el-form-item>
      <div v-if="preview" class="preview">
        <b>{{ preview }}</b>
        <span class="hint">编号按杂交年份自动给，系谱式自动写。母本、父本对调算不同的组合。</span>
      </div>
    </el-form>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="save">建好杂交组合</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.preview {
  margin-left: 88px;
  padding: 8px 12px;
  border-radius: var(--radius-sm);
  background: var(--wheat-wash);
  display: flex;
  flex-direction: column;
  gap: 2px;
}
</style>
