<script setup lang="ts">
// 电脑端框架：左边深绿侧栏（品牌、分组菜单、用户），右边顶栏（面包屑、快速跳转、实验田切换）+ 页面内容。
// 侧栏选中的菜单像笔记本的书签页一样"拉出来"，和右边的纸面连在一起。侧栏可以收起成只有图标。
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch, type Component } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  ArrowDown, ArrowRight, Calendar, Check, Collection, Cpu, DataAnalysis, Document, Expand, Fold, Grid, HomeFilled, Notebook, Share,
  DocumentChecked, EditPen, Link as LinkIcon, Lock, Menu, Monitor, PieChart, Search, SwitchButton, Tickets, TopRight, Upload, User,
} from '@element-plus/icons-vue'

import { authApi, fieldApi, type Field, type Role } from '@/shared/api'
import { ROLE } from '@/shared/labels'
import { useAuthStore } from '@/stores/auth'
import { useModuleStore } from '@/stores/modules'
import { useCorrectionStore } from '@/stores/corrections'
import { getServerUrl } from '@/shared/settings'
import CommandPalette, { type PaletteItem } from '@/desktop/components/CommandPalette.vue'
import appIcon from '@/assets/art/app-icon.webp'
import wheatArt from '@/assets/art/sidebar-wheat.webp'

const auth = useAuthStore()
const modules = useModuleStore()
const corrections = useCorrectionStore()
const route = useRoute()
const router = useRouter()

interface MenuItem {
  path: string
  label: string
  icon: Component
  roles: Role[]
  /** 菜单右边的小数字（例如待审核条数），没有或为 0 时不显示 */
  badge?: () => number | null | undefined
  /** 属于哪个功能模块：模块停用时不显示 */
  module?: string
  /** 链接模块：在浏览器里打开的外部地址 */
  href?: string
}

const RECORDERS: Role[] = ['TEACHER', 'STUDENT']

const GROUPS: { title: string; items: MenuItem[] }[] = [
  {
    title: '育种',
    items: [
      { path: '/breeding/seasons', label: '季和地块', icon: Calendar, roles: RECORDERS, module: 'breeding' },
      { path: '/breeding/lines', label: '品系库', icon: Notebook, roles: RECORDERS, module: 'breeding' },
      { path: '/breeding/graph', label: '系谱图', icon: Share, roles: RECORDERS, module: 'breeding' },
    ],
  },
  {
    title: '品种比较',
    items: [
      { path: '/home', label: '首页', icon: HomeFilled, roles: RECORDERS },
      { path: '/data', label: '数据查看', icon: Document, roles: RECORDERS, module: 'data' },
      { path: '/stats', label: '统计分析', icon: DataAnalysis, roles: RECORDERS, module: 'stats' },
      { path: '/analysis', label: '自助分析', icon: PieChart, roles: RECORDERS, module: 'analysis' },
      { path: '/quality', label: '数据质量', icon: DocumentChecked, roles: RECORDERS, module: 'quality' },
      { path: '/varieties', label: '品种', icon: Collection, roles: RECORDERS },
      { path: '/import', label: '导入历史表格', icon: Upload, roles: ['TEACHER'], module: 'import' },
      { path: '/screen', label: '数据大屏', icon: Monitor, roles: ['SYS_ADMIN', 'TEACHER', 'STUDENT'], module: 'screen' },
    ],
  },
  {
    title: '共用',
    items: [
      {
        path: '/corrections', label: '更正申请', icon: EditPen, roles: RECORDERS, module: 'corrections',
        // 老师：待审核的；学生：自己还没处理的
        badge: () => corrections.summary?.pending,
      },
      { path: '/traits', label: '录入项目', icon: Tickets, roles: RECORDERS },
    ],
  },
  {
    title: '系统',
    items: [
      { path: '/users', label: '用户管理', icon: User, roles: ['SYS_ADMIN'] },
      { path: '/modules', label: '功能模块', icon: Menu, roles: ['SYS_ADMIN'] },
      { path: '/devices', label: '设备接入（预留）', icon: Cpu, roles: ['SYS_ADMIN'], module: 'devices' },
    ],
  },
]

const groups = computed(() => {
  const list = GROUPS.map((g) => ({
    ...g,
    items: g.items.filter((m) => auth.role && m.roles.includes(auth.role) && modules.isOn(m.module)),
  }))
  // 系统管理员加的链接模块（服务器已经按角色筛过）
  if (modules.links.length > 0) {
    list.push({
      title: '链接',
      items: modules.links.map((l): MenuItem => ({
        path: `/link/${l.code}`, label: l.name, icon: LinkIcon, roles: [], href: l.url ?? undefined,
      })),
    })
  }
  return list.filter((g) => g.items.length > 0)
})

