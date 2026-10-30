<template>
  <div class="warning-container">
    <div class="content-header">
      <h2>告警列表</h2>
      <el-breadcrumb separator="/">
        <el-breadcrumb-item :to="{ path: '/dashboard' }">首页</el-breadcrumb-item>
        <el-breadcrumb-item>告警列表</el-breadcrumb-item>
      </el-breadcrumb>
    </div>

    <el-card class="warning-card">
      <!-- 搜索栏 -->
      <div class="search-bar">
        <el-form :inline="true" :model="searchForm" class="search-form">
          <el-form-item label="设备名称">
            <el-input
                v-model="searchForm.deviceName"
                placeholder="请输入设备名称"
                clearable
            />
          </el-form-item>
          <el-form-item label="告警级别">
            <el-select
                v-model="searchForm.level"
                placeholder="请选择告警级别"
                clearable
            >
              <el-option label="全部" value="" />
              <el-option label="一般" value="normal" />
              <el-option label="紧急" value="urgent" />
              <el-option label="致命" value="critical" />
            </el-select>
          </el-form-item>
          <el-form-item>
            <el-button type="primary" @click="handleSearch">搜索</el-button>
            <el-button @click="resetSearch">重置</el-button>
          </el-form-item>
        </el-form>
      </div>

      <!-- 告警表格 -->
      <el-table
          :data="tableData"
          style="width: 100%"
          stripe
          v-loading="loading"
      >
        <el-table-column prop="deviceName" label="设备名称" width="180" />
        <el-table-column prop="content" label="告警内容" />
        <el-table-column prop="level" label="告警级别" width="120">
          <template #default="{ row }">
            <el-tag :type="getLevelType(row.level)" effect="dark">
              {{ getLevelText(row.level) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="time" label="告警时间" width="180" sortable />
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

// 搜索表单
const searchForm = ref({
  deviceName: '',
  level: ''
})

// 表格数据
const tableData = ref([])
const allData = ref([]) // 保存所有数据用于重置
const loading = ref(false)
const currentPage = ref(1)
const pageSize = ref(20)
const total = ref(0)

// 生成更合理的假数据
const generateMockData = () => {
  const devices = [
    '温湿度传感器-01',
    '光照传感器-02',
    '土壤传感器-03',
    '二氧化碳传感器-04',
    '通风设备-05',
    '灌溉设备-06',
    '气象站-08'
  ]

  const contents = [
    '温度超过阈值(30°C)',
    '湿度低于阈值(20%)',
    '光照强度不足',
    '土壤湿度过低',
    'CO2浓度过高',
    '设备离线',
    '通讯异常',
    '电池电量低',
    '设备过热',
    '数据异常'
  ]

  // 更合理的告警级别分布：一般占70%，紧急占20%，致命占10%
  const levels = [
    ...Array(70).fill('normal'),
    ...Array(20).fill('urgent'),
    ...Array(10).fill('critical')
  ]

  const mockData = []
  const now = new Date()
  const oneWeekAgo = new Date(now.getTime() - 7 * 24 * 60 * 60 * 1000)

  for (let i = 0; i < 100; i++) {
    const randomDevice = devices[Math.floor(Math.random() * devices.length)]
    const randomContent = contents[Math.floor(Math.random() * contents.length)]
    const randomLevel = levels[Math.floor(Math.random() * levels.length)]

    // 生成过去7天内的随机时间
    const randomTime = new Date(
        oneWeekAgo.getTime() + Math.random() * (now.getTime() - oneWeekAgo.getTime())
    )

    const formattedTime = randomTime.toLocaleString('zh-CN', {
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
      hour12: false
    }).replace(/\//g, '-')

    mockData.push({
      id: i + 1,
      deviceName: randomDevice,
      content: randomContent,
      level: randomLevel,
      time: formattedTime,
      timestamp: randomTime.getTime() // 用于排序
    })
  }

  // 按时间倒序排序
  return mockData.sort((a, b) => b.timestamp - a.timestamp)
}

// 获取告警级别对应的颜色
const getLevelType = (level) => {
  const map = {
    normal: '',
    urgent: 'warning',
    critical: 'danger'
  }
  return map[level] || ''
}

// 获取告警级别对应的文本
const getLevelText = (level) => {
  const map = {
    normal: '一般',
    urgent: '紧急',
    critical: '致命'
  }
  return map[level] || level
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
    if (searchForm.value.deviceName) {
      filteredData = filteredData.filter(item =>
          item.deviceName.includes(searchForm.value.deviceName)
      )
    }
    if (searchForm.value.level) {
      filteredData = filteredData.filter(item =>
          item.level === searchForm.value.level
      )
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
    deviceName: '',
    level: ''
  }
  // 不重新获取数据，只重置搜索条件
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
  console.log('查看详情:', row)
  // 这里可以打开详情对话框或跳转到详情页
  // 例如: ElMessage.info(`查看告警详情: ${row.id}`)
}

onMounted(() => {
  fetchData()
})
</script>

<style scoped>
  @import url("../assets/css/warning.css");
</style>
