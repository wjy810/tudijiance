<template>
  <div class="log-container">
    <div class="content-header">
      <h2>系统日志</h2>
      <el-breadcrumb separator="/">
        <el-breadcrumb-item :to="{ path: '/dashboard' }">首页</el-breadcrumb-item>
        <el-breadcrumb-item>系统日志</el-breadcrumb-item>
      </el-breadcrumb>
    </div>

    <el-card class="log-card">
      <!-- 搜索栏 -->
      <div class="search-bar">
        <el-form :inline="true" :model="searchForm" class="search-form">
          <el-form-item label="日志类型">
            <el-select
                v-model="searchForm.logType"
                placeholder="请选择日志类型"
                clearable
            >
              <el-option label="全部" value="" />
              <el-option label="设备检索" value="device_search" />
              <el-option label="用户登录" value="user_login" />
              <el-option label="设备告警" value="device_alert" />
              <el-option label="设备上报" value="device_report" />
            </el-select>
          </el-form-item>
          <el-form-item label="时间范围">
            <el-date-picker
                v-model="searchForm.timeRange"
                type="datetimerange"
                range-separator="至"
                start-placeholder="开始日期"
                end-placeholder="结束日期"
                value-format="YYYY-MM-DD HH:mm:ss"
            />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" @click="handleSearch">搜索</el-button>
            <el-button @click="resetSearch">重置</el-button>
          </el-form-item>
        </el-form>
      </div>

      <!-- 日志表格 -->
      <el-table
          :data="tableData"
          style="width: 100%"
          stripe
          v-loading="loading"
      >
        <el-table-column prop="time" label="时间" width="180" sortable />
        <el-table-column prop="type" label="类型" width="120">
          <template #default="{ row }">
            <el-tag :type="getLogTypeStyle(row.type)" effect="plain">
              {{ getLogTypeText(row.type) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="content" label="内容" />
        <el-table-column label="操作" width="120">
          <template #default="{ row }">
            <el-button size="small" @click="handleDetail(row)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>

      <!-- 分页 -->
      <div class="pagination">
        <el-pagination
            v-model:current-page="currentPage"
            v-model:page-size="pageSize"
            :page-sizes="[10, 20, 30, 50]"
            :small="false"
            :background="true"
            layout="total, sizes, prev, pager, next, jumper"
            :total="total"
            @size-change="handleSizeChange"
            @current-change="handleCurrentChange"
        />
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { formatDateTime } from '@/utils/dateUtil' // 假设有一个日期格式化工具

// 搜索表单
const searchForm = ref({
  logType: '',
  timeRange: []
})

// 表格数据
const tableData = ref([])
const allData = ref([])
const loading = ref(false)
const currentPage = ref(1)
const pageSize = ref(20)
const total = ref(0)

// 从告警列表导入的告警数据（模拟）
const alertData = [
  {
    deviceId: 1,
    productKey: 'd604229e2d8bce7b',
    title: '土壤传感器',
    content: '设备电量过低',
    kinds: 'urgency',
    create_time: '2024-06-25 10:53:31'
  },
  {
    deviceId: 2,
    productKey: 'a1b2c3d4e5f6g7h8',
    title: '温湿度传感器',
    content: '温度超过阈值(30°C)',
    kinds: 'normal',
    create_time: '2024-06-25 09:15:22'
  },
  {
    deviceId: 3,
    productKey: 'i9j8k7l6m5n4o3p2',
    title: '光照传感器',
    content: '光照强度不足',
    kinds: 'critical',
    create_time: '2024-06-24 14:30:45'
  }
]

// 生成符合模板格式的假数据
const generateMockData = () => {
  const now = new Date()
  const oneWeekAgo = new Date(now.getTime() - 7 * 24 * 60 * 60 * 1000)

  const mockData = []

  // 1. 生成设备检索日志 (30%)
  for (let i = 0; i < 30; i++) {
    const randomTime = new Date(
        oneWeekAgo.getTime() + Math.random() * (now.getTime() - oneWeekAgo.getTime())
    )

    mockData.push({
      id: mockData.length + 1,
      time: formatDateTime(randomTime),
      timestamp: randomTime.getTime(),
      type: 'device_search',
      content: `检索到${Math.floor(Math.random() * 20) + 1}条数据`
    })
  }

  // 2. 生成用户登录日志 (20%)
  const users = ['admin', 'operator', 'viewer', 'guest']
  for (let i = 0; i < 20; i++) {
    const randomTime = new Date(
        oneWeekAgo.getTime() + Math.random() * (now.getTime() - oneWeekAgo.getTime())
    )
    const user = users[Math.floor(Math.random() * users.length)]

    mockData.push({
      id: mockData.length + 1,
      time: formatDateTime(randomTime),
      timestamp: randomTime.getTime(),
      type: 'user_login',
      content: `传入的用户信息User(userId=null, username=${user}, password=******)`
    })
  }

  // 3. 生成设备告警日志 (20%) - 与告警列表一致
  for (let i = 0; i < alertData.length; i++) {
    const alert = alertData[i]
    const alertTime = new Date(alert.create_time)

    mockData.push({
      id: mockData.length + 1,
      time: alert.create_time,
      timestamp: alertTime.getTime(),
      type: 'device_alert',
      content: `告警内容：Device(deviceId=${alert.deviceId}, productKey=${alert.productKey}, title=${alert.title}, content="${alert.content}", kinds="${alert.kinds}", create_time=${alert.create_time})`
    })
  }

  // 4. 生成设备上报日志 (30%)
  const devices = [
    { id: 1, productKey: 'd604229e2d8bce7b', title: '土壤传感器' },
    { id: 2, productKey: 'a1b2c3d4e5f6g7h8', title: '温湿度传感器' },
    { id: 3, productKey: 'i9j8k7l6m5n4o3p2', title: '光照传感器' }
  ]

  const reportContents = [
    '{"temperature": 25.6, "humidity": 60.2}',
    '{"illumination": 35}',
    '{"soil_moisture": 45.8}',
    '{"co2": 420}',
    '{"status": "normal"}'
  ]

  for (let i = 0; i < 30; i++) {
    const randomTime = new Date(
        oneWeekAgo.getTime() + Math.random() * (now.getTime() - oneWeekAgo.getTime())
    )
    const device = devices[Math.floor(Math.random() * devices.length)]
    const content = reportContents[Math.floor(Math.random() * reportContents.length)]

    mockData.push({
      id: mockData.length + 1,
      time: formatDateTime(randomTime),
      timestamp: randomTime.getTime(),
      type: 'device_report',
      content: `上报内容:Device(deviceId=${device.id}, productKey=${device.productKey}, title="${device.title}", content=${content}，create_time=${formatDateTime(randomTime)})`
    })
  }

  // 按时间倒序排序
  return mockData.sort((a, b) => b.timestamp - a.timestamp)
}

// 获取日志类型对应的颜色
const getLogTypeStyle = (type) => {
  const map = {
    device_search: '',
    user_login: 'success',
    device_alert: 'warning',
    device_report: 'info'
  }
  return map[type] || ''
}

// 获取日志类型对应的文本
const getLogTypeText = (type) => {
  const map = {
    device_search: '设备检索',
    user_login: '用户登录',
    device_alert: '设备告警',
    device_report: '设备上报'
  }
  return map[type] || type
}

// 获取表格数据
const fetchData = () => {
  loading.value = true
  // 模拟API请求延迟
  setTimeout(() => {
    // 只在初始加载时生成数据
    if (allData.value.length === 0) {
      allData.value = generateMockData()
    }

    // 应用搜索条件
    let filteredData = [...allData.value]
    if (searchForm.value.logType) {
      filteredData = filteredData.filter(item =>
          item.type === searchForm.value.logType
      )
    }
    if (searchForm.value.timeRange && searchForm.value.timeRange.length === 2) {
      const [start, end] = searchForm.value.timeRange
      const startTime = new Date(start).getTime()
      const endTime = new Date(end).getTime()

      filteredData = filteredData.filter(item => {
        return item.timestamp >= startTime && item.timestamp <= endTime
      })
    }

    total.value = filteredData.length

    // 分页处理
    const start = (currentPage.value - 1) * pageSize.value
    const end = start + pageSize.value
    tableData.value = filteredData.slice(start, end)

    loading.value = false
  }, 500)
}

// 搜索
const handleSearch = () => {
  currentPage.value = 1
  fetchData()
}

// 重置搜索条件但不重置数据
const resetSearch = () => {
  searchForm.value = {
    logType: '',
    timeRange: []
  }
  currentPage.value = 1
  fetchData()
}

// 分页大小变化
const handleSizeChange = (val) => {
  pageSize.value = val
  fetchData()
}

// 当前页变化
const handleCurrentChange = (val) => {
  currentPage.value = val
  fetchData()
}

// 查看详情
const handleDetail = (row) => {
  console.log('查看日志详情:', row)
  // 这里可以打开详情对话框
}

onMounted(() => {
  fetchData()
})
</script>

<style scoped>
  @import url("../assets/css/systemlog.css");
</style>
