<script setup lang="ts">
// 用户管理（系统管理员）：新增账号、重置密码、停用
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'

import { userApi, type Role, type UserView } from '@/shared/api'
import { ROLE } from '@/shared/labels'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const users = ref<UserView[]>([])
const loading = ref(false)

const ROLE_TAG: Record<Role, 'danger' | 'success' | 'primary' | 'info'> = {
  SYS_ADMIN: 'danger',
  TEACHER: 'success',
  STUDENT: 'primary',
  GUEST: 'info',
}

const ROLE_HINT: Record<Role, string> = {
  SYS_ADMIN: '维护系统、管理用户，不录入和修改试验数据',
  TEACHER: '布置试验，录入、查看、修改数据',
  STUDENT: '录入和查看数据，不能修改已录的数据',
  GUEST: '只能看数据大屏',
}

async function load() {
  loading.value = true
  try {
    users.value = await userApi.list()
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    loading.value = false
  }
}

onMounted(load)

const dialogOpen = ref(false)
const saving = ref(false)
const form = reactive({ username: '', displayName: '', role: 'STUDENT' as Role, password: '' })

function openCreate() {
  Object.assign(form, { username: '', displayName: '', role: 'STUDENT', password: '' })
  dialogOpen.value = true
}

async function create() {
  if (!form.username.trim()) {
    ElMessage.warning('请填写用户名')
    return
  }
  if (form.password.length < 6) {
    ElMessage.warning('密码至少 6 位')
    return
  }
  saving.value = true
  try {
    await userApi.create({ ...form, username: form.username.trim(), displayName: form.displayName.trim() })
    ElMessage.success('已新增')
    dialogOpen.value = false
    await load()
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    saving.value = false
  }
}

async function resetPassword(u: UserView) {
  let password: string
  try {
    const res = await ElMessageBox.prompt(`给 ${u.displayName || u.username} 设置新密码（至少 6 位）`, '重置密码', {
      inputType: 'password', inputPattern: /^.{6,64}$/, inputErrorMessage: '密码 6 到 64 位',
      confirmButtonText: '保存', cancelButtonText: '取消',
    })
    password = res.value
  } catch {
    return
  }
  try {
    await userApi.resetPassword(u.id, password)
    ElMessage.success('密码已重置')
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

async function setEnabled(u: UserView, enabled: boolean) {
  try {
    await userApi.setEnabled(u.id, enabled)
    u.enabled = enabled
    ElMessage.success(enabled ? '已启用' : '已停用，这个账号不能再登录')
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h2>用户管理</h2>
        <div class="page-desc">四级权限：系统管理员、老师、学生、游客。停用的账号不能登录。</div>
      </div>
      <el-button type="primary" :icon="Plus" @click="openCreate">新增用户</el-button>
    </div>

    <div class="panel table-panel">
    <el-table v-loading="loading" :data="users">
      <el-table-column label="用户" min-width="180">
        <template #default="{ row }">
          <div class="user-cell">
            <span class="avatar" :class="row.role">{{ (row.displayName || row.username).slice(0, 1) }}</span>
            <div>
              <div>{{ row.displayName || row.username }}</div>
              <div class="muted small">{{ row.username }}</div>
            </div>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="角色" width="120">
        <template #default="{ row }">
          <el-tag :type="ROLE_TAG[row.role as Role]" effect="light" round>{{ ROLE[row.role as Role] ?? row.role }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="权限" min-width="240">
        <template #default="{ row }"><span class="muted">{{ ROLE_HINT[row.role as Role] }}</span></template>
      </el-table-column>
      <el-table-column label="启用" width="70" align="center">
        <template #default="{ row }">
          <el-switch
            :model-value="row.enabled"
            :disabled="row.id === auth.user?.id"
            size="small"
            @update:model-value="(v: string | number | boolean) => setEnabled(row, !!v)"
          />
        </template>
      </el-table-column>
      <el-table-column label="操作" width="100">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="resetPassword(row)">重置密码</el-button>
        </template>
      </el-table-column>
    </el-table>
    </div>

    <el-dialog v-model="dialogOpen" title="新增用户" width="480px">
      <el-form label-width="80px">
        <el-form-item label="用户名" required>
          <el-input v-model="form.username" maxlength="64" placeholder="登录用，例如学号" />
        </el-form-item>
        <el-form-item label="姓名">
          <el-input v-model="form.displayName" maxlength="64" />
        </el-form-item>
        <el-form-item label="角色">
          <el-radio-group v-model="form.role">
            <el-radio-button v-for="(label, key) in ROLE" :key="key" :value="key">{{ label }}</el-radio-button>
          </el-radio-group>
          <div class="hint full">{{ ROLE_HINT[form.role] }}</div>
        </el-form-item>
        <el-form-item label="初始密码" required>
          <el-input v-model="form.password" type="password" show-password placeholder="至少 6 位" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogOpen = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="create">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.full {
  width: 100%;
}

.table-panel {
  padding: 4px 16px 12px;
}

.small {
  font-size: 12px;
}

.user-cell {
  display: flex;
  align-items: center;
  gap: 10px;
}

.avatar {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 600;
  background: #eef1ec;
  color: #4f5c54;
}

.avatar.TEACHER {
  background: var(--app-accent-soft);
  color: var(--app-accent);
}

.avatar.SYS_ADMIN {
  background: #fbeaea;
  color: var(--app-danger);
}
</style>
