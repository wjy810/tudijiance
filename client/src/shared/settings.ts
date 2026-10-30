// 本机设置：服务器地址。存在本机，换电脑要重新填
const SERVER_KEY = 'tudijiance.serverUrl'
const DEFAULT_SERVER = 'http://localhost:8080'

export function getServerUrl(): string {
  try {
    return localStorage.getItem(SERVER_KEY) || DEFAULT_SERVER
  } catch {
    return DEFAULT_SERVER
  }
}

export function setServerUrl(url: string): void {
  const trimmed = url.trim().replace(/\/+$/, '')
  try {
    localStorage.setItem(SERVER_KEY, trimmed || DEFAULT_SERVER)
  } catch {
    // 本机存储不可用时，只在本次运行有效
  }
}
