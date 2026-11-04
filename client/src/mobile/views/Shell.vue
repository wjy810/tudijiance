<script setup lang="ts">
// 有标签栏的几个页面的外框：顶上提示离线、登录过期，底下是首页、平面图、记录、我的
import { computed, onMounted, watch } from 'vue'

import { useSyncStore } from '../stores/sync'
import { useFieldStore } from '../stores/field'
import { timeAgo } from '../display'
import { OFFLINE_EDITION } from '@/shared/edition'

const sync = useSyncStore()
const field = useFieldStore()

// 传上去以后，重新取小区状态（今天录过的绿点）和还没传的小区（橙点）
watch(() => sync.syncedTick, () => {
  if (field.trialId) void field.loadTrial().catch(() => {})
})

onMounted(() => {
  sync.start()
  if (field.fields.length === 0) {
    void field.load()
  }
})

const notice = computed(() => {
  if (OFFLINE_EDITION) return '离线版 · 数据保存在本机'
  if (sync.needLogin) return '登录已过期，到"我的"里退出再登录，没传的数据会接着上传'
  if (!sync.online || field.offlineSince) {
    const when = field.offlineSince ? `，看到的是${timeAgo(field.offlineSince)}的数据` : ''
    return `没有网络，录入的数据先存在手机上${when}`
  }
  return ''
})
const badge = computed(() => (sync.pending + sync.failed > 0 ? sync.pending + sync.failed : ''))
</script>

<template>
  <div class="shell">
    <van-notice-bar v-if="notice" left-icon="info-o" :text="notice" wrapable :scrollable="false" class="notice" />
    <RouterView v-slot="{ Component }">
      <keep-alive>
        <component :is="Component" />
      </keep-alive>
    </RouterView>
    <van-tabbar route safe-area-inset-bottom>
      <van-tabbar-item to="/home" icon="home-o">首页</van-tabbar-item>
      <van-tabbar-item to="/map" icon="apps-o">平面图</van-tabbar-item>
      <van-tabbar-item to="/records" icon="notes-o" :badge="badge">记录</van-tabbar-item>
      <van-tabbar-item to="/me" icon="user-o">我的</van-tabbar-item>
    </van-tabbar>
  </div>
</template>

<style scoped>
.shell {
  min-height: 100vh;
}

.notice {
  position: sticky;
  top: 0;
  z-index: 20;
  font-size: 13px;
}
</style>
