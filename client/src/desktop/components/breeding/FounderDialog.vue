<script setup lang="ts">
// 新建原始材料：一行一个名字，可以一次建多个；也可以把原来品种比较里的品种拿来当原始材料
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'

import { lineApi, type LineRow } from '@/shared/api'
import { useBreedingStore } from '@/stores/breeding'
import { useCatalogStore } from '@/stores/catalog'

const visible = defineModel<boolean>({ required: true })
const emit = defineEmits<{ saved: [rows: LineRow[]] }>()

const store = useBreedingStore()
const catalog = useCatalogStore()
const tab = ref<'new' | 'adopt'>('new')
const text = ref('')
const adoptIds = ref<number[]>([])
const saving = ref(false)

/** 一行一个；"名称,编号"可以带上编号 */
const items = computed(() => text.value.split(/\n/).map((s) => s.trim()).filter(Boolean).map((line) => {
  const [name, code] = line.split(/[,，\t]/).map((x) => x.trim())
  return { name: name!, code: code || null }
}))

const adoptable = computed(() => catalog.varieties.filter((v) => !v.lineKind))

watch(visible, (open) => {
  if (!open) return
  text.value = ''
  adoptIds.value = []
  tab.value = 'new'
  void catalog.loadVarieties()
})

async function save() {
  saving.value = true
  try {
    let rows: LineRow[]
    if (tab.value === 'new') {
      if (items.value.length === 0) {
        ElMessage.warning('请填写名称，一行一个')
        return
      }
      rows = await lineApi.createFounders(items.value)
    } else {
      if (adoptIds.value.length === 0) {
        ElMessage.warning('请选择品种')
        return
      }
      rows = await lineApi.adopt(adoptIds.value)
    }
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
  <el-dialog v-model="visible" title="新建原始材料" width="560px">
    <el-tabs v-model="tab">
      <el-tab-pane label="填名字" name="new">
        <el-input
          v-model="text"
          type="textarea"
          :rows="8"
          placeholder="一行一个，例如：&#10;周麦16&#10;郑麦366&#10;百农207&#10;要带编号时写成“名称,编号”"
        />
        <div class="hint">
          原始材料是没有父母记录的品系（外引品种、已有品种、地方品种），算第 1 代。编号不填时用名称。名称里不能有“/”和“*”。
          <template v-if="items.length">将建 {{ items.length }} 个。</template>
        </div>
      </el-tab-pane>
      <el-tab-pane label="从品种里选" name="adopt">
        <el-select v-model="adoptIds" multiple filterable placeholder="原来品种比较里的品种" style="width: 100%">
          <el-option v-for="v in adoptable" :key="v.id" :value="v.id" :label="v.name" />
        </el-select>
        <div class="hint">同一条记录，品种页照常能看到；拿来当原始材料后可以种到育种地块上、做杂交和选株。</div>
      </el-tab-pane>
    </el-tabs>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="save">建好</el-button>
    </template>
  </el-dialog>
</template>