// 平面图是从首页点进去的，菜单仍然高亮"首页"；育种的地块高亮"季和地块"
const activeMenu = computed(() => {
  if (route.path.startsWith('/fields/') && currentLand.value) return '/breeding/seasons'
  if (route.path.startsWith('/fields') || route.path.startsWith('/trials')) return '/home'
  if (route.path.startsWith('/breeding/')) {
    return route.path.startsWith('/breeding/lines') ? '/breeding/lines' : '/' + route.path.split('/').slice(1, 3).join('/')
  }
  return '/' + (route.path.split('/')[1] ?? '')
})

const displayName = computed(() => auth.user?.displayName || auth.user?.username || '')

function badgeOf(m: MenuItem): number | null {
  const n = m.badge?.()
  return n ? n : null
}

// ---------- 侧栏收起 ----------
const COLLAPSE_KEY = 'tudijiance.navCollapsed'
const collapsed = ref(localStorage.getItem(COLLAPSE_KEY) === '1')
watch(collapsed, (v) => localStorage.setItem(COLLAPSE_KEY, v ? '1' : '0'))

// ---------- 实验田（顶栏切换、快速跳转用） ----------
const allFields = ref<Field[]>([])
/** 原来品种比较的实验田（顶栏切换用） */
const fields = computed(() => allFields.value.filter((f) => !f.seasonId))
/** 育种的地块 */
const lands = computed(() => allFields.value.filter((f) => !!f.seasonId))

async function loadFields() {
  if (!auth.canRecord) return
  try {
    allFields.value = await fieldApi.list('all')
  } catch {
    // 顶栏的实验田列表取不到不影响页面本身
  }
}

/** 正在看的是育种的地块 */
const currentLand = computed(() =>
  (route.path.startsWith('/fields/') ? lands.value.find((f) => f.id === Number(route.params.id)) ?? null : null))

/** 当前实验田：在平面图里就是正在看的那块，否则是上次打开的那块 */
const currentField = computed(() => {
  const fromRoute = route.path.startsWith('/fields/') ? Number(route.params.id) : null
  const remembered = Number(localStorage.getItem('tudijiance.lastField'))
  return fields.value.find((f) => f.id === fromRoute)
    ?? fields.value.find((f) => f.id === remembered)
    ?? fields.value[0]
    ?? null
})

function openField(id: number) {
  router.push(`/fields/${id}`)
}

// ---------- 面包屑 ----------
const crumbs = computed<{ label: string; to?: string }[]>(() => {
  if (route.path.startsWith('/fields/') && currentLand.value) {
    return [{ label: '季和地块', to: '/breeding/seasons' }, { label: currentLand.value.name }]
  }
  if (route.path.startsWith('/fields/')) {
    const f = fields.value.find((x) => x.id === Number(route.params.id))
    return [{ label: '首页', to: '/home' }, { label: f?.name ?? '实验田平面图' }]
  }
  if (route.path.startsWith('/breeding/lines/')) {
    return [{ label: '育种' }, { label: '品系库', to: '/breeding/lines' }, { label: '品系详情' }]
  }
  if (route.path.startsWith('/trials/')) {
    return [{ label: '首页', to: '/home' }, { label: '试验记载本' }]
  }
  for (const g of groups.value) {
    const m = g.items.find((x) => x.path === activeMenu.value)
    if (m) return [{ label: g.title }, { label: m.label }]
  }
  return []
})

// ---------- 快速跳转（Ctrl + K） ----------
const paletteOpen = ref(false)

const paletteItems = computed<PaletteItem[]>(() => [
  ...groups.value.flatMap((g) => g.items.map((m) => ({
    id: 'page:' + m.path,
    group: '页面',
    label: m.label,
    desc: g.title,
    icon: m.icon,
    run: () => (m.href ? window.open(m.href, '_blank', 'noopener') : router.push(m.path)),
  }))),
  ...fields.value.map((f) => ({
    id: 'field:' + f.id,
    group: '实验田平面图',
    label: f.name,
    desc: `${f.rowCount} 行 × ${f.colCount} 列${f.location ? ' · ' + f.location : ''}`,
    icon: Grid,
    keywords: '平面图 录入',
    run: () => openField(f.id),
  })),
  ...lands.value.map((f) => ({
    id: 'land:' + f.id,
    group: '育种地块',
    label: f.name,
    desc: '育种地块',
    icon: Calendar,
    keywords: '育种 地块 平面图 种植',
    run: () => openField(f.id),
  })),
  { id: 'act:password', group: '账号', label: '修改密码', icon: Lock, run: () => openPassword() },
])

