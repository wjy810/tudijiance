<script setup lang="ts">
// 功能模块管理（系统管理员）：内置模块启用、停用（必需模块不能停），自己加的链接模块增、改、删。
// 停用的模块：菜单里不显示、页面打不开、服务器也拒绝它的接口；数据不会删，重新启用就回来了
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  Collection, Cpu, DataAnalysis, Delete, Document, EditPen, Grid, Link as LinkIcon, Lock, Menu, Monitor, Notebook,
  PieChart, Plus, Tickets, TopRight, Upload, User, Warning, Finished,
} from '@element-plus/icons-vue'
import type { Component } from 'vue'

import { moduleApi, type AppModule, type Role } from '@/shared/api'
import { ROLE } from '@/shared/labels'
import { useModuleStore } from '@/stores/modules'
import PageHeader from '@/desktop/components/PageHeader.vue'
import SectionCard from '@/desktop/components/SectionCard.vue'
import EmptyState from '@/desktop/components/EmptyState.vue'

const store = useModuleStore()
const list = ref<AppModule[]>([])
const loading = ref(true)
const loadError = ref('')
const busy = ref<string | null>(null)

const ICONS: Record<string, Component> = {
  field_map: Grid, varieties: Collection, traits: Tickets, data: Document, stats: DataAnalysis, analysis: PieChart,
  record_book: Notebook, import: Upload, screen: Monitor, devices: Cpu, quality: Finished, corrections: EditPen,
  users: User, modules: Menu,
}

/** 停用后谁会受影响 */
const WHO: Record<string, string> = {
  data: '老师、学生看不到"数据查看"；录入不受影响',
  stats: '老师、学生看不到"统计分析"',
  analysis: '看不到"自助分析"，平面图上没有"做图表"',
  record_book: '看不到试验记载本，不能导出 Excel 记载本',
  import: '老师不能导入历史表格',
  screen: '数据大屏打不开，游客登录后看到"已停用"',
  devices: '传感器上报接口拒绝数据（第二期才用）',
  quality: '看不到数据质量检查',
  corrections: '学生不能申请更正，老师看不到待审核的申请',
}

const builtins = computed(() => list.value.filter((m) => m.kind === 'BUILTIN'))
const links = computed(() => list.value.filter((m) => m.kind === 'LINK'))
const onCount = computed(() => builtins.value.filter((m) => m.on).length)

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    list.value = await moduleApi.list()
  } catch (e) {
    loadError.value = (e as Error).message
  } finally {
    loading.value = false
  }
}

onMounted(load)

async function toggle(m: AppModule, enabled: boolean) {
  if (!enabled) {
    try {
      await ElMessageBox.confirm(
        `停用后：${WHO[m.code] ?? '菜单里不再显示这个功能'}。已有的数据不会删除，重新启用就恢复。`,
        `停用“${m.name}”`, { type: 'warning', confirmButtonText: '停用', cancelButtonText: '取消' },
      )
    } catch {
      return
    }
  }
  busy.value = m.code
  try {
    const saved = await moduleApi.setEnabled(m.code, enabled)
    Object.assign(m, saved)
    await store.load(true)
    ElMessage.success(enabled ? `已启用“${m.name}”` : `已停用“${m.name}”`)
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    busy.value = null
  }
}

// ---------- 链接模块 ----------

const LINK_ROLES: Role[] = ['TEACHER', 'STUDENT', 'SYS_ADMIN', 'GUEST']
const dialog = ref(false)
const editing = ref<AppModule | null>(null)
const linkForm = reactive({ name: '', url: 'https://', description: '', roles: ['TEACHER', 'STUDENT'] as Role[], enabled: true })
const saving = ref(false)

function openLink(m: AppModule | null) {
  editing.value = m
  Object.assign(linkForm, m
    ? {
        name: m.name, url: m.url ?? '', description: m.description ?? '',
        roles: (m.roles ?? '').split(',').filter(Boolean) as Role[], enabled: m.enabled,
      }
    : { name: '', url: 'https://', description: '', roles: ['TEACHER', 'STUDENT'], enabled: true })
  dialog.value = true
}

async function saveLink() {
  if (!linkForm.name.trim()) {
    ElMessage.warning('请填写名称')
    return
  }
  if (!/^https?:\/\/\S{3,}$/i.test(linkForm.url.trim())) {
    ElMessage.warning('地址要以 http:// 或 https:// 开头')
    return
  }
  if (linkForm.roles.length === 0) {
    ElMessage.warning('至少选一个角色')
    return
  }
  saving.value = true
  try {
    const req = { name: linkForm.name.trim(), url: linkForm.url.trim(), description: linkForm.description.trim() || null, roles: linkForm.roles, enabled: linkForm.enabled }
    if (editing.value) {
      await moduleApi.updateLink(editing.value.code, req)
    } else {
      await moduleApi.createLink(req)
    }
    dialog.value = false
    await Promise.all([load(), store.load(true)])
    ElMessage.success('已保存，左边菜单"链接"里能看到')
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    saving.value = false
  }
}

