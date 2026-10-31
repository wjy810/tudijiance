<script setup lang="ts">
// 我的：账号、服务器地址、下载离线数据、退出登录
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { showConfirmDialog, showLoadingToast, showToast, closeToast } from 'vant'

import { useAuthStore } from '@/stores/auth'
import { ROLE } from '@/shared/labels'
import { getServerUrl, setServerUrl } from '@/shared/settings'
import { useSyncStore } from '../stores/sync'
import { useFieldStore } from '../stores/field'
import { downloadForOffline } from '../repo'
import { db } from '../db'
import { timeAgo } from '../display'

const auth = useAuthStore()
const sync = useSyncStore()
const field = useFieldStore()
const router = useRouter()

// 版本号在打包时由 Vite 写进来（package.json 的 version）
const APP_VERSION = __APP_VERSION__
const DOWNLOAD_KEY = 'tudijiance.mobile.downloadedAt'
const downloadedAt = ref(Number(localStorage.getItem(DOWNLOAD_KEY)) || null)
const server = ref(getServerUrl())
const serverOpen = ref(false)
const serverDraft = ref('')

async function download() {
  const toast = showLoadingToast({ message: '正在下载…', forbidClick: true, duration: 0 })
  try {
    const n = await downloadForOffline((text) => (toast.message = `正在下载\n${text}`))
    downloadedAt.value = Date.now()
    localStorage.setItem(DOWNLOAD_KEY, String(downloadedAt.value))
    await field.load()
    closeToast()
    showToast({ type: 'success', message: `下载好了（${n} 项），没信号也能用` })
  } catch (e) {
    closeToast()
    showToast((e as Error).message)
  }
}

function editServer() {
  serverDraft.value = server.value
  serverOpen.value = true
}

function saveServer() {
  setServerUrl(serverDraft.value)
  server.value = getServerUrl()
  showToast('已保存，下次请求用新的地址')
  void field.load()
}

async function clearDone() {
  await db.outbox.where('status').equals('done').delete()
  showToast('已清除上传过的记录')
}

async function logout() {
  const left = sync.pending + sync.failed
  try {
    await showConfirmDialog({
      title: '退出登录？',
      message: left ? `还有 ${left} 条没传上去，会留在手机上，下次用这个账号登录后接着上传。` : '退出后要重新输入密码。',
    })
  } catch {
    return
  }
  auth.logout()
  void router.replace('/login')
}
</script>

<template>
  <div class="m-page me">
    <section class="m-card profile">
      <div class="avatar serif">{{ (auth.user?.displayName || auth.user?.username || '?').slice(0, 1) }}</div>
      <div>
        <div class="name m-title">{{ auth.user?.displayName || auth.user?.username }}</div>
        <div class="m-muted">{{ auth.role ? ROLE[auth.role] : '' }} · {{ auth.user?.username }}</div>
      </div>
    </section>

    <van-cell-group inset title="数据" class="group">
      <van-cell title="下载离线数据" :label="`去田里之前联网点一下。上次：${timeAgo(downloadedAt)}`" is-link icon="down" @click="download" />
      <van-cell title="录入记录" :value="sync.pending + sync.failed ? `${sync.pending + sync.failed} 条没传` : '全部已上传'" is-link icon="notes-o" @click="router.push('/records')" />
      <van-cell title="清除上传过的记录" label="只清手机上的列表，服务器上的数据不动" icon="delete-o" is-link @click="clearDone" />
    </van-cell-group>

    <van-cell-group inset title="设置" class="group">
      <van-cell title="服务器地址" :label="server" is-link icon="setting-o" @click="editServer" />
    </van-cell-group>

    <van-button block round plain type="danger" class="logout" @click="logout">退出登录</van-button>
    <div class="version m-muted">小麦实验田 手机端 {{ APP_VERSION }}</div>

    <van-dialog v-model:show="serverOpen" title="服务器地址" show-cancel-button @confirm="saveServer">
      <van-field v-model="serverDraft" placeholder="http://服务器地址:8080" clearable class="server-field" />
    </van-dialog>
  </div>
</template>

<style scoped>
.profile {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 18px 16px;
}

.avatar {
  width: 54px;
  height: 54px;
  border-radius: 50%;
  background: linear-gradient(160deg, #f0cf83, #c8953a);
  color: #2a2008;
  font-size: 24px;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
}

.name {
  font-size: 20px;
}

.group {
  margin: 8px 0 0;
  border: 1px solid var(--line);
}

.logout {
  margin-top: 26px;
}

.version {
  margin-top: 14px;
  text-align: center;
  font-size: 12px;
}

.server-field {
  margin: 8px 0;
}
</style>
