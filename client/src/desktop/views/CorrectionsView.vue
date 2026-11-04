<script setup lang="ts">
// 更正申请：学生发现录错了提交"改成什么、为什么"，老师在这里通过或驳回。学生在这里看自己申请的结果、撤回
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Check, Close, MapLocation, Refresh, RefreshLeft, Warning } from '@element-plus/icons-vue'

import { correctionApi, type CorrectionStatus, type CorrectionView } from '@/shared/api'
import { useAuthStore } from '@/stores/auth'
import { useCorrectionStore } from '@/stores/corrections'
import PageHeader from '@/desktop/components/PageHeader.vue'
import SectionCard from '@/desktop/components/SectionCard.vue'
import EmptyState from '@/desktop/components/EmptyState.vue'

const auth = useAuthStore()
const store = useCorrectionStore()
const router = useRouter()

const rows = ref<CorrectionView[]>([])
const loading = ref(false)
const loadError = ref('')
const tab = ref<'pending' | 'done'>('pending')
const busy = ref<number | null>(null)

const STATUS: Record<CorrectionStatus, { label: string; tone: 'wheat' | 'green' | 'clay' | 'muted' }> = {
  PENDING: { label: '待审核', tone: 'wheat' },
  APPROVED: { label: '已通过', tone: 'green' },
  REJECTED: { label: '已驳回', tone: 'clay' },
  WITHDRAWN: { label: '已撤回', tone: 'muted' },
}

const pending = computed(() => rows.value.filter((r) => r.status === 'PENDING'))
const done = computed(() => rows.value.filter((r) => r.status !== 'PENDING'))
const shown = computed(() => (tab.value === 'pending' ? pending.value : done.value))

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    rows.value = await correctionApi.list()
    void store.refresh()
  } catch (e) {
    loadError.value = (e as Error).message
  } finally {
    loading.value = false
  }
}

onMounted(load)

const time = (s: string | null) => (s ? s.slice(5, 16).replace('T', ' ') : '')

function openPlot(r: CorrectionView) {
  if (!r.fieldId) return
  router.push({ path: `/fields/${r.fieldId}`, query: { trial: r.trialId, plot: r.plotId ?? undefined } })
}

async function approve(r: CorrectionView) {
  busy.value = r.id
  try {
    await correctionApi.approve(r.id, null)
    ElMessage.success(r.kind === 'DELETE' ? '已通过，这条数据已删掉' : '已通过，数据已改好')
    await load()
  } catch (e) {
    ElMessage.error((e as Error).message)
    await load()
  } finally {
    busy.value = null
  }
}

async function reject(r: CorrectionView) {
  let note: string | null = null
  try {
    const res = await ElMessageBox.prompt('写一下为什么不改，学生能看到。', '驳回申请', {
      confirmButtonText: '驳回', cancelButtonText: '取消', inputPlaceholder: '例如：原来录的是对的，复查过了',
      inputValidator: (v) => (v && v.trim() ? true : '请写一下原因'),
    })
    note = res.value.trim()
  } catch {
    return
  }
  busy.value = r.id
  try {
    await correctionApi.reject(r.id, note)
    ElMessage.success('已驳回')
    await load()
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    busy.value = null
  }
}

async function withdraw(r: CorrectionView) {
  try {
    await ElMessageBox.confirm('撤回这个申请？撤回后可以重新申请。', '撤回', {
      type: 'warning', confirmButtonText: '撤回', cancelButtonText: '取消',
    })
  } catch {
    return
  }
  busy.value = r.id
  try {
    await correctionApi.withdraw(r.id)
    ElMessage.success('已撤回')
    await load()
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    busy.value = null
  }
}

const desc = computed(() => (auth.isTeacher
  ? '学生发现录错了提交的更正。看过原来的值和要改成的值，通过后数据才会改，修改记录里记下是按谁的申请改的。'
  : '自己提交的更正申请和老师处理的结果。在数据查看、小区详情、数据质量里都能对某条数据点"申请更正"。'))
</script>

