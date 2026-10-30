<script setup lang="ts">
import { computed, reactive, ref, type Component } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  ArrowDown, Collection, Cpu, DataAnalysis, Document, HomeFilled, Monitor, Tickets, User,
} from '@element-plus/icons-vue'

import { authApi, type Role } from '@/shared/api'
import { ROLE } from '@/shared/labels'
import { useAuthStore } from '@/stores/auth'
import { getServerUrl } from '@/shared/settings'
import BrandMark from '@/desktop/components/BrandMark.vue'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

interface MenuItem {
  path: string
  label: string
  icon: Component
  roles: Role[]
}

const RECORDERS: Role[] = ['TEACHER', 'STUDENT']

const GROUPS: { title: string; items: MenuItem[] }[] = [
  {
    title: '日常',
    items: [
      { path: '/home', label: '首页', icon: HomeFilled, roles: RECORDERS },
      { path: '/data', label: '数据查看', icon: Document, roles: RECORDERS },
      { path: '/stats', label: '统计分析', icon: DataAnalysis, roles: RECORDERS },
    ],
  },
  {
    title: '资料',
    items: [
      { path: '/varieties', label: '品种', icon: Collection, roles: RECORDERS },
      { path: '/traits', label: '录入项目', icon: Tickets, roles: RECORDERS },
    ],
  },
  {
    title: '系统',
    items: [
      { path: '/users', label: '用户管理', icon: User, roles: ['SYS_ADMIN'] },
      { path: '/devices', label: '设备接入（预留）', icon: Cpu, roles: ['SYS_ADMIN'] },
    ],
  },
]

const groups = computed(() =>
  GROUPS.map((g) => ({ ...g, items: g.items.filter((m) => auth.role && m.roles.includes(auth.role)) }))
    .filter((g) => g.items.length > 0),
)

// 平面图是从首页点进去的，菜单仍然高亮"首页"
const activeMenu = computed(() => (route.path.startsWith('/fields') ? '/home' : '/' + (route.path.split('/')[1] ?? '')))

const displayName = computed(() => auth.user?.displayName || auth.user?.username || '')

function onCommand(cmd: string) {
  if (cmd === 'password') {
    openPassword()
  } else if (cmd === 'logout') {
    auth.logout()
    router.replace({ name: 'login' })
  }
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
  <div class="layout">
    <aside class="aside">
      <div class="brand">
        <BrandMark :size="30" color="#e6c36a" />
        <div>
          <div class="brand-title">小麦实验田</div>
          <div class="brand-sub">品种试验数据平台</div>
        </div>
      </div>

      <nav class="nav">
        <div v-for="g in groups" :key="g.title" class="nav-group">
          <div class="nav-group-title">{{ g.title }}</div>
          <RouterLink
            v-for="m in g.items"
            :key="m.path"
            :to="m.path"
            class="nav-item"
            :class="{ active: activeMenu === m.path }"
          >
            <el-icon><component :is="m.icon" /></el-icon>
            <span>{{ m.label }}</span>
          </RouterLink>
        </div>
        <div class="nav-group">
          <div class="nav-group-title">展示</div>
          <RouterLink to="/screen" class="nav-item">
            <el-icon><Monitor /></el-icon>
            <span>数据大屏</span>
          </RouterLink>
        </div>
      </nav>

      <el-dropdown trigger="click" placement="top-start" class="user-dropdown" @command="onCommand">
        <div class="user">
          <div class="avatar">{{ displayName.slice(0, 1) }}</div>
          <div class="user-text">
            <div class="user-name">{{ displayName }}</div>
            <div class="user-role">{{ auth.role ? ROLE[auth.role] : '' }}</div>
          </div>
          <el-icon class="user-arrow"><ArrowDown /></el-icon>
        </div>
        <template #dropdown>
          <div class="server">服务器 {{ getServerUrl() }}</div>
          <el-dropdown-menu>
            <el-dropdown-item command="password">修改密码</el-dropdown-item>
            <el-dropdown-item command="logout" divided>退出登录</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </aside>

    <main class="main">
      <RouterView />
    </main>

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
  </div>
</template>

<style scoped>
.layout {
  height: 100%;
  display: flex;
}

.aside {
  width: 212px;
  flex-shrink: 0;
  background: linear-gradient(180deg, #17402d 0%, #123323 100%);
  color: #d4e3d9;
  display: flex;
  flex-direction: column;
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 22px 20px 18px;
}

.brand-title {
  font-size: 17px;
  font-weight: 600;
  color: #ffffff;
  letter-spacing: 1px;
}

.brand-sub {
  font-size: 12px;
  color: #8fb09c;
  margin-top: 2px;
}

.nav {
  flex: 1;
  overflow: auto;
  padding: 4px 12px;
}

.nav-group {
  margin-bottom: 14px;
}

.nav-group-title {
  font-size: 12px;
  color: #6f9580;
  padding: 0 10px 6px;
  letter-spacing: 2px;
}

.nav-item {
  display: flex;
  align-items: center;
  gap: 10px;
  height: 40px;
  padding: 0 12px;
  margin-bottom: 2px;
  border-radius: 8px;
  color: #d4e3d9;
  text-decoration: none;
  font-size: 14px;
  position: relative;
  transition: background 0.15s;
}

.nav-item:hover {
  background: rgba(255, 255, 255, 0.07);
  color: #ffffff;
}

.nav-item.active {
  background: rgba(255, 255, 255, 0.13);
  color: #ffffff;
  font-weight: 600;
}

.nav-item.active::before {
  content: '';
  position: absolute;
  left: -12px;
  top: 10px;
  bottom: 10px;
  width: 3px;
  border-radius: 0 3px 3px 0;
  background: #e6c36a;
}

.nav-item .el-icon {
  font-size: 16px;
}

.user-dropdown {
  display: block;
}

.user {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 8px 12px 14px;
  padding: 10px 12px;
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.06);
  cursor: pointer;
  width: 164px;
}

.user:hover {
  background: rgba(255, 255, 255, 0.1);
}

.avatar {
  width: 34px;
  height: 34px;
  border-radius: 50%;
  background: #e6c36a;
  color: #17402d;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.user-text {
  flex: 1;
  min-width: 0;
}

.user-name {
  color: #ffffff;
  font-size: 14px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.user-role {
  color: #8fb09c;
  font-size: 12px;
}

.user-arrow {
  color: #8fb09c;
}

.server {
  padding: 8px 16px 4px;
  font-size: 12px;
  color: var(--app-text-muted);
}

.main {
  flex: 1;
  min-width: 0;
  overflow: auto;
}
</style>
