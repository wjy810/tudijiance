// Capacitor：把手机端页面（dist-mobile）装进安卓 APP
import type { CapacitorConfig } from '@capacitor/cli'

const config: CapacitorConfig = {
  appId: 'cn.tudijiance.mobile',
  appName: '小麦实验田',
  webDir: 'dist-mobile',
  server: {
    androidScheme: 'https',
    // 学校的服务器可能先用 http，允许明文请求
    cleartext: true,
  },
  plugins: {
    // 网络请求走原生 HTTP：不受跨域限制，也能访问 http 的服务器
    CapacitorHttp: { enabled: true },
    SplashScreen: { launchShowDuration: 600, backgroundColor: '#1c4231', showSpinner: false },
    StatusBar: { backgroundColor: '#1c4231', style: 'DARK', overlaysWebView: false },
  },
}

export default config