<template>
  <div class="page corrections">
    <PageHeader title="更正申请" :desc="desc">
      <template #actions>
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      </template>
    </PageHeader>

    <SectionCard v-if="loadError">
      <EmptyState art="data" title="没取到" :desc="`${loadError}。检查一下网络或服务器。`">
        <el-button type="primary" @click="load">再试一次</el-button>
      </EmptyState>
    </SectionCard>
    <div v-else-if="loading && rows.length === 0" class="skeleton" style="height: 320px" />

    <SectionCard v-else flush>
      <template #title>
        <div class="tabs" role="tablist">
          <button role="tab" :class="{ on: tab === 'pending' }" @click="tab = 'pending'">待审核 <b class="num">{{ pending.length }}</b></button>
          <button role="tab" :class="{ on: tab === 'done' }" @click="tab = 'done'">已处理 <b class="num">{{ done.length }}</b></button>
        </div>
      </template>

      <EmptyState
        v-if="shown.length === 0"
        :art="tab === 'pending' ? 'done' : 'data'"
        :size="130"
        :title="tab === 'pending' ? (auth.isTeacher ? '没有待审核的申请' : '没有等待审核的申请') : '还没有处理过的申请'"
        :desc="auth.isTeacher
          ? '学生提交更正申请后会出现在这里，侧边栏会显示条数'
          : '发现录错了，在数据查看或小区详情里对那条数据点“申请更正”'"
      />

      <div v-else class="list">
        <article v-for="r in shown" :key="r.id" class="item" :class="[`st-${r.status.toLowerCase()}`, { stale: r.stale }]">
          <header class="item-head">
            <span class="chip" :class="STATUS[r.status].tone">{{ STATUS[r.status].label }}</span>
            <b class="where">小区 {{ r.plotCode || '-' }}</b>
            <span>{{ r.traitName }}</span>
            <span v-if="r.varietyName" class="faint">· {{ r.varietyName }}</span>
            <span class="faint trial">{{ r.fieldName }} · {{ r.trialName }}</span>
            <span class="who faint">{{ r.requestedBy }} · {{ time(r.requestedAt) }}</span>
          </header>

          <div class="diff">
            <div class="side old">
              <span class="side-label">原来</span>
              <span class="side-val">{{ r.oldValue }}</span>
              <span v-if="r.dataType !== 'DATE'" class="side-date faint">调查日期 {{ r.oldObservedOn }}</span>
            </div>
            <span class="arrow" aria-hidden="true">→</span>
            <div v-if="r.kind === 'DELETE'" class="side new del">
              <span class="side-label">申请</span>
              <span class="side-val">删掉这条</span>
            </div>
            <div v-else class="side new">
              <span class="side-label">改成</span>
              <span class="side-val">{{ r.newValue }}</span>
              <span v-if="r.dataType !== 'DATE'" class="side-date faint" :class="{ changed: r.newObservedOn !== r.oldObservedOn }">调查日期 {{ r.newObservedOn }}</span>
            </div>
          </div>

          <blockquote class="reason">{{ r.reason }}</blockquote>

          <div v-if="r.stale" class="stale-note">
            <el-icon><Warning /></el-icon>
            <span v-if="r.deleted">这条数据已经删了，只能驳回。</span>
            <span v-else>申请之后这条数据又被改过了（现在是 {{ r.currentValue }}），不能直接通过。可以驳回，让学生按现在的值重新申请。</span>
          </div>

          <div v-if="r.status !== 'PENDING' && r.status !== 'WITHDRAWN'" class="review" :class="r.status === 'APPROVED' ? 'ok' : 'no'">
            <b>{{ r.reviewedBy }}</b> {{ time(r.reviewedAt) }} {{ r.status === 'APPROVED' ? '通过了' : '驳回了' }}{{ r.reviewNote ? '：' + r.reviewNote : '' }}
          </div>

          <footer class="item-actions">
            <el-button v-if="r.fieldId && r.plotId" link type="primary" :icon="MapLocation" @click="openPlot(r)">去平面图</el-button>
            <template v-if="r.status === 'PENDING'">
              <template v-if="auth.isTeacher">
                <el-button :icon="Close" :disabled="busy === r.id" @click="reject(r)">驳回</el-button>
                <el-button type="primary" :icon="Check" :loading="busy === r.id" :disabled="r.stale" @click="approve(r)">
                  {{ r.kind === 'DELETE' ? '通过，删掉' : '通过，改数据' }}
                </el-button>
              </template>
              <el-button v-else-if="r.requestedById === auth.user?.id" :icon="RefreshLeft" :loading="busy === r.id" @click="withdraw(r)">撤回</el-button>
            </template>
          </footer>
        </article>
      </div>
    </SectionCard>
  </div>
