// 手机端的 Vite 配置：入口是 mobile/index.html，打包到 dist-mobile，Capacitor 再把它装进安卓 APP
import { readFileSync } from 'node:fs'
import { fileURLToPath, URL } from 'node:url'

import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

const here = (p: string) => fileURLToPath(new URL(p, import.meta.url))
const pkg = JSON.parse(readFileSync(here('./package.json'), 'utf8')) as { version: string }

export default defineConfig({
  root: here('./mobile'),
  // 相对路径：APP 里从本地文件加载
  base: './',
  publicDir: here('./public'),
  // 和电脑端（pnpm dev）分开放依赖预构建缓存：两个同时开时不会互相把对方的缓存冲掉（"Outdated Optimize Dep"）
  cacheDir: here('./node_modules/.vite-mobile'),
  plugins: [vue()],
  define: {
    __APP_VERSION__: JSON.stringify(pkg.version),
  },
  resolve: {
    alias: {
      '@': here('./src'),
    },
  },
  server: {
    port: 5174,
    strictPort: true,
  },
  build: {
    outDir: here('./dist-mobile'),
    emptyOutDir: true,
    chunkSizeWarningLimit: 2000,
  },
})
