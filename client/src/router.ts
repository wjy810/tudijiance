import { createRouter, createWebHashHistory } from 'vue-router'

import { setUnauthorizedHandler, type Role } from '@/shared/api'
import { useAuthStore } from '@/stores/auth'
import AppLayout from '@/desktop/AppLayout.vue'
import LoginView from '@/desktop/views/LoginView.vue'

declare module 'vue-router' {
  interface RouteMeta {
    /** 能打开这个页面的角色，不写表示登录了就能看 */
    roles?: Role[]
  }
}

const RECORDERS: Role[] = ['TEACHER', 'STUDENT']

// 打包后用 file:// 打开，所以用 hash 路由
const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/login', name: 'login', component: LoginView },
    { path: '/screen', name: 'screen', component: () => import('@/desktop/views/ScreenView.vue') },
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
          path: 'data', name: 'data', component: () => import('@/desktop/views/DataView.vue'),
          meta: { roles: RECORDERS },
        },
        {
          path: 'stats', name: 'stats', component: () => import('@/desktop/views/StatsView.vue'),
          meta: { roles: RECORDERS },
        },
        {
          path: 'varieties', name: 'varieties', component: () => import('@/desktop/views/VarietiesView.vue'),
          meta: { roles: RECORDERS },
        },
        {
          path: 'import', name: 'import', component: () => import('@/desktop/views/ImportView.vue'),
          meta: { roles: ['TEACHER'] },
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
          meta: { roles: ['SYS_ADMIN'] },
        },
      ],
    },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
})

router.beforeEach((to) => {
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
})

setUnauthorizedHandler(() => {
  useAuthStore().logout()
  router.replace({ name: 'login' })
})

export default router
