<script setup lang="ts">
// 手机端登录：用老师分配的账号。登录后令牌 30 天有效，田里几天没信号也能一直录
import { computed, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { showToast } from 'vant'

import { useAuthStore } from '@/stores/auth'
import { getServerUrl, setServerUrl } from '@/shared/settings'
import { OFFLINE_EDITION } from '@/shared/edition'
import heroArt from '@/assets/art/login-hero.webp'
import appIcon from '@/assets/art/app-icon.webp'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()
const offlineEdition = ref(OFFLINE_EDITION)

const form = reactive({ username: '', password: '', serverUrl: getServerUrl() })
const showServer = ref(!localStorage.getItem('tudijiance.serverUrl'))
const loading = ref(false)
const serverToggleText = computed(() => (showServer.value ? '收起服务器设置' : `服务器：${form.serverUrl}`))

async function submit() {
  if (OFFLINE_EDITION) {
    loading.value = true
    try {
      await auth.enterLocal()
      await router.replace('/home')
    } catch (e) {
      showToast(`无法打开本机数据：${(e as Error).message}`)
    } finally {
      loading.value = false
    }
    return
  }
  if (!form.username || !form.password) {
    showToast('请输入用户名和密码')
    return
  }
  setServerUrl(form.serverUrl)
  loading.value = true
  try {
    await auth.login(form.username.trim(), form.password, 'MOBILE')
    await router.replace(auth.canRecord ? '/home' : '/blocked')
  } catch (e) {
    showToast({ message: (e as Error).message, wordBreak: 'break-all' })
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login">
    <div class="hero" :style="{ backgroundImage: `url(${heroArt})` }">
      <div class="shade" />
      <div class="brand">
        <img :src="appIcon" alt="" class="icon" />
        <div>
          <div class="name">小麦实验田</div>
          <div class="sub">{{ offlineEdition ? '离线版 · 无需账号和服务器' : '田间录入 · 没信号也能录' }}</div>
        </div>
      </div>
    </div>

    <div class="card m-card">
      <div class="title m-title">{{ offlineEdition ? '本机使用' : '登录' }}</div>
      <p v-if="offlineEdition" class="local-intro">不用联网，点一下即可进入。<br>实验田和录入记录保存在这台手机上。</p>
      <van-notice-bar v-if="!offlineEdition && route.query.expired" wrapable :scrollable="false" class="expired"
        text="登录已过期，请重新登录。手机上没传的数据还在，登录后会接着上传。" />
      <van-cell-group v-if="!offlineEdition" inset class="fields">
        <van-field v-model="form.username" label="用户名" placeholder="学号或工号" autocomplete="username" clearable />
        <van-field v-model="form.password" type="password" label="密码" placeholder="请输入密码" autocomplete="current-password" />
        <van-field v-if="showServer" v-model="form.serverUrl" label="服务器" placeholder="http://服务器地址:8080" clearable />
      </van-cell-group>
      <van-button type="primary" block round size="large" :loading="loading" class="submit" @click="submit">{{ offlineEdition ? '一键进入' : '登 录' }}</van-button>
      <div v-if="!offlineEdition" class="server-toggle" @click="showServer = !showServer">
        <van-icon name="setting-o" />{{ serverToggleText }}
      </div>
    </div>
    <div class="foot">按 NY/T 1301—2025 记载</div>
  </div>
</template>

<style scoped>
.login {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
}

.hero {
  position: relative;
  height: 38vh;
  min-height: 230px;
  background-size: cover;
  background-position: center 30%;
}

.shade {
  position: absolute;
  inset: 0;
  background: linear-gradient(180deg, rgba(14, 36, 26, 0.35) 0%, rgba(14, 36, 26, 0.1) 40%, rgba(244, 239, 227, 1) 100%);
}

.brand {
  position: absolute;
  left: 22px;
  top: calc(28px + env(safe-area-inset-top));
  display: flex;
  align-items: center;
  gap: 12px;
  color: #fbf3df;
}

.icon {
  width: 46px;
  height: 46px;
  border-radius: 12px;
  box-shadow: 0 4px 14px rgba(0, 0, 0, 0.35);
}

.name {
  font-family: var(--font-serif);
  font-weight: 700;
  font-size: 22px;
  letter-spacing: 3px;
  text-shadow: 0 2px 10px rgba(0, 0, 0, 0.45);
}

.sub {
  font-size: 12.5px;
  letter-spacing: 1px;
  opacity: 0.9;
  text-shadow: 0 1px 6px rgba(0, 0, 0, 0.5);
}

.card {
  position: relative;
  margin: -70px 16px 0;
  padding: 22px 6px 18px;
}

.title {
  font-size: 22px;
  text-align: center;
  margin-bottom: 14px;
}

.local-intro {
  margin: 12px 16px 22px;
  text-align: center;
  line-height: 1.9;
  color: var(--ink-3);
}

.expired {
  margin: 0 10px 12px;
  border-radius: 10px;
}

.fields {
  margin: 0 4px;
  border: 1px solid var(--line);
}

.submit {
  margin: 18px 14px 0;
  width: calc(100% - 28px);
  letter-spacing: 8px;
}

.server-toggle {
  margin-top: 14px;
  text-align: center;
  font-size: 12.5px;
  color: var(--ink-3);
  display: flex;
  justify-content: center;
  align-items: center;
  gap: 4px;
}

.foot {
  margin: auto 0 calc(18px + env(safe-area-inset-bottom));
  padding-top: 24px;
  text-align: center;
  font-size: 12px;
  color: var(--ink-3);
}
</style>