async function removeLink(m: AppModule) {
  try {
    await ElMessageBox.confirm(`删除链接“${m.name}”？菜单里不再显示。`, '删除链接', {
      type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消',
    })
  } catch {
    return
  }
  try {
    await moduleApi.removeLink(m.code)
    await Promise.all([load(), store.load(true)])
    ElMessage.success('已删除')
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

function rolesText(m: AppModule): string {
  return (m.roles ?? '').split(',').filter(Boolean).map((r) => ROLE[r as Role] ?? r).join('、')
}
</script>

<template>
  <div class="page">
    <PageHeader title="功能模块" desc="停用暂时用不到的功能，菜单会更简洁；停用的功能页面打不开，服务器也不接受它的请求。数据不会删，重新启用就回来。" />

    <div v-if="loading" class="skeleton" style="height: 420px" />
    <SectionCard v-else-if="loadError">
      <EmptyState art="data" title="没取到功能模块" :desc="`${loadError}。检查一下网络或服务器。`">
        <el-button type="primary" @click="load">再试一次</el-button>
      </EmptyState>
    </SectionCard>

    <template v-else>
      <SectionCard title="内置功能" :sub="`开着 ${onCount} / ${builtins.length} 个`">
        <div class="grid">
          <article v-for="m in builtins" :key="m.code" class="mod" :class="{ off: !m.on }">
            <span class="mod-icon"><el-icon><component :is="ICONS[m.code] ?? Menu" /></el-icon></span>
            <div class="mod-body">
              <div class="mod-head">
                <b>{{ m.name }}</b>
                <span v-if="m.required" class="chip"><el-icon><Lock /></el-icon>必需</span>
                <span v-else-if="!m.on" class="chip clay">已停用</span>
              </div>
              <p>{{ m.description }}</p>
              <p v-if="!m.on && WHO[m.code]" class="effect"><el-icon><Warning /></el-icon>{{ WHO[m.code] }}</p>
            </div>
            <el-switch
              :model-value="m.on"
              :disabled="m.required"
              :loading="busy === m.code"
              :aria-label="`${m.name}${m.on ? '已启用' : '已停用'}`"
              @update:model-value="(v: string | number | boolean) => toggle(m, !!v)"
            />
          </article>
        </div>
      </SectionCard>

      <SectionCard title="链接" sub="加到左边菜单里的外部网站，例如学校教务系统、气象网站、品种审定公告">
        <template #actions>
          <el-button type="primary" :icon="Plus" @click="openLink(null)">添加链接</el-button>
        </template>
        <EmptyState v-if="links.length === 0" art="data" :size="90" title="还没有链接" desc="添加后，选定的角色在左边菜单的“链接”里能看到，点开在浏览器里打开。" />
        <div v-else class="links">
          <div v-for="m in links" :key="m.code" class="link-row" :class="{ off: !m.on }">
            <span class="mod-icon sky"><el-icon><LinkIcon /></el-icon></span>
            <div class="link-text">
              <div class="mod-head">
                <b>{{ m.name }}</b>
                <span v-if="!m.on" class="chip clay">已停用</span>
                <span class="faint">给 {{ rolesText(m) }}</span>
              </div>
              <a :href="m.url ?? '#'" target="_blank" rel="noopener noreferrer" class="url">{{ m.url }}<el-icon><TopRight /></el-icon></a>
              <p v-if="m.description">{{ m.description }}</p>
            </div>
            <el-button link type="primary" :icon="EditPen" @click="openLink(m)">修改</el-button>
            <el-button link type="danger" :icon="Delete" @click="removeLink(m)">删除</el-button>
          </div>
        </div>
      </SectionCard>
    </template>

    <el-dialog v-model="dialog" :title="editing ? '修改链接' : '添加链接'" width="520px">
      <el-form label-width="80px" @submit.prevent="saveLink">
        <el-form-item label="名称" required>
          <el-input v-model="linkForm.name" maxlength="20" placeholder="例如：气象网站" />
        </el-form-item>
        <el-form-item label="地址" required>
          <el-input v-model="linkForm.url" maxlength="490" placeholder="例如 https 开头的网址" />
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="linkForm.description" maxlength="200" placeholder="可不填" />
        </el-form-item>
        <el-form-item label="给谁看" required>
          <el-checkbox-group v-model="linkForm.roles">
            <el-checkbox v-for="r in LINK_ROLES" :key="r" :value="r">{{ ROLE[r] }}</el-checkbox>
          </el-checkbox-group>
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="linkForm.enabled" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveLink">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: var(--sp-5);
}

.grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.mod {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 14px 16px;
  border: 1px solid var(--line);
  border-radius: var(--radius-sm);
  background: var(--surface);
  transition: background var(--dur) var(--ease), border-color var(--dur) var(--ease);
}

.mod.off {
  background: var(--surface-2);
  border-style: dashed;
}

.mod.off .mod-icon {
  filter: grayscale(1);
  opacity: 0.6;
}

.mod-icon {
  flex-shrink: 0;
  width: 36px;
  height: 36px;
  border-radius: 10px;
  display: grid;
  place-items: center;
  background: var(--green-soft);
  color: var(--green);
  font-size: 18px;
}

.mod-icon.sky {
  background: var(--sky-soft);
  color: var(--sky);
}

.mod-body {
  flex: 1;
  min-width: 0;
}

.mod-head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.mod-head b {
  color: var(--forest);
  font-size: var(--fs-md);
}

.mod-head .chip {
  display: inline-flex;
  align-items: center;
  gap: 3px;
}

.mod p {
  margin: 4px 0 0;
  font-size: var(--fs-sm);
  color: var(--ink-2);
  line-height: 1.6;
}

.mod .effect {
  display: flex;
  gap: 4px;
  align-items: flex-start;
  color: var(--clay);
}

.mod .effect .el-icon {
  margin-top: 3px;
}

.links {
  display: flex;
  flex-direction: column;
}

.link-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 0;
  border-bottom: 1px solid var(--line-soft);
}

.link-row:last-child {
  border-bottom: none;
}

.link-row.off {
  opacity: 0.6;
}

.link-text {
  flex: 1;
  min-width: 0;
}

.link-text p {
  margin: 2px 0 0;
  font-size: var(--fs-sm);
  color: var(--ink-2);
}

.url {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  font-size: var(--fs-sm);
  color: var(--sky);
  text-decoration: none;
  word-break: break-all;
}

.url:hover {
  text-decoration: underline;
}

@media (max-width: 1100px) {
  .grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
