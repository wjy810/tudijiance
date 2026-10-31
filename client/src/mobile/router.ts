// 手机端路由。老师、学生用手机录入；系统管理员、游客在手机上没有要做的事
import { createRouter, createWebHashHistory } from 'vue-router'

import { setUnauthorizedHandler } from '@/shared/api'
import { useAuthStore } from '@/stores/auth'
// 页面都直接打包进来、不按需加载：APP 里没网时也不会出现"页面加载失败"
import Shell from './views/Shell.vue'
import LoginView from './views/LoginView.vue'
import BlockedView from './views/BlockedView.vue'
import HomeView from './views/HomeView.vue'
import MapView from './views/MapView.vue'
import RecordsView from './views/RecordsView.vue'
import MeView from './views/MeView.vue'
import PlotView from './views/PlotView.vue'

const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/login', name: 'login', component: LoginView },
    { path: '/blocked', name: 'blocked', component: BlockedView },
    {
      path: '/',
      component: Shell,
      children: [
        { path: '', redirect: '/home' },
        { path: 'home', name: 'home', component: HomeView },
        { path: 'map', name: 'map', component: MapView },
        { path: 'records', name: 'records', component: RecordsView },
        { path: 'me', name: 'me', component: MeView },
      ],
    },
    { path: '/plot/:trialId/:plotId', name: 'plot', component: PlotView },
    { path: '/:pathMatch(.*)*', redirect: '/home' },
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
  if (!auth.canRecord && to.name !== 'blocked') {
    return { name: 'blocked' }
  }
})

// 登录过期：回到登录页。本机的待上传数据不动，重新登录后接着传
setUnauthorizedHandler(() => {
  useAuthStore().logout()
  void router.replace({ name: 'login', query: { expired: '1' } })
})

export default router
