<script setup lang="ts">
// 给选中的小区分配品种。品种列表里没有的可以直接新增
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'

import { trialApi, varietyApi, type ControlType, type Layout } from '@/shared/api'
import { CONTROL_TYPE } from '@/shared/labels'
import { useCatalogStore } from '@/stores/catalog'

const props = defineProps<{ trialId: number; plotIds: number[] }>()
const visible = defineModel<boolean>({ required: true })
const emit = defineEmits<{ saved: [layout: Layout] }>()

const catalog = useCatalogStore()
const varietyId = ref<number | null>(null)
const keyword = ref('')
const saving = ref(false)
const adding = ref(false)
const newVariety = reactive<{ name: string; controlType: ControlType | null }>({ name: '', controlType: null })

const filtered = computed(() => {
  const k = keyword.value.trim()
  return k ? catalog.varieties.filter((v) => v.name.includes(k) || v.code?.includes(k)) : catalog.varieties
})

watch(visible, (open) => {
  if (open) {
    varietyId.value = null
    keyword.value = ''
    adding.value = false
    catalog.loadVarieties().catch((e: Error) => ElMessage.error(e.message))
  }
})

async function assign(value: number | null) {
  saving.value = true
  try {
    const layout = await trialApi.assign(props.trialId, props.plotIds, 'VARIETY', value)
    ElMessage.success(value ? `已给 ${props.plotIds.length} 个小区分配品种` : '已清除品种')
    visible.value = false
    emit('saved', layout)
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    saving.value = false
  }
}

async function addVariety() {
  const name = newVariety.name.trim()
  if (!name) {
    ElMessage.warning('请填写品种名称')
    return
  }
  try {
    const created = await varietyApi.create({
      name, code: null, breeder: null, ecotype: null, qualityType: null, controlType: newVariety.controlType, remark: null,
    })
    await catalog.loadVarieties(true)
    varietyId.value = created.id
    adding.value = false
    Object.assign(newVariety, { name: '', controlType: null })
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}
</script>

<template>
  <el-dialog v-model="visible" title="分配品种" width="560px">
    <div class="hint top">给选中的 {{ plotIds.length }} 个小区种同一个品种。</div>
    <el-input v-model="keyword" placeholder="搜索品种名称或编号" clearable />
    <el-radio-group v-model="varietyId" class="list">
      <el-radio v-for="v in filtered" :key="v.id" :value="v.id" class="item" border>
        {{ v.name }}
        <el-tag v-if="v.controlType" size="small" type="danger" effect="plain">{{ CONTROL_TYPE[v.controlType] }}</el-tag>
      </el-radio>
    </el-radio-group>
    <el-empty v-if="filtered.length === 0" :image-size="50" description="没有找到品种" />

    <div v-if="adding" class="add">
      <el-input v-model="newVariety.name" maxlength="100" placeholder="品种名称" />
      <el-select v-model="newVariety.controlType" clearable placeholder="是否对照品种" class="ck">
        <el-option v-for="(label, key) in CONTROL_TYPE" :key="key" :value="key" :label="label" />
      </el-select>
      <el-button type="primary" @click="addVariety">添加</el-button>
      <el-button @click="adding = false">取消</el-button>
    </div>
    <el-button v-else text type="primary" @click="adding = true">+ 新增品种</el-button>

    <template #footer>
      <el-button text type="danger" class="left" :loading="saving" @click="assign(null)">清除品种</el-button>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="saving" :disabled="!varietyId" @click="assign(varietyId)">分配</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.top {
  margin-bottom: 10px;
}

.list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin: 12px 0;
  max-height: 300px;
  overflow: auto;
}

.item {
  margin-right: 0;
}

.add {
  display: flex;
  gap: 8px;
}

.ck {
  width: 170px;
  flex-shrink: 0;
}

.left {
  float: left;
}
</style>
