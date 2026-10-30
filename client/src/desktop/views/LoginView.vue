<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Lock, Setting, User } from '@element-plus/icons-vue'

import { useAuthStore } from '@/stores/auth'
import { getServerUrl, setServerUrl } from '@/shared/settings'
import BrandMark from '@/desktop/components/BrandMark.vue'

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

// 左侧装饰：一块实验田的网格，颜色表示不同状态
const COLORS = ['#3e8f63', '#4f9f72', '#5aa97c', '#e6c36a', '#3e8f63', '#d9a441', '#4f9f72', '#6fb58c']
const cells = computed(() =>
  Array.from({ length: 6 * 9 }, (_, i) => ({
    i,
    color: COLORS[(i * 7 + Math.floor(i / 9) * 3) % COLORS.length],
    delay: `${(i % 9) * 60 + Math.floor(i / 9) * 90}ms`,
  })),
)
</script>

<template>
  <div class="login-page">
    <section class="hero">
      <div class="hero-brand">
        <BrandMark :size="40" color="#e6c36a" />
        <div>
          <div class="hero-name">小麦实验田</div>
          <div class="hero-sub">品种试验数据平台</div>
        </div>
      </div>

      <div class="field-art" aria-hidden="true">
        <div v-for="c in cells" :key="c.i" class="plot" :style="{ background: c.color, animationDelay: c.delay }" />
      </div>

      <div class="hero-copy">
        <h1>在平面图上记录每一个小区</h1>
        <ul>
          <li>选中小区直接录入，生育期、病害、产量按国家标准记载</li>
          <li>自动按品种汇总，算出比对照增减和位次</li>
          <li>历年数据对比，数据大屏实时展示</li>
        </ul>
      </div>
      <div class="hero-foot">按 NY/T 1301—2025《农作物品种试验规范 小麦》记载</div>
    </section>

    <section class="form-side">
      <div class="form-box">
        <h2>登录</h2>
        <p class="muted">用老师分配的账号登录</p>
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
  background: var(--app-surface);
}

.hero {
  flex: 1.15;
  background: radial-gradient(circle at 20% 15%, #24573c 0%, #17402d 45%, #102e20 100%);
  color: #e5efe8;
  padding: 44px 56px;
  display: flex;
  flex-direction: column;
  position: relative;
  overflow: hidden;
  min-width: 0;
}

.hero-brand {
  display: flex;
  align-items: center;
  gap: 12px;
}

.hero-name {
  font-size: 22px;
  font-weight: 600;
  color: #fff;
  letter-spacing: 2px;
}

.hero-sub {
  font-size: 13px;
  color: #9fc1ad;
}

.field-art {
  margin: 56px 0 40px;
  display: grid;
  grid-template-columns: repeat(9, 1fr);
  gap: 6px;
  max-width: 520px;
  transform: perspective(900px) rotateX(28deg) rotateZ(-6deg);
  transform-origin: left center;
}

.plot {
  aspect-ratio: 1.6;
  border-radius: 4px;
  opacity: 0;
  animation: grow 0.6s ease-out forwards;
}

@keyframes grow {
  from {
    opacity: 0;
    transform: translateY(8px);
  }
  to {
    opacity: 0.92;
    transform: none;
  }
}

.hero-copy h1 {
  font-size: 30px;
  font-weight: 600;
  color: #fff;
  margin: 0 0 18px;
  letter-spacing: 1px;
}

.hero-copy ul {
  margin: 0;
  padding: 0;
  list-style: none;
}

.hero-copy li {
  padding: 6px 0 6px 22px;
  position: relative;
  color: #c9dccf;
  font-size: 15px;
}

.hero-copy li::before {
  content: '';
  position: absolute;
  left: 0;
  top: 14px;
  width: 8px;
  height: 8px;
  border-radius: 2px;
  background: #e6c36a;
}

.hero-foot {
  margin-top: auto;
  font-size: 12px;
  color: #7fa58f;
}

.form-side {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 40px;
  min-width: 420px;
}

.form-box {
  width: 360px;
}

.form-box h2 {
  margin: 0;
  font-size: 26px;
  font-weight: 600;
}

.form-box > .muted {
  margin: 8px 0 28px;
}

.submit {
  width: 100%;
  margin-top: 8px;
  height: 44px;
  font-size: 16px;
}

.server-toggle {
  margin-top: 18px;
  border: none;
  background: none;
  color: var(--app-text-muted);
  font-size: 12px;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 0;
}

.server-toggle:hover {
  color: var(--app-accent);
}
</style>
