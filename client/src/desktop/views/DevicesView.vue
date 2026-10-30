<script setup lang="ts">
// 传感器（第二期预留）：登记设备、查看最近的读数。系统管理员可以新增、修改、删除设备，老师只能看
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { EChartsCoreOption } from 'echarts/core'
import { Plus } from '@element-plus/icons-vue'

import {
  deviceApi, fieldApi,
  type Bucket, type DeviceRequest, type DeviceView, type Field, type SensorMetric, type Series,
} from '@/shared/api'
import { DEVICE_STATUS, DEVICE_TYPE } from '@/shared/labels'
import { getServerUrl } from '@/shared/settings'
import { FONT, LIGHT, axisStyle, formatNumber, tooltipStyle } from '@/shared/charts'
import { useAuthStore } from '@/stores/auth'
import EChart from '@/desktop/components/EChart.vue'

const auth = useAuthStore()
const devices = ref<DeviceView[]>([])
const metrics = ref<SensorMetric[]>([])
const fields = ref<Field[]>([])
const loading = ref(false)

const enabledMetrics = computed(() => metrics.value.filter((m) => m.enabled))
const fieldName = (id: number | null) => fields.value.find((f) => f.id === id)?.name ?? ''

async function load() {
  loading.value = true
  try {
    ;[devices.value, metrics.value, fields.value] = await Promise.all([deviceApi.list(), deviceApi.metrics(), fieldApi.list()])
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    loading.value = false
  }
}

onMounted(load)

function latestText(d: DeviceView, code: string): string {
  const v = d.latest[code]
  const m = metrics.value.find((x) => x.code === code)
  return v ? `${formatNumber(v.value, m?.precisionDigits ?? 1)}${m?.unit ? ' ' + m.unit : ''}` : '—'
}

// ---------- 新增、修改 ----------

const dialogOpen = ref(false)
const editing = ref<DeviceView | null>(null)
const saving = ref(false)

function emptyForm(): DeviceRequest {
  return {
    code: '', name: '', deviceType: 'SOIL_3IN1', fieldId: fields.value[0]?.id ?? null,
    posRow: null, posCol: null, depthCm: 20, simulated: false, remark: null,
  }
}

const form = reactive<DeviceRequest>(emptyForm())

function open(d: DeviceView | null) {
  editing.value = d
  Object.assign(form, d ? { ...d } : emptyForm())
  dialogOpen.value = true
}

const tokenShown = ref<{ code: string; token: string; topic: string } | null>(null)

async function save() {
  if (!form.code.trim() || !form.name.trim()) {
    ElMessage.warning('请填写设备编号和名称')
    return
  }
  saving.value = true
  try {
    if (editing.value) {
      await deviceApi.update(editing.value.id, form)
      ElMessage.success('已保存')
    } else {
      const res = await deviceApi.create(form)
      tokenShown.value = { code: res.device.code, token: res.token, topic: res.device.mqttTopic }
    }
    dialogOpen.value = false
    await load()
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    saving.value = false
  }
}

