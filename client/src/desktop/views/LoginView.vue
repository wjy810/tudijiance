<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Lock, Setting, User } from '@element-plus/icons-vue'

import { useAuthStore } from '@/stores/auth'
import { getServerUrl, setServerUrl } from '@/shared/settings'
import heroArt from '@/assets/art/login-hero.webp'
import appIcon from '@/assets/art/app-icon.webp'
import divider from '@/assets/art/divider-wheat.webp'

const auth = useAuthStore()
const router = useRouter()

const SERVER_HINT = 'http://服务器地址:8080'
const form = reactive({ username: '', password: '', serverUrl: getServerUrl() })
const loading = ref(false)
// 服务器地址一般只在第一次用时设置，平时收起来
const showServer = ref(false)

async function submit() {
  if (!form.username || !form.password) {
    ElMessage.warning('请输入用户名和密码')
    return
  }
  setServerUrl(form.serverUrl)
  loading.value = true
  try {
    await auth.login(form.username, form.password)
    router.replace(auth.homePath)
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    loading.value = false
  }
}

const FEATURES = [
  { title: '点选小区就能录', desc: '生育期、病害、产量按国家标准记载，能选的都做成按钮' },
  { title: '历史表格一键导入', desc: '按行按列对应字段，和录入项目自动对上' },
  { title: '自己选字段做图表', desc: '折线、柱状、饼图，品种对比、历年变化一目了然' },
]
</script>

<template>
  <div class="login-page">
    <section class="hero" :style="{ backgroundImage: `url(${heroArt})` }">
      <div class="hero-shade" />
      <div class="hero-brand">
        <img :src="appIcon" alt="" class="hero-icon" />
        <div>
          <div class="hero-name">小麦实验田</div>
          <div class="hero-sub">品种试验数据平台</div>
        </div>
      </div>

      <div class="hero-copy">
        <div class="eyebrow">WHEAT VARIETY TRIALS</div>
        <h1>一块田，<br />每一个小区都有记录</h1>
        <div class="features">
          <div v-for="(f, i) in FEATURES" :key="f.title" class="feature">
            <span class="feature-no">0{{ i + 1 }}</span>
            <div>
              <div class="feature-title">{{ f.title }}</div>
              <div class="feature-desc">{{ f.desc }}</div>
            </div>
          </div>
        </div>
      </div>
      <div class="hero-foot">按 NY/T 1301—2025《农作物品种试验与信息化技术规程 小麦》记载</div>
    </section>

    <section class="form-side">
      <div class="form-box">
        <h2>欢迎回来</h2>
        <img :src="divider" alt="" class="divider" />
        <p class="lead">用老师分配的账号登录</p>
        <el-form label-position="top" size="large" @submit.prevent="submit">
          <el-form-item label="用户名">
            <el-input v-model="form.username" :prefix-icon="User" autocomplete="username" placeholder="例如学号或工号" />
          </el-form-item>
          <el-form-item label="密码">
            <el-input v-model="form.password" :prefix-icon="Lock" type="password" show-password autocomplete="current-password" />
          </el-form-item>
          <el-collapse-transition>
            <el-form-item v-show="showServer" label="服务器地址">
              <el-input v-model="form.serverUrl" :placeholder="SERVER_HINT" />
            </el-form-item>
          </el-collapse-transition>
          <el-button type="primary" native-type="submit" :loading="loading" class="submit">登 录</el-button>
        </el-form>
        <button type="button" class="server-toggle" @click="showServer = !showServer">
          <el-icon><Setting /></el-icon>
          {{ showServer ? '收起服务器设置' : `服务器设置（当前 ${form.serverUrl}）` }}
        </button>
      </div>
    </section>
  </div>
</template>

<style scoped>
.login-page {
  height: 100%;
  display: flex;
}

.hero {
  position: relative;
  flex: 1.2;
  min-width: 0;
  overflow: hidden;
  padding: 44px 56px;
  display: flex;
  flex-direction: column;
  color: #f6efdc;
  background-size: cover;
  background-position: center 30%;
}

