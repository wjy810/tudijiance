<script setup lang="ts">
// 我的：账号、服务器地址、下载离线数据、退出登录
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { showConfirmDialog, showLoadingToast, showToast, closeToast } from 'vant'

import { useAuthStore } from '@/stores/auth'
import { correctionApi, type CorrectionStatus, type CorrectionSummary, type CorrectionView } from '@/shared/api'
import { ROLE } from '@/shared/labels'
import { getServerUrl, setServerUrl } from '@/shared/settings'
import { OFFLINE_EDITION } from '@/shared/edition'
import { createLocalField } from '../local'
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
const localFieldOpen = ref(false)
const localField = reactive({ name: '', rowCount: 4, colCount: 6, varieties: '' })

async function saveLocalField(action: string): Promise<boolean> {
  if (action !== 'confirm') return true
  try {
    const created = await createLocalField({
      name: localField.name, rowCount: Number(localField.rowCount), colCount: Number(localField.colCount),
      varietyNames: localField.varieties.split(/[,，、\n]/),
    })
    await field.load()
    await field.selectField(created.id)
    showToast('实验田已保存在本机，可以开始录入')
    await router.push('/map')
    return true
  } catch (e) {
    showToast((e as Error).message)
    return false
  }
}

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

// ---------- 我的更正申请（学生，要联网） ----------
const showCorrections = computed(() => !OFFLINE_EDITION && auth.role === 'STUDENT')
const corrSummary = ref<CorrectionSummary | null>(null)
const corrOpen = ref(false)
const corrList = ref<CorrectionView[]>([])
const corrLoading = ref(false)
const CORR_STATUS: Record<CorrectionStatus, { label: string; type: 'warning' | 'success' | 'danger' | 'default' }> = {
  PENDING: { label: '待审核', type: 'warning' },
  APPROVED: { label: '已通过', type: 'success' },
  REJECTED: { label: '已驳回', type: 'danger' },
  WITHDRAWN: { label: '已撤回', type: 'default' },
}
const statusOf = (c: CorrectionView) => CORR_STATUS[c.status]
const corrValue = computed(() => {
  const c = corrSummary.value
  if (!c) return ''
  return [c.pending ? `${c.pending} 个待审核` : '', c.reviewedRecently ? `${c.reviewedRecently} 个有结果` : ''].filter(Boolean).join('，')
})

onMounted(async () => {
  if (!showCorrections.value) return
  corrSummary.value = await correctionApi.summary().catch(() => null)
})

async function openCorrections() {
  corrOpen.value = true
  corrLoading.value = true
  try {
    corrList.value = await correctionApi.list()
  } catch (e) {
    showToast((e as Error).message)
  } finally {
    corrLoading.value = false
  }
}

async function withdrawCorrection(c: CorrectionView) {
  try {
    await showConfirmDialog({ title: '撤回这个申请？', message: '撤回后可以重新申请。' })
  } catch {
    return
  }
  try {
    await correctionApi.withdraw(c.id)
    showToast('已撤回')
    await openCorrections()
    corrSummary.value = await correctionApi.summary().catch(() => null)
  } catch (e) {
    showToast((e as Error).message)
  }
}