async function remove(d: DeviceView) {
  try {
    await ElMessageBox.confirm(`删除设备"${d.name}"和它的全部读数？`, '删除设备', {
      type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消',
    })
  } catch {
    return
  }
  try {
    await deviceApi.remove(d.id)
    ElMessage.success('已删除')
    await load()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

async function regenerate(d: DeviceView) {
  try {
    await ElMessageBox.confirm('生成新的接入密钥后，旧密钥立即失效，设备需要改成新密钥才能上报。', '重新生成密钥', {
      type: 'warning', confirmButtonText: '生成', cancelButtonText: '取消',
    })
  } catch {
    return
  }
  try {
    const res = await deviceApi.regenerateToken(d.id)
    tokenShown.value = { code: res.device.code, token: res.token, topic: res.device.mqttTopic }
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

async function copy(text: string) {
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success('已复制')
  } catch {
    ElMessage.warning('复制失败，请手动选中复制')
  }
}

const curlExample = computed(() => tokenShown.value
  ? `curl -X POST ${getServerUrl()}/api/ingest/devices/${tokenShown.value.code}/readings `
    + `-H "X-Device-Token: ${tokenShown.value.token}" -H "Content-Type: application/json" `
    + `-d '{"values":{"soil_temp":15.2,"soil_moisture":23.5,"soil_ph":7.1}}'`
  : '')

// ---------- 读数曲线 ----------

const chartOpen = ref(false)
const chartDevice = ref<DeviceView | null>(null)
const metric = ref('')
const days = ref(7)
const series = ref<Series | null>(null)
const chartLoading = ref(false)

/** 后端要不带时区的本地时间 */
function localIso(d: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}:00`
}

async function loadSeries() {
  const d = chartDevice.value
  if (!d || !metric.value) {
    return
  }
  const to = new Date()
  const from = new Date(to.getTime() - days.value * 86400000)
  const bucket: Bucket = days.value > 30 ? 'DAY' : 'HOUR'
  chartLoading.value = true
  try {
    series.value = await deviceApi.readings(d.id, metric.value, localIso(from), localIso(to), bucket)
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    chartLoading.value = false
  }
}

function openChart(d: DeviceView) {
  chartDevice.value = d
  metric.value = enabledMetrics.value[0]?.code ?? ''
  series.value = null
  chartOpen.value = true
  loadSeries()
}

watch([metric, days], loadSeries)

const metricInfo = computed(() => metrics.value.find((m) => m.code === metric.value) ?? null)

const lineOption = computed<EChartsCoreOption>(() => {
  const t = LIGHT
  const points = series.value?.points ?? []
  const unit = series.value?.unit ?? ''
  const digits = metricInfo.value?.precisionDigits ?? 1
  return {
    textStyle: { fontFamily: FONT },
    grid: { left: 8, right: 16, top: 28, bottom: 24, containLabel: true },
    tooltip: {
      trigger: 'axis',
      ...tooltipStyle(t),
      axisPointer: { type: 'line', lineStyle: { color: t.axis } },
      valueFormatter: (v: number) => `${formatNumber(v, digits)} ${unit}`,
    },
    xAxis: { type: 'time', ...axisStyle(t), splitLine: { show: false } },
    yAxis: { type: 'value', scale: true, name: unit, ...axisStyle(t) },
    series: [{
      type: 'line',
      name: metricInfo.value?.name ?? metric.value,
      data: points.map((p) => [p.t, p.value]),
      showSymbol: false,
      lineStyle: { width: 2, color: t.series[0] },
      itemStyle: { color: t.series[0], borderColor: t.surface, borderWidth: 2 },
      areaStyle: { color: t.series[0], opacity: 0.1 },
    }],
  }
})
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h2>设备接入（第二期预留）</h2>
        <div class="page-desc">第一期只预留接口：传感器通过 HTTP 或 MQTT 上报读数。没有硬件时可以登记模拟设备试用。</div>
      </div>
      <el-button v-if="auth.isSysAdmin" type="primary" :icon="Plus" @click="open(null)">登记设备</el-button>
    </div>

    <div class="panel table-panel">
    <el-table v-loading="loading" :data="devices" empty-text="还没有登记设备">
      <el-table-column prop="code" label="编号" width="110" />
      <el-table-column label="名称" min-width="130">
        <template #default="{ row }">
          {{ row.name }}
          <el-tag v-if="row.simulated" size="small" type="info">模拟</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="类型" min-width="150">
        <template #default="{ row }">{{ DEVICE_TYPE[row.deviceType] ?? row.deviceType }}</template>
      </el-table-column>
      <el-table-column label="位置" min-width="150">
        <template #default="{ row }">
          {{ fieldName(row.fieldId) }}
          <span v-if="row.posRow" class="muted">第 {{ row.posRow }} 行第 {{ row.posCol }} 列</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="80">
        <template #default="{ row }">
          <el-tag size="small" :type="row.status === 'ONLINE' ? 'success' : row.status === 'OFFLINE' ? 'info' : 'warning'">
            {{ DEVICE_STATUS[row.status as keyof typeof DEVICE_STATUS] }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column v-for="m in enabledMetrics" :key="m.code" :label="m.name" width="100" align="right">
        <template #default="{ row }"><span class="num">{{ latestText(row, m.code) }}</span></template>
      </el-table-column>
      <el-table-column label="最后上报" width="140">
        <template #default="{ row }">{{ row.lastSeenAt ? row.lastSeenAt.replace('T', ' ').slice(0, 16) : '—' }}</template>
      </el-table-column>
      <el-table-column label="操作" :width="auth.isSysAdmin ? 190 : 70" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="openChart(row)">曲线</el-button>
          <template v-if="auth.isSysAdmin">
            <el-button link size="small" @click="open(row)">修改</el-button>
            <el-button link size="small" @click="regenerate(row)">换密钥</el-button>
            <el-button link type="danger" size="small" @click="remove(row)">删除</el-button>
          </template>
        </template>
      </el-table-column>
    </el-table>
    </div>

    <el-dialog v-model="dialogOpen" :title="editing ? '修改设备' : '登记设备'" width="520px">
      <el-form label-width="88px">
        <el-form-item label="设备编号" required>
          <el-input v-model="form.code" maxlength="64" :disabled="!!editing" placeholder="例如 SOIL-01，上报时用" />
        </el-form-item>
        <el-form-item label="名称" required>
          <el-input v-model="form.name" maxlength="100" />
        </el-form-item>
        <el-form-item label="类型">
          <el-select v-model="form.deviceType" class="full">
            <el-option v-for="(label, key) in DEVICE_TYPE" :key="key" :value="key" :label="label" />
          </el-select>
        </el-form-item>
        <el-form-item label="实验田">
          <el-select v-model="form.fieldId" clearable class="full">
            <el-option v-for="f in fields" :key="f.id" :value="f.id" :label="f.name" />
          </el-select>
        </el-form-item>
        <el-form-item label="位置">
          <span class="muted gap">第</span>
          <el-input-number v-model="form.posRow" :min="0.5" :step="0.5" :controls="false" class="pos" />
          <span class="muted gap">行，第</span>
          <el-input-number v-model="form.posCol" :min="0.5" :step="0.5" :controls="false" class="pos" />
          <span class="muted gap">列</span>
        </el-form-item>
        <el-form-item label="埋深">
          <el-input-number v-model="form.depthCm" :min="0" :max="200" :controls="false" class="pos" />
          <span class="muted gap">cm</span>
        </el-form-item>
        <el-form-item label="模拟设备">
          <el-switch v-model="form.simulated" />
          <span class="hint gap">打开后由服务器自动生成读数，没有硬件时用来试用</span>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" maxlength="500" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogOpen = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog :model-value="!!tokenShown" title="设备接入密钥" width="640px" @update:model-value="tokenShown = null">
      <template v-if="tokenShown">
        <el-alert type="warning" :closable="false" show-icon title="密钥只显示这一次，请马上复制保存。丢了只能重新生成。" />
        <div class="token-row">
          <code class="inline">{{ tokenShown.token }}</code>
          <el-button size="small" @click="copy(tokenShown.token)">复制</el-button>
        </div>
        <div class="section">HTTP 上报</div>
        <code class="inline block">{{ curlExample }}</code>
        <div class="section">MQTT 上报</div>
        <div class="hint">
          发布到主题 <code class="inline">{{ tokenShown.topic }}</code>，报文格式和 HTTP 相同。
          MQTT 的账号由 MQTT 服务器（Mosquitto）给每台设备单独开，上面的密钥只用于 HTTP 上报。
        </div>
      </template>
      <template #footer>
        <el-button type="primary" @click="tokenShown = null">我已保存</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="chartOpen" :title="chartDevice ? `${chartDevice.name} · 读数` : '读数'" size="60%">
      <div class="chart-tools">
        <el-radio-group v-model="metric" size="small">
          <el-radio-button v-for="m in enabledMetrics" :key="m.code" :value="m.code">{{ m.name }}</el-radio-button>
        </el-radio-group>
        <el-radio-group v-model="days" size="small">
          <el-radio-button :value="1">1 天</el-radio-button>
          <el-radio-button :value="7">7 天</el-radio-button>
          <el-radio-button :value="30">30 天</el-radio-button>
          <el-radio-button :value="90">90 天</el-radio-button>
        </el-radio-group>
      </div>
      <div v-loading="chartLoading">
        <el-empty v-if="series && series.points.length === 0" description="这段时间没有读数" :image-size="60" />
        <EChart v-else :option="lineOption" height="380px" />
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.table-panel {
  padding: 4px 16px 12px;
}

.full {
  width: 100%;
}

.pos {
  width: 90px;
}

.gap {
  margin: 0 8px;
}

.num {
  font-variant-numeric: tabular-nums;
}

.token-row {
  display: flex;
  gap: 8px;
  align-items: center;
  margin: 12px 0;
}

.section {
  font-weight: 600;
  margin: 14px 0 6px;
}

.block {
  display: block;
  padding: 8px 10px;
  line-height: 1.6;
}

.chart-tools {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}
</style>