function openPalette() {
  loadFields()
  paletteOpen.value = true
}

function onKeydown(e: KeyboardEvent) {
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k') {
    e.preventDefault()
    if (paletteOpen.value) {
      paletteOpen.value = false
    } else {
      openPalette()
    }
  }
}

onMounted(() => {
  loadFields()
  if (auth.canRecord) void corrections.refresh()
  window.addEventListener('keydown', onKeydown)
})

onBeforeUnmount(() => window.removeEventListener('keydown', onKeydown))

// 新建、改名实验田后，顶栏的列表跟着更新：回到首页时刷新；打开了列表里没有的实验田时也刷新
watch(() => route.path, (p) => {
  const missing = p.startsWith('/fields/') && !allFields.value.some((f) => f.id === Number(route.params.id))
  if (p === '/home' || missing) loadFields()
})

// ---------- 用户菜单 ----------
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
  <div class="layout" :class="{ collapsed }">
    <aside class="aside">
      <img class="aside-art" :src="wheatArt" alt="" draggable="false" />

      <RouterLink :to="auth.homePath" class="brand" title="回到首页">
        <img class="brand-icon" :src="appIcon" alt="" />
        <div class="brand-text">
          <div class="brand-title">小麦实验田</div>
          <div class="brand-sub">品种试验数据平台</div>
        </div>
      </RouterLink>

      <nav class="nav" aria-label="主菜单">
        <div v-for="g in groups" :key="g.title" class="nav-group">
          <div class="nav-group-title"><span>{{ g.title }}</span></div>
          <el-tooltip
            v-for="m in g.items"
            :key="m.path"
            :content="m.label"
            placement="right"
            :disabled="!collapsed"
            :show-after="200"
          >
            <a
              v-if="m.href"
              :href="m.href"
              target="_blank"
              rel="noopener noreferrer"
              class="nav-item"
              :title="m.href"
            >
              <el-icon class="nav-icon"><component :is="m.icon" /></el-icon>
              <span class="nav-label">{{ m.label }}</span>
              <el-icon class="nav-ext"><TopRight /></el-icon>
            </a>
            <RouterLink
              v-else
              :to="m.path"
              class="nav-item"
              :class="{ active: activeMenu === m.path }"
              :aria-current="activeMenu === m.path ? 'page' : undefined"
            >
              <el-icon class="nav-icon"><component :is="m.icon" /></el-icon>
              <span class="nav-label">{{ m.label }}</span>
              <span v-if="badgeOf(m)" class="nav-badge num">{{ badgeOf(m) }}</span>
            </RouterLink>
          </el-tooltip>
        </div>
      </nav>

      <el-dropdown trigger="click" placement="top-start" class="user-dropdown" popper-class="user-menu" @command="onCommand">
        <button type="button" class="user" :title="collapsed ? displayName : undefined">
          <span class="avatar">{{ displayName.slice(0, 1) }}</span>
          <span class="user-text">
            <span class="user-name">{{ displayName }}</span>
            <span class="user-role">{{ auth.role ? ROLE[auth.role] : '' }}</span>
          </span>
          <el-icon class="user-arrow"><ArrowDown /></el-icon>
        </button>
        <template #dropdown>
          <div class="user-menu-head">
            <div class="umh-name">{{ displayName }}<span class="chip green">{{ auth.role ? ROLE[auth.role] : '' }}</span></div>
            <div class="umh-server">服务器 {{ getServerUrl() }}</div>
          </div>
          <el-dropdown-menu>
            <el-dropdown-item command="password" :icon="Lock">修改密码</el-dropdown-item>
            <el-dropdown-item command="logout" :icon="SwitchButton" divided>退出登录</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </aside>

    <div class="main">
      <header class="topbar">
        <el-tooltip :content="collapsed ? '展开菜单' : '收起菜单'" placement="bottom" :show-after="300">
          <button type="button" class="icon-btn" :aria-label="collapsed ? '展开菜单' : '收起菜单'" @click="collapsed = !collapsed">
            <el-icon><component :is="collapsed ? Expand : Fold" /></el-icon>
          </button>
        </el-tooltip>

        <nav class="crumbs" aria-label="当前位置">
          <template v-for="(c, i) in crumbs" :key="i">
            <el-icon v-if="i > 0" class="crumb-sep"><ArrowRight /></el-icon>
            <RouterLink v-if="c.to" :to="c.to" class="crumb link">{{ c.label }}</RouterLink>
            <span v-else class="crumb" :class="{ last: i === crumbs.length - 1 }">{{ c.label }}</span>
          </template>
        </nav>

        <div class="topbar-right">
          <button type="button" class="search-trigger" @click="openPalette">
            <el-icon><Search /></el-icon>
            <span class="st-text">搜索页面、实验田</span>
            <span class="st-keys"><kbd>Ctrl</kbd><kbd>K</kbd></span>
          </button>

          <el-dropdown
            v-if="auth.canRecord && currentField"
            trigger="click"
            placement="bottom-end"
            popper-class="field-menu"
            @command="openField"
            @visible-change="(v: boolean) => v && loadFields()"
          >
            <button type="button" class="field-switch" title="打开实验田平面图">
              <span class="fs-icon"><el-icon><Grid /></el-icon></span>
              <span class="fs-text">
                <span class="fs-caption">平面图</span>
                <span class="fs-name">{{ currentField.name }}</span>
              </span>
              <el-icon class="fs-arrow"><ArrowDown /></el-icon>
            </button>
            <template #dropdown>
              <div class="field-menu-head">打开哪块实验田的平面图</div>
              <el-dropdown-menu>
                <el-dropdown-item v-for="f in fields" :key="f.id" :command="f.id">
                  <span class="fm-item">
                    <span class="fm-name">{{ f.name }}</span>
                    <span class="fm-meta">{{ f.rowCount }} 行 × {{ f.colCount }} 列{{ f.location ? ' · ' + f.location : '' }}</span>
                  </span>
                  <el-icon v-if="f.id === currentField.id" class="fm-check"><Check /></el-icon>
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </header>

      <main class="app-content">
        <!-- 平面图之间直接跳（例如从子代的地块跳到父母当季的地块）要重新加载 -->
        <RouterView v-slot="{ Component, route: r }">
          <component :is="Component" :key="r.path.startsWith('/fields/') ? r.path : undefined" />
        </RouterView>
      </main>
    </div>

    <CommandPalette v-model="paletteOpen" :items="paletteItems" />

    <el-dialog v-model="pwdOpen" title="修改密码" width="420px">
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
  --w: var(--sidebar-w);
  height: 100%;
  display: flex;
}

