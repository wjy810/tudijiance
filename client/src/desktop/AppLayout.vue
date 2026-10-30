<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Collection, Cpu, Grid, Tickets } from '@element-plus/icons-vue'

import { useAuthStore } from '@/stores/auth'
import { getServerUrl } from '@/shared/settings'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

// 实验田编辑页也算在"实验田"菜单下
const activeMenu = computed(() => '/' + (route.path.split('/')[1] ?? ''))

function logout() {
  auth.logout()
  router.replace({ name: 'login' })
}
</script>

<template>
  <el-container class="layout">
    <el-aside width="200px" class="aside">
      <div class="brand">
        <div class="brand-title">小麦实验田管理</div>
        <div class="brand-sub">桌面端</div>
      </div>
      <el-menu :default-active="activeMenu" router class="menu">
        <el-menu-item index="/fields">
          <el-icon><Grid /></el-icon>
          <span>实验田</span>
        </el-menu-item>
        <el-menu-item index="/varieties">
          <el-icon><Collection /></el-icon>
          <span>品种</span>
        </el-menu-item>
        <el-menu-item index="/traits">
          <el-icon><Tickets /></el-icon>
          <span>性状字典</span>
        </el-menu-item>
        <el-menu-item index="/devices">
          <el-icon><Cpu /></el-icon>
          <span>传感器</span>
        </el-menu-item>
      </el-menu>
      <div class="aside-footer">
        <div class="muted">{{ auth.user?.displayName || auth.user?.username }}（{{ auth.isAdmin ? '管理员' : '录入员' }}）</div>
        <div class="hint">服务器：{{ getServerUrl() }}</div>
        <el-button text size="small" @click="logout">退出登录</el-button>
      </div>
    </el-aside>
    <el-main class="main">
      <RouterView />
    </el-main>
  </el-container>
</template>

<style scoped>
.layout {
  height: 100%;
}

.aside {
  background: #fff;
  border-right: 1px solid var(--app-border);
  display: flex;
  flex-direction: column;
}

.brand {
  padding: 20px 20px 12px;
}

.brand-title {
  font-size: 17px;
  font-weight: 600;
  color: var(--app-accent);
}

.brand-sub {
  font-size: 12px;
  color: var(--app-text-secondary);
}

.menu {
  border-right: none;
  flex: 1;
}

.aside-footer {
  padding: 12px 20px 16px;
  border-top: 1px solid var(--app-border);
  display: flex;
  flex-direction: column;
  gap: 4px;
  align-items: flex-start;
}

.main {
  padding: 0;
  overflow: auto;
}
</style>
