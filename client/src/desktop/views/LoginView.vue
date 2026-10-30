<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'

import { useAuthStore } from '@/stores/auth'
import { getServerUrl, setServerUrl } from '@/shared/settings'

const auth = useAuthStore()
const router = useRouter()

const form = reactive({ username: '', password: '', server: getServerUrl() })
const loading = ref(false)

async function submit() {
  if (!form.username || !form.password) {
    ElMessage.warning('请输入用户名和密码')
    return
  }
  setServerUrl(form.server)
  loading.value = true
  try {
    await auth.login(form.username, form.password)
    router.replace('/fields')
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <el-card class="login-card" shadow="never">
      <h1>小麦实验田管理</h1>
      <p class="muted">品种试验数据采集与可视化</p>
      <el-form label-position="top" @submit.prevent="submit">
        <el-form-item label="用户名">
          <el-input v-model="form.username" autocomplete="username" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="form.password" type="password" show-password autocomplete="current-password" />
        </el-form-item>
        <el-form-item label="服务器地址">
          <el-input v-model="form.server" placeholder="http://服务器地址:8080" />
        </el-form-item>
        <el-button type="primary" native-type="submit" :loading="loading" class="submit">登录</el-button>
      </el-form>
    </el-card>
  </div>
</template>

<style scoped>
.login-page {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(160deg, #eef5ef 0%, #f7f4ea 100%);
}

.login-card {
  width: 380px;
  padding: 8px 12px;
}

h1 {
  margin: 0 0 4px;
  font-size: 22px;
  color: var(--app-accent);
}

.submit {
  width: 100%;
  margin-top: 8px;
}
</style>
