import { createRouter, createWebHashHistory } from 'vue-router'

import { setUnauthorizedHandler } from '@/shared/api'
import { useAuthStore } from '@/stores/auth'
import AppLayout from '@/desktop/AppLayout.vue'
import LoginView from '@/desktop/views/LoginView.vue'

// 打包后用 file:// 打开，所以用 hash 路由
const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/login', name: 'login', component: LoginView },
    {
      path: '/',
      component: AppLayout,
      redirect: '/fields',
      children: [
        { path: 'fields', name: 'fields', component: () => import('@/desktop/views/FieldsView.vue') },
        { path: 'fields/:id', name: 'field-editor', component: () => import('@/desktop/views/FieldEditorView.vue') },
        { path: 'varieties', name: 'varieties', component: () => import('@/desktop/views/VarietiesView.vue') },
        { path: 'traits', name: 'traits', component: () => import('@/desktop/views/TraitsView.vue') },
        { path: 'devices', name: 'devices', component: () => import('@/desktop/views/DevicesView.vue') },
      ],
    },
  ],
})

router.beforeEach((to) => {
  const auth = useAuthStore()
  if (to.name !== 'login' && !auth.loggedIn) {
    return { name: 'login' }
  }
})

setUnauthorizedHandler(() => {
  useAuthStore().logout()
  router.replace({ name: 'login' })
})

export default router