.layout.collapsed {
  --w: var(--sidebar-w-collapsed);
}

/* ---------------- 侧栏 ---------------- */

.aside {
  position: relative;
  width: var(--w);
  flex-shrink: 0;
  overflow: hidden;
  color: var(--nav-ink);
  display: flex;
  flex-direction: column;
  background:
    radial-gradient(120% 50% at 0% 0%, rgba(226, 182, 90, 0.09), transparent 60%),
    linear-gradient(180deg, var(--nav-bg-top) 0%, #183a2b 55%, var(--nav-bg-bottom) 100%);
  z-index: 2;
  transition: width var(--dur-slow) var(--ease);
}

/* 底部的线描麦穗，压暗一点，不抢菜单 */
.aside-art {
  position: absolute;
  left: -24px;
  bottom: 30px;
  width: 260px;
  opacity: 0.16;
  pointer-events: none;
  user-select: none;
  mask-image: linear-gradient(180deg, transparent 0%, #000 40%, #000 100%);
}

.brand {
  position: relative;
  display: flex;
  align-items: center;
  gap: 11px;
  height: var(--topbar-h);
  padding: 0 18px;
  box-sizing: border-box;
  flex-shrink: 0;
  text-decoration: none;
  border-bottom: 1px solid rgba(255, 255, 255, 0.07);
}

.brand-icon {
  width: 32px;
  height: 32px;
  border-radius: 9px;
  flex-shrink: 0;
  box-shadow: 0 0 0 1px rgba(226, 182, 90, 0.35), 0 4px 10px rgba(0, 0, 0, 0.3);
}

.brand-text {
  min-width: 0;
  white-space: nowrap;
  transition: opacity var(--dur) var(--ease);
}

.brand-title {
  font-family: var(--font-serif);
  font-size: 16px;
  font-weight: 700;
  color: #fbf3df;
  letter-spacing: 0.12em;
  line-height: 1.2;
}

.brand-sub {
  font-size: 11px;
  color: var(--nav-ink-2);
  margin-top: 2px;
  letter-spacing: 0.08em;
}

.nav {
  position: relative;
  flex: 1;
  min-height: 0;
  overflow-x: hidden;
  overflow-y: auto;
  scrollbar-width: none;
  padding: 14px 0 8px 12px;
}

.nav::-webkit-scrollbar {
  display: none;
}

.nav-group + .nav-group {
  margin-top: 14px;
}

.nav-group-title {
  height: 22px;
  padding: 0 12px;
  font-size: 11px;
  line-height: 22px;
  letter-spacing: 0.32em;
  color: rgba(214, 180, 104, 0.72);
  white-space: nowrap;
}

/* 菜单项：右边贴着侧栏边缘，选中时变成纸色书签页，和右边的页面连成一片 */
.nav-item {
  position: relative;
  display: flex;
  align-items: center;
  gap: 11px;
  height: 38px;
  margin: 2px 0;
  padding: 0 12px;
  border-radius: 10px 0 0 10px;
  color: var(--nav-ink);
  text-decoration: none;
  font-size: 14px;
  white-space: nowrap;
  transition: background var(--dur-fast), color var(--dur-fast);
}

.nav-item:hover {
  background: rgba(255, 255, 255, 0.06);
  color: #ffffff;
}

.nav-item:focus-visible {
  outline: 2px solid rgba(226, 182, 90, 0.7);
  outline-offset: -2px;
}

.nav-icon {
  font-size: 16px;
  flex-shrink: 0;
  opacity: 0.82;
}

.nav-label {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
}

.nav-badge {
  min-width: 18px;
  height: 18px;
  padding: 0 5px;
  box-sizing: border-box;
  border-radius: 9px;
  background: var(--wheat-bright);
  color: #3b2a0c;
  font-size: 11px;
  font-weight: 700;
  line-height: 18px;
  text-align: center;
  margin-right: 10px;
}

.nav-item.active {
  color: var(--forest);
  font-weight: 700;
  background: var(--paper) url('@/assets/art/paper.jpg') repeat fixed;
  background-size: 512px;
  box-shadow: -2px 2px 8px -4px rgba(0, 0, 0, 0.35);
}

.nav-item.active .nav-icon {
  color: var(--wheat);
  opacity: 1;
}

/* 书签页上下两个反向圆角，让它像从纸面里伸出来的 */
.nav-item.active::before,
.nav-item.active::after {
  content: '';
  position: absolute;
  right: 0;
  width: 12px;
  height: 12px;
  pointer-events: none;
}

.nav-item.active::before {
  top: -12px;
  background: radial-gradient(circle at 0 0, transparent 11.5px, var(--paper) 12px);
}

.nav-item.active::after {
  bottom: -12px;
  background: radial-gradient(circle at 0 100%, transparent 11.5px, var(--paper) 12px);
}

/* 用户 */
.user-dropdown {
  display: block;
  position: relative;
  flex-shrink: 0;
}

.user {
  display: flex;
  align-items: center;
  gap: 10px;
  width: calc(var(--w) - 24px);
  margin: 8px 12px 14px;
  padding: 8px 10px;
  box-sizing: border-box;
  border-radius: 12px;
  background: rgba(8, 26, 18, 0.42);
  border: 1px solid rgba(226, 182, 90, 0.14);
  backdrop-filter: blur(4px);
  cursor: pointer;
  font: inherit;
  text-align: left;
  transition: background var(--dur-fast), width var(--dur-slow) var(--ease);
}

.user:hover {
  background: rgba(8, 26, 18, 0.6);
}

.avatar {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  background: linear-gradient(160deg, #f0cf83, #c8953a);
  color: #2a2008;
  font-family: var(--font-serif);
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.user-text {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.user-name {
  color: #fbf3df;
  font-size: 13.5px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.user-role {
  color: var(--nav-ink-2);
  font-size: 11.5px;
}

.user-arrow {
  color: var(--nav-ink-2);
}

/* 收起：只留图标 */
.collapsed .brand {
  padding: 0;
  justify-content: center;
}

.collapsed .brand-text,
.collapsed .nav-label,
.collapsed .user-text,
.collapsed .user-arrow {
  display: none;
}

.collapsed .nav-group-title {
  height: 14px;
  padding: 0;
  margin-right: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.collapsed .nav-group-title span {
  display: none;
}

.collapsed .nav-group-title::before {
  content: '';
  width: 18px;
  height: 1px;
  background: rgba(214, 180, 104, 0.4);
}

.collapsed .nav-item {
  padding: 0 0 0 14px;
}

.collapsed .nav-badge {
  position: absolute;
  top: 3px;
  left: 28px;
  min-width: 8px;
  width: 8px;
  height: 8px;
  padding: 0;
  font-size: 0;
}

.collapsed .user {
  justify-content: center;
  padding: 8px 0;
}

/* ---------------- 右侧：顶栏 + 内容 ---------------- */

.main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.topbar {
  position: relative;
  z-index: 1;
  display: flex;
  align-items: center;
  gap: 10px;
  height: var(--topbar-h);
  padding: 0 20px 0 14px;
  box-sizing: border-box;
  flex-shrink: 0;
  border-bottom: 1px solid var(--line);
  background: rgba(250, 246, 237, 0.72);
  backdrop-filter: blur(8px);
}

.icon-btn {
  width: 34px;
  height: 34px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: none;
  border-radius: 9px;
  background: none;
  color: var(--ink-2);
  font-size: 17px;
  cursor: pointer;
  transition: background var(--dur-fast), color var(--dur-fast);
}

.icon-btn:hover {
  background: var(--surface-3);
  color: var(--forest);
}

.crumbs {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
  font-size: var(--fs-sm);
}

.crumb {
  color: var(--ink-3);
  white-space: nowrap;
}

.crumb.last {
  color: var(--ink);
  font-weight: 600;
}

.crumb.link {
  text-decoration: none;
}

.crumb.link:hover {
  color: var(--green);
}

.crumb-sep {
  font-size: 11px;
  color: var(--ink-4);
}

.topbar-right {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 10px;
}

.search-trigger {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  height: 34px;
  width: 236px;
  padding: 0 8px 0 12px;
  box-sizing: border-box;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: var(--surface);
  color: var(--ink-3);
  font: inherit;
  font-size: var(--fs-sm);
  cursor: pointer;
  transition: border-color var(--dur-fast), box-shadow var(--dur-fast);
}

.search-trigger:hover {
  border-color: var(--line-strong);
  box-shadow: var(--shadow-xs);
}

.st-text {
  flex: 1;
  text-align: left;
}

.st-keys {
  display: inline-flex;
  gap: 3px;
}

.field-switch {
  display: inline-flex;
  align-items: center;
  gap: 9px;
  height: 38px;
  max-width: 260px;
  padding: 0 10px 0 4px;
  box-sizing: border-box;
  border: 1px solid var(--line);
  border-radius: 11px;
  background: var(--surface);
  font: inherit;
  cursor: pointer;
  transition: border-color var(--dur-fast), box-shadow var(--dur-fast);
}

.field-switch:hover {
  border-color: #d9c48f;
  box-shadow: var(--shadow-xs);
}

.fs-icon {
  width: 28px;
  height: 28px;
  border-radius: 8px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  background: var(--forest);
  color: var(--wheat-bright);
  flex-shrink: 0;
}

.fs-text {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  min-width: 0;
  line-height: 1.2;
}

.fs-caption {
  font-size: 10.5px;
  color: var(--ink-3);
}

.fs-name {
  max-width: 180px;
  font-size: var(--fs-sm);
  font-weight: 600;
  color: var(--forest);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.fs-arrow {
  font-size: 12px;
  color: var(--ink-3);
}

.app-content {
  position: relative;
  flex: 1;
  min-height: 0;
  overflow: auto;
}

@media (max-width: 1320px) {
  .search-trigger {
    width: auto;
  }

  .st-text {
    display: none;
  }
}
.nav-ext {
  margin-left: auto;
  font-size: 12px;
  opacity: 0.55;
}
</style>

<style>
/* 下拉菜单挂在 body 下，样式不能 scoped */
.user-menu .user-menu-head,
.field-menu .field-menu-head {
  padding: 10px 14px 6px;
}

.user-menu .umh-name {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 600;
  color: var(--forest);
}

.user-menu .umh-server {
  margin-top: 4px;
  font-size: 12px;
  color: var(--ink-3);
}

.field-menu .field-menu-head {
  font-size: 12px;
  color: var(--ink-3);
}

.field-menu .el-dropdown-menu__item {
  min-width: 240px;
  justify-content: space-between;
  gap: 16px;
}

.field-menu .fm-item {
  display: flex;
  flex-direction: column;
  line-height: 1.35;
}

.field-menu .fm-name {
  font-weight: 600;
}

.field-menu .fm-meta {
  font-size: 12px;
  color: var(--ink-3);
}

.field-menu .fm-check {
  color: var(--green);
}
</style>
