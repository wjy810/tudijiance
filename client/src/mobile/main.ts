// 手机端入口：Vant 组件 + 麦田手记主题。在安卓上由 Capacitor 打包成 APP
import 'vant/lib/index.css'
import '@/fonts.css'
import './mobile.css'

import { createApp } from 'vue'
import { createPinia } from 'pinia'
import Vant from 'vant'
import { Capacitor } from '@capacitor/core'
import { StatusBar, Style } from '@capacitor/status-bar'

import App from './App.vue'
import router from './router'

const app = createApp(App)
app.use(createPinia())
app.use(router)
app.use(Vant)
app.mount('#app')

if (Capacitor.isNativePlatform()) {
  void StatusBar.setBackgroundColor({ color: '#1c4231' }).catch(() => {})
  void StatusBar.setStyle({ style: Style.Dark }).catch(() => {})
}
