<script setup lang="ts">
// 系统管理员、游客在手机上没有要做的事，告诉他们去哪儿
import { useRouter } from 'vue-router'

import { useAuthStore } from '@/stores/auth'
import { ROLE } from '@/shared/labels'
import EmptyState from '@/desktop/components/EmptyState.vue'

const auth = useAuthStore()
const router = useRouter()

function logout() {
  auth.logout()
  void router.replace('/login')
}
</script>

<template>
  <div class="blocked">
    <EmptyState art="field" :size="150" :title="`${auth.role ? ROLE[auth.role] : ''}账号不用手机录入`"
      :desc="auth.isGuest ? '游客请在数据大屏上看。' : '系统管理员请在电脑端管理用户和设备。'">
      <van-button round type="primary" @click="logout">换个账号登录</van-button>
    </EmptyState>
  </div>
</template>

<style scoped>
.blocked {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
}
</style>