async function logout() {
  const left = sync.pending + sync.failed
  try {
    await showConfirmDialog({
      title: '退出登录？',
      message: OFFLINE_EDITION ? '本机记录会保留，下次点“一键进入”即可继续使用。' : left ? `还有 ${left} 条没传上去，会留在手机上，下次用这个账号登录后接着上传。` : '退出后要重新输入密码。',
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
        <div class="m-muted">{{ OFFLINE_EDITION ? '离线版 · 无需账号' : `${auth.role ? ROLE[auth.role] : ''} · ${auth.user?.username}` }}</div>
      </div>
    </section>

    <van-cell-group inset title="数据" class="group">
      <van-cell v-if="OFFLINE_EDITION" title="新建本机实验田" label="填写名称、行列和品种，直接开始录入" is-link icon="plus" @click="localFieldOpen = true" />
      <van-cell v-else title="下载离线数据" :label="`去田里之前联网点一下。上次：${timeAgo(downloadedAt)}`" is-link icon="down" @click="download" />
      <van-cell title="录入记录" :value="OFFLINE_EDITION ? '已存本机' : sync.pending + sync.failed ? `${sync.pending + sync.failed} 条没传` : '全部已上传'" is-link icon="notes-o" @click="router.push('/records')" />
      <van-cell v-if="showCorrections" title="我的更正申请" :value="corrValue" label="发现录错了，在小区详情里点“更正”" is-link icon="edit" @click="openCorrections" />
      <van-cell v-if="OFFLINE_EDITION" title="本机保存" label="记录不会自动清理。卸载应用或清除应用数据会删除本机记录。" icon="info-o" />
      <van-cell v-else title="清除上传过的记录" label="只清手机上的列表，服务器上的数据不动" icon="delete-o" is-link @click="clearDone" />
    </van-cell-group>

    <van-cell-group v-if="!OFFLINE_EDITION" inset title="设置" class="group">
      <van-cell title="服务器地址" :label="server" is-link icon="setting-o" @click="editServer" />
    </van-cell-group>

    <van-button block round plain type="danger" class="logout" @click="logout">退出登录</van-button>
    <div class="version m-muted">小麦实验田 {{ OFFLINE_EDITION ? '离线版' : '手机端' }} {{ APP_VERSION }}</div>

    <van-dialog v-model:show="localFieldOpen" title="新建本机实验田" show-cancel-button confirm-button-text="创建" :before-close="saveLocalField">
      <van-field v-model="localField.name" label="名称" placeholder="例如：一号实验田" maxlength="60" />
      <van-field v-model="localField.rowCount" label="行数" type="digit" placeholder="1—30" />
      <van-field v-model="localField.colCount" label="列数" type="digit" placeholder="1—30" />
      <van-field v-model="localField.varieties" label="品种" type="textarea" placeholder="可选，多个品种用逗号分隔" rows="2" maxlength="600" />
      <p class="local-hint m-muted">品种按填写顺序循环分配到各小区；留空也可录入。</p>
    </van-dialog>

    <van-dialog v-model:show="serverOpen" title="服务器地址" show-cancel-button @confirm="saveServer">
      <van-field v-model="serverDraft" placeholder="http://服务器地址:8080" clearable class="server-field" />
    </van-dialog>
    <van-popup v-model:show="corrOpen" position="bottom" round closeable :style="{ height: '80%' }" teleport="body">
      <div class="corr">
        <div class="m-title corr-title">我的更正申请</div>
        <van-loading v-if="corrLoading" class="corr-loading" />
        <div v-else-if="corrList.length === 0" class="m-muted corr-empty">还没有申请过</div>
        <div v-else class="corr-list">
          <div v-for="c in corrList" :key="c.id" class="corr-item m-card">
            <div class="corr-head">
              <van-tag :type="statusOf(c).type" round>{{ statusOf(c).label }}</van-tag>
              <b>小区 {{ c.plotCode }}</b><span>{{ c.traitName }}</span>
              <span class="m-muted corr-time">{{ c.requestedAt.slice(5, 16).replace('T', ' ') }}</span>
            </div>
            <div class="corr-diff">
              <s>{{ c.oldValue }}</s> → <b :class="{ del: c.kind === 'DELETE' }">{{ c.kind === 'DELETE' ? '删掉这条' : c.newValue }}</b>
            </div>
            <div class="m-muted corr-reason">{{ c.reason }}</div>
            <div v-if="c.reviewedBy" class="corr-review" :class="c.status === 'APPROVED' ? 'ok' : 'no'">
              {{ c.reviewedBy }}：{{ c.status === 'APPROVED' ? '通过' : '驳回' }}{{ c.reviewNote ? '，' + c.reviewNote : '' }}
            </div>
            <van-button v-if="c.status === 'PENDING'" size="small" round plain class="corr-withdraw" @click="withdrawCorrection(c)">撤回</van-button>
          </div>
        </div>
      </div>
    </van-popup>
  </div>
</template>

<style scoped>
.corr {
  height: 100%;
  display: flex;
  flex-direction: column;
  padding: 18px 14px 0;
}

.corr-title {
  font-size: 19px;
  margin-bottom: 12px;
}

.corr-loading,
.corr-empty {
  margin: 40px auto;
  text-align: center;
}

.corr-list {
  flex: 1;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding-bottom: calc(16px + env(safe-area-inset-bottom));
}

.corr-item {
  padding: 12px 14px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.corr-head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.corr-time {
  margin-left: auto;
  font-size: 12px;
}

.corr-diff s {
  color: var(--ink-3);
}

.corr-diff b {
  color: var(--forest);
}

.corr-diff b.del {
  color: var(--clay);
}

.corr-reason {
  font-size: 13px;
}

.corr-review {
  font-size: 13px;
  padding: 6px 10px;
  border-radius: 10px;
}

.corr-review.ok {
  background: var(--green-soft);
  color: var(--forest);
}

.corr-review.no {
  background: #f8e6df;
  color: #7d3520;
}

.corr-withdraw {
  align-self: flex-end;
}

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

.local-hint {
  margin: 8px 18px 18px;
  font-size: 12px;
  line-height: 1.6;
}
</style>
