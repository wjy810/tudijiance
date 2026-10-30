<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'

import { fieldApi, type Field, type FieldDetail } from '@/shared/api'
import { useAuthStore } from '@/stores/auth'
import FieldFormDialog from '@/desktop/components/FieldFormDialog.vue'

const auth = useAuthStore()
const router = useRouter()
const fields = ref<Field[]>([])
const loading = ref(false)
const dialogOpen = ref(false)

async function load() {
  loading.value = true
  try {
    fields.value = await fieldApi.list()
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    loading.value = false
  }
}

function onSaved(detail: FieldDetail) {
  router.push(`/fields/${detail.field.id}`)
}

onMounted(load)
</script>

<template>
  <div class="page" v-loading="loading">
    <div class="page-header">
      <h2>实验田</h2>
      <el-button v-if="auth.isAdmin" type="primary" :icon="Plus" @click="dialogOpen = true">新建实验田</el-button>
    </div>

    <el-empty v-if="!loading && fields.length === 0" description="还没有实验田。新建一块，设定行数和列数后会自动生成小区网格。" />

    <div class="cards">
      <el-card v-for="f in fields" :key="f.id" shadow="hover" class="card" @click="router.push(`/fields/${f.id}`)">
        <div class="card-title">{{ f.name }}</div>
        <div class="muted">{{ f.rowCount }} 行 × {{ f.colCount }} 列，共 {{ f.rowCount * f.colCount }} 个小区</div>
        <div class="muted">
          {{ f.location || '未填写地点' }}
          <template v-if="f.irrigated !== null"> · {{ f.irrigated ? '水地' : '旱地' }}</template>
          <template v-if="f.soilType"> · {{ f.soilType }}</template>
        </div>
      </el-card>
    </div>

    <FieldFormDialog v-model="dialogOpen" @saved="onSaved" />
  </div>
</template>

<style scoped>
.cards {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 16px;
}

.card {
  cursor: pointer;
}

.card-title {
  font-size: 16px;
  font-weight: 600;
  margin-bottom: 6px;
}
</style>