/* 从左下的深绿渐到透明，文字压得住，又能看到麦田 */
.hero-shade {
  position: absolute;
  inset: 0;
  background:
    linear-gradient(180deg, rgba(14, 36, 26, 0.55) 0%, rgba(14, 36, 26, 0.05) 30%, rgba(14, 36, 26, 0.15) 55%, rgba(12, 30, 22, 0.92) 100%),
    linear-gradient(90deg, rgba(12, 30, 22, 0.55) 0%, rgba(12, 30, 22, 0) 60%);
}

.hero > *:not(.hero-shade) {
  position: relative;
}

.hero-brand {
  display: flex;
  align-items: center;
  gap: 14px;
}

.hero-icon {
  width: 48px;
  height: 48px;
  border-radius: 12px;
  box-shadow: 0 0 0 1px rgba(226, 182, 90, 0.4), 0 6px 18px rgba(0, 0, 0, 0.35);
}

.hero-name {
  font-family: var(--font-serif);
  font-size: 24px;
  font-weight: 700;
  letter-spacing: 3px;
  text-shadow: 0 2px 10px rgba(0, 0, 0, 0.4);
}

.hero-sub {
  margin-top: 3px;
  font-size: 13px;
  letter-spacing: 2px;
  color: #e6d7ad;
}

.hero-copy {
  margin-top: auto;
  max-width: 560px;
}

.eyebrow {
  font-size: 11px;
  letter-spacing: 5px;
  color: var(--wheat-bright);
  margin-bottom: 12px;
}

h1 {
  margin: 0 0 26px;
  font-family: var(--font-serif);
  font-weight: 700;
  font-size: 40px;
  line-height: 1.35;
  letter-spacing: 2px;
  text-shadow: 0 2px 18px rgba(0, 0, 0, 0.45);
}

.features {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.feature {
  display: flex;
  gap: 14px;
  align-items: flex-start;
}

.feature-no {
  font-family: var(--font-serif);
  font-weight: 700;
  font-size: 15px;
  color: var(--wheat-bright);
  padding-top: 1px;
  width: 22px;
}

.feature-title {
  font-weight: 600;
  font-size: 15px;
}

.feature-desc {
  margin-top: 2px;
  font-size: 13px;
  color: rgba(246, 239, 220, 0.72);
}

.hero-foot {
  margin-top: 34px;
  font-size: 12px;
  color: rgba(246, 239, 220, 0.55);
}

.form-side {
  flex: 1;
  min-width: 420px;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 40px;
  background: radial-gradient(90% 70% at 70% 20%, rgba(255, 253, 248, 0.9), rgba(244, 239, 227, 0));
}

.form-box {
  width: 380px;
  padding: 38px 36px 30px;
  border-radius: 20px;
  background: var(--surface);
  border: 1px solid var(--line);
  box-shadow: var(--shadow-lift);
}

h2 {
  margin: 0;
  text-align: center;
  font-family: var(--font-serif);
  font-size: 26px;
  font-weight: 700;
  color: var(--forest);
  letter-spacing: 3px;
}

.divider {
  display: block;
  width: 220px;
  margin: 10px auto 6px;
  opacity: 0.9;
}

.lead {
  margin: 0 0 22px;
  text-align: center;
  color: var(--ink-2);
  font-size: 13px;
}

.submit {
  width: 100%;
  height: 46px;
  margin-top: 8px;
  font-size: 16px;
  letter-spacing: 6px;
  border-radius: 10px;
}

.server-toggle {
  display: flex;
  align-items: center;
  gap: 5px;
  margin: 18px auto 0;
  padding: 0;
  border: none;
  background: none;
  font-size: 12px;
  color: var(--ink-3);
  cursor: pointer;
}

.server-toggle:hover {
  color: var(--green);
}
</style>
