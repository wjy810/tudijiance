<script setup lang="ts">
import { computed, reactive, ref, type Component } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Collection, Cpu, DataAnalysis, Document, Grid, Monitor, Tickets, User } from '@element-plus/icons-vue'

import { authApi, type Role } from '@/shared/api'
import { ROLE } from '@/shared/labels'
import { useAuthStore } from '@/stores/auth'
import { getServerUrl } from '@/shared/settings'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

interface MenuItem {
  path: string
  label: string
  icon: Component
  roles: Role[]
}

const MENU: MenuItem[] = [
  { path: '/fields', label: '实验田', icon: Grid, roles: ['TEACHER', 'STUDENT'] },
  { path: '/data', label: '数据查看', icon: Document, roles: ['TEACHER', 'STUDENT'] },
  { path: '/stats', label: '统计分析', icon: DataAnalysis, roles: ['TEACHER', 'STUDENT'] },
  { path: '/varieties', label: '品种', icon: Collection, roles: ['TEACHER', 'STUDENT'] },
  { path: '/traits', label: '录入项目', icon: Tickets, roles: ['TEACHER', 'STUDENT'] },
  { path: '/users', label: '用户管理', icon: User, roles: ['SYS_ADMIN'] },
  { path: '/devices', label: '传感器（预留）', icon: Cpu, roles: ['SYS_ADMIN', 'TEACHER'] },
]

const menu = computed(() => MENU.filter((m) => auth.role && m.roles.includes(auth.role)))

// 平面图页面也算在"实验田"菜单下
const activeMenu = computed(() => '/' + (route.path.split('/')[1] ?? ''))

function logout() {
  auth.logout()
  router.replace({ name: 'login' })
}

// ---------- 修改自己的密码 ----------
const pwdOpen = ref(false)
const pwd = reactive({ old: '', next: '', again: '' })
const pwdSaving = ref(false)

function openPassword() {
  Object.assign(pwd, { old: '', next: '', again: '' })
  pwdOpen.value = true
}

async function savePassword() {
  if (pwd.next.length < 6) {
    ElMessage.warning('新密码至少 6 位')
    return
  }
  if (pwd.next !== pwd.again) {
    ElMessage.warning('两次输入的新密码不一样')
    return
  }
  pwdSaving.value = true
  try {
    await authApi.changePassword(pwd.old, pwd.next)
    ElMessage.success('密码已修改')
    pwdOpen.value = false
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    pwdSaving.value = false
  }
}
</script>

<template>
  <el-container class="layout">
    <el-aside width="200px" class="aside">
      <div class="brand">
        <div class="brand-title">小麦实验田管理</div>
        <div class="brand-sub">第一期 · 数据采集与展示</div>
      </div>
      <el-menu :default-active="activeMenu" router class="menu">
        <el-menu-item v-for="m in menu" :key="m.path" :index="m.path">
          <el-icon><component :is="m.icon" /></el-icon>
          <span>{{ m.label }}</span>
        </el-menu-item>
      </el-menu>
      <div class="screen-entry">
        <el-button :icon="Monitor" plain @click="router.push('/screen')">打开数据大屏</el-button>
      </div>
      <div class="aside-footer">
        <div>{{ auth.user?.displayName || auth.user?.username }}</div>
        <div class="muted">{{ auth.role ? ROLE[auth.role] : '' }}</div>
        <div class="hint">服务器：{{ getServerUrl() }}</div>
        <div>
          <el-button text size="small" @click="openPassword">修改密码</el-button>
          <el-button text size="small" @click="logout">退出登录</el-button>
        </div>
      </div>
    </el-aside>
    <el-main class="main">
      <RouterView />
    </el-main>

    <el-dialog v-model="pwdOpen" title="修改密码" width="400px">
      <el-form label-width="90px" @submit.prevent="savePassword">
        <el-form-item label="原密码">
          <el-input v-model="pwd.old" type="password" show-password />
        </el-form-item>
        <el-form-item label="新密码">
          <el-input v-model="pwd.next" type="password" show-password placeholder="至少 6 位" />
        </el-form-item>
        <el-form-item label="再输一次">
          <el-input v-model="pwd.again" type="password" show-password />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="pwdOpen = false">取消</el-button>
        <el-button type="primary" :loading="pwdSaving" @click="savePassword">保存</el-button>
      </template>
    </el-dialog>
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

.screen-entry {
  padding: 0 20px 12px;
}

.screen-entry .el-button {
  width: 100%;
}

.aside-footer {
  padding: 12px 20px 16px;
  border-top: 1px solid var(--app-border);
  display: flex;
  flex-direction: column;
  gap: 2px;
  align-items: flex-start;
}

.aside-footer .el-button + .el-button {
  margin-left: 4px;
}

.main {
  padding: 0;
  overflow: auto;
}
</style>
