<script setup lang="ts">
// 新建品种（原始材料）：一行一个名字，可以一次建多个
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'

import { lineApi, type LineRow } from '@/shared/api'
import { useBreedingStore } from '@/stores/breeding'

const visible = defineModel<boolean>({ required: true })
const emit = defineEmits<{ saved: [rows: LineRow[]] }>()

const store = useBreedingStore()
const text = ref('')
const saving = ref(false)

/** 一行一个；"名称,编号"可以带上编号 */
const items = computed(() => text.value.split(/\n/).map((s) => s.trim()).filter(Boolean).map((line) => {
  const [name, code] = line.split(/[,，\t]/).map((x) => x.trim())
  return { name: name!, code: code || null }
}))


watch(visible, (open) => {
  if (!open) return
  text.value = ''
})

async function save() {
  saving.value = true
  try {
    if (items.value.length === 0) {
      ElMessage.warning('请填写名称，一行一个')
      return
    }
    const rows = await lineApi.createFounders(items.value)
    ElMessage.success(`建好 ${rows.length} 个原始材料`)
    visible.value = false
    await store.reload()
    emit('saved', rows)
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <el-dialog v-model="visible" title="新建品种（原始材料）" width="560px">
    <el-input
      v-model="text"
      type="textarea"
      :rows="8"
      placeholder="一行一个，例如：&#10;周麦16&#10;郑麦366&#10;百农207&#10;要带编号时写成“名称,编号”"
    />
    <div class="hint">
      原始材料就是外引的、已有的、地方的品种：没有父母记录，算第 1 代。编号不填时用名称。名称里不能有“/”和“*”。
      统一编号、对照类型建好后在品系库里改。
      <template v-if="items.length">将建 {{ items.length }} 个。</template>
    </div>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="save">建好</el-button>
    </template>
  </el-dialog>
</template>
