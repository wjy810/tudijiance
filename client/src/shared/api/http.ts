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

/**
 * 请求出错。status 是服务器返回的状态码；连不上服务器（没网、地址不对、超时）时为 null。
 * 手机端靠它区分"没网，先存在本机"和"服务器不收，要改"。
 */
export class ApiError extends Error {
  readonly status: number | null

  constructor(message: string, status: number | null) {
    super(message)
    this.name = 'ApiError'
    this.status = status
  }

  get network(): boolean {
    return this.status === null
  }
}

/** 是不是连不上服务器（没网） */
export function isNetworkError(e: unknown): boolean {
  return e instanceof ApiError && e.network
}

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
    return Promise.reject(new ApiError(errorMessage(error), error.response?.status ?? null))
  },
)

/** 把内容存成文件（浏览器下载）。链接要先放进页面再点，有的浏览器才认文件名 */
export function saveFile(data: Blob | string, filename: string): void {
  const href = typeof data === 'string' ? data : URL.createObjectURL(data)
  const a = document.createElement('a')
  a.href = href
  a.download = filename
  a.style.display = 'none'
  document.body.appendChild(a)
  a.click()
  a.remove()
  if (typeof data !== 'string') {
    setTimeout(() => URL.revokeObjectURL(href), 10_000)
  }
}

/**
 * 下载文件（Excel 等）。要带登录令牌，所以不能直接用链接打开。
 * 出错时服务器返回的是 JSON，这里读出其中的提示文字。
 */
export async function download(url: string, params: object, filename: string): Promise<void> {
  const res = await http.get<Blob>(url, { params, responseType: 'blob', validateStatus: () => true })
  await saveDownload(res.status, res.data, filename)
}

/** 和 download 一样，只是参数放在请求体里（查询条件比较复杂时） */
export async function downloadPost(url: string, body: object, filename: string): Promise<void> {
  const res = await http.post<Blob>(url, body, { responseType: 'blob', validateStatus: () => true, timeout: 60000 })
  await saveDownload(res.status, res.data, filename)
}

/** 状态码不是 200 时，服务器返回的是 JSON，读出其中的提示文字 */
async function saveDownload(status: number, data: Blob, filename: string): Promise<void> {
  if (status !== 200) {
    if (status === 401 && onUnauthorized) {
      onUnauthorized()
    }
    let message = `下载失败（${status}）`
    try {
      message = (JSON.parse(await data.text()) as { message?: string }).message ?? message
    } catch {
      // 不是 JSON，用默认提示
    }
    throw new Error(message)
  }
  saveFile(data, filename)
}

function errorMessage(error: AxiosError<{ message?: string }>): string {
  if (error.response?.data?.message) {
    return error.response.data.message
  }
  if (error.response) {
    return `请求失败（${error.response.status}）`
  }
  return `连不上服务器 ${getServerUrl()}，请检查服务器地址和网络`
}
