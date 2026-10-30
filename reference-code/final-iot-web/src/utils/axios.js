import axios from 'axios'
import { ElMessage } from 'element-plus'

const service = axios.create({
    baseURL: 'http://localhost:8084',
    timeout: 10000
})

// 响应拦截器（仅处理错误）
service.interceptors.response.use(
    response => {
        return response.data
    },
    error => {
        ElMessage.error(error.response?.data?.msg || error.message)
        return Promise.reject(error)
    }
)

export default service
