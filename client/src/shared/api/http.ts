import axios, { AxiosError } from 'axios'

import { getServerUrl } from '@/shared/settings'

let token: string | null = null
let onUnauthorized: (() => void) | null = null

export function setToken(value: string | null): void {
  token = value
}

/** 登录过期或令牌无效时调用（由登录状态模块注册，用来跳回登录页）。 */
export function setUnauthorizedHandler(handler: () => void): void {
  onUnauthorized = handler
}

export const http = axios.create({ timeout: 20000 })

http.interceptors.request.use((config) => {
  config.baseURL = getServerUrl()
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

http.interceptors.response.use(
  (response) => response,
  (error: AxiosError<{ message?: string }>) => {
    const isLogin = error.config?.url?.includes('/api/auth/login')
    if (error.response?.status === 401 && !isLogin && onUnauthorized) {
      onUnauthorized()
    }
    return Promise.reject(new Error(errorMessage(error)))
  },
)

function errorMessage(error: AxiosError<{ message?: string }>): string {
  if (error.response?.data?.message) {
    return error.response.data.message
  }
  if (error.response) {
    return `请求失败（${error.response.status}）`
  }
  return `连不上服务器 ${getServerUrl()}，请检查服务器地址和网络`
}
