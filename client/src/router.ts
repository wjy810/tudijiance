import { createRouter, createWebHashHistory } from 'vue-router'

import { setUnauthorizedHandler, type Role } from '@/shared/api'
import { useAuthStore } from '@/stores/auth'
import { useModuleStore } from '@/stores/modules'
import { ElMessage } from 'element-plus'
import AppLayout from '@/desktop/AppLayout.vue'
import LoginView from '@/desktop/views/LoginView.vue'

declare module 'vue-router' {
  interface RouteMeta {
    /** 能打开这个页面的角色，不写表示登录了就能看 */
    roles?: Role[]
    /** 属于哪个功能模块：模块停用时打不开 */
    module?: string
  }
}

const RECORDERS: Role[] = ['TEACHER', 'STUDENT']

// 打包后用 file:// 打开，所以用 hash 路由
const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/login', name: 'login', component: LoginView },
    { path: '/screen', name: 'screen', component: () => import('@/desktop/views/ScreenView.vue'), meta: { module: 'screen' } },
    {
      path: '/',
      component: AppLayout,
      children: [
        { path: '', name: 'home', redirect: () => useAuthStore().homePath },
        {
          path: 'home', name: 'home-dashboard', component: () => import('@/desktop/views/HomeView.vue'),
          meta: { roles: RECORDERS },
        },
        { path: 'fields', redirect: '/home' },
        {
          path: 'fields/:id', name: 'field-map', component: () => import('@/desktop/views/FieldMapView.vue'),
          meta: { roles: RECORDERS },
        },
        {
          path: 'trials/:id/book', name: 'trial-book', component: () => import('@/desktop/views/RecordBookView.vue'),
          meta: { roles: RECORDERS, module: 'record_book' },
        },
        {
          path: 'data', name: 'data', component: () => import('@/desktop/views/DataView.vue'),
          meta: { roles: RECORDERS, module: 'data' },
        },
        {
          path: 'stats', name: 'stats', component: () => import('@/desktop/views/StatsView.vue'),
          meta: { roles: RECORDERS, module: 'stats' },
        },
        {
          path: 'analysis', name: 'analysis', component: () => import('@/desktop/views/AnalysisView.vue'),
          meta: { roles: RECORDERS, module: 'analysis' },
        },
        {
          path: 'varieties', name: 'varieties', component: () => import('@/desktop/views/VarietiesView.vue'),
          meta: { roles: RECORDERS },
        },
        {
          path: 'import', name: 'import', component: () => import('@/desktop/views/ImportView.vue'),
          meta: { roles: ['TEACHER'], module: 'import' },
        },
        {
          path: 'traits', name: 'traits', component: () => import('@/desktop/views/TraitsView.vue'),
          meta: { roles: RECORDERS },
        },
        {
          path: 'users', name: 'users', component: () => import('@/desktop/views/UsersView.vue'),
          meta: { roles: ['SYS_ADMIN'] },
        },
        {
          path: 'devices', name: 'devices', component: () => import('@/desktop/views/DevicesView.vue'),
          meta: { roles: ['SYS_ADMIN'], module: 'devices' },
        },
        {
          path: 'modules', name: 'modules', component: () => import('@/desktop/views/ModulesView.vue'),
          meta: { roles: ['SYS_ADMIN'] },
        },
      ],
    },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
})

router.beforeEach(async (to) => {
  const auth = useAuthStore()
  if (to.name === 'login') {
    return
  }
  if (!auth.loggedIn) {
    return { name: 'login' }
  }
  const roles = to.meta.roles
  if (roles && auth.role && !roles.includes(auth.role)) {
    return auth.homePath
  }
  const modules = useModuleStore()
  await modules.load()
  // 停用的模块打不开；游客的首页就是大屏，留在大屏页面上显示"已停用"
  if (to.meta.module && !modules.isOn(to.meta.module) && !(auth.isGuest && to.name === 'screen')) {
    ElMessage.warning(`“${modules.nameOf(to.meta.module)}”已被系统管理员停用`)
    return auth.homePath
  }
})

setUnauthorizedHandler(() => {
  useAuthStore().logout()
  router.replace({ name: 'login' })
})

export default router