</template>

<style scoped>
.corrections {
  display: flex;
  flex-direction: column;
  gap: var(--sp-5);
}

.tabs {
  display: flex;
  gap: 4px;
}

.tabs button {
  border: none;
  background: none;
  padding: 4px 12px;
  border-radius: var(--radius-pill);
  font: inherit;
  font-family: var(--font-serif);
  font-size: 16px;
  font-weight: 700;
  color: var(--ink-3);
  cursor: pointer;
}

.tabs button b {
  font-family: var(--font-sans);
  font-size: 12px;
  margin-left: 4px;
  padding: 0 7px;
  border-radius: var(--radius-pill);
  background: var(--paper-deep);
  color: var(--ink-2);
}

.tabs button.on {
  color: var(--forest);
  background: var(--green-wash);
}

.tabs button.on b {
  background: var(--forest);
  color: #fbf3df;
}

.list {
  display: flex;
  flex-direction: column;
}

.item {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 16px 20px;
  border-bottom: 1px solid var(--line-soft);
  border-left: 3px solid transparent;
}

.item.st-pending {
  border-left-color: var(--wheat);
}

.item.stale {
  border-left-color: var(--clay);
}

.item-head {
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: 8px;
}

.where {
  font-size: 15px;
}

.trial {
  font-size: var(--fs-xs);
}

.who {
  margin-left: auto;
  font-size: var(--fs-xs);
}

.chip.muted {
  background: var(--paper-deep);
  color: var(--ink-3);
}

.diff {
  display: flex;
  align-items: stretch;
  gap: 12px;
  max-width: 640px;
}

.side {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 10px 14px;
  border-radius: 12px;
  border: 1px solid var(--line);
  background: var(--surface-2);
}

.side-label {
  font-size: 12px;
  color: var(--ink-3);
}

.side-val {
  font-family: var(--font-serif);
  font-size: 18px;
  font-weight: 700;
  color: var(--ink);
  word-break: break-all;
}

.side-date {
  font-size: 12px;
}

.side-date.changed {
  color: var(--forest);
  font-weight: 600;
}

.side.old .side-val {
  color: var(--ink-3);
  text-decoration: line-through;
  text-decoration-color: rgba(176, 84, 58, 0.45);
}

.side.new {
  background: var(--green-wash);
  border-color: #cfe0cf;
}

.side.new .side-val {
  color: var(--forest);
}

.side.new.del {
  background: #f8e6df;
  border-color: #ecc9bc;
}

.side.new.del .side-val {
  color: var(--clay);
}

.arrow {
  align-self: center;
  font-size: 18px;
  color: var(--ink-3);
}

.reason {
  margin: 0;
  padding: 6px 12px;
  border-left: 3px solid var(--line-strong);
  color: var(--ink-2);
  font-size: var(--fs-sm);
  line-height: 1.7;
  max-width: 640px;
}

.stale-note {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 12px;
  border-radius: 10px;
  background: #f8e6df;
  color: #7d3520;
  font-size: var(--fs-sm);
  max-width: 640px;
}

.review {
  font-size: var(--fs-sm);
  color: var(--ink-2);
}

.review.ok b {
  color: var(--forest);
}

.review.no b {
  color: var(--clay);
}

.item-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  justify-content: flex-end;
}

.item-actions:empty {
  display: none;
}
</style>
