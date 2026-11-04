// 跨平台打包；离线包使用独立应用 ID，可与联机版同时安装。
import { spawnSync } from 'node:child_process'
import { copyFileSync, existsSync, mkdirSync, readFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const offline = process.argv.includes('--offline')
const env = { ...process.env, TUDI_OFFLINE: offline ? '1' : '0' }
if (!env.ANDROID_HOME && !env.ANDROID_SDK_ROOT && process.platform === 'win32' && env.LOCALAPPDATA) {
  const sdk = join(env.LOCALAPPDATA, 'Android', 'Sdk')
  if (existsSync(sdk)) env.ANDROID_HOME = sdk
}

function run(command, args, cwd = root) {
  const result = spawnSync(command, args, { cwd, env, stdio: 'inherit' })
  if (result.error) throw result.error
  if (result.status !== 0) process.exit(result.status ?? 1)
}

run(process.execPath, [join(root, 'node_modules/vite/bin/vite.js'), 'build', '--config', 'vite.mobile.config.ts', '--mode', offline ? 'offline' : 'production'])
run(process.execPath, [join(root, 'node_modules/@capacitor/cli/bin/capacitor'), 'sync', 'android'])
const gradleArgs = [':app:assembleDebug', '--console=plain', `-Poffline=${offline}`]
if (process.platform === 'win32') {
  run(env.ComSpec || 'cmd.exe', ['/d', '/c', 'gradlew.bat', ...gradleArgs], join(root, 'android'))
} else {
  run('./gradlew', gradleArgs, join(root, 'android'))
}

const { version } = JSON.parse(readFileSync(join(root, 'package.json'), 'utf8'))
const output = join(root, 'release', `tudijiance-${offline ? 'offline-' : ''}${version}.apk`)
mkdirSync(dirname(output), { recursive: true })
copyFileSync(join(root, 'android/app/build/outputs/apk/debug/app-debug.apk'), output)
console.log(`APK: ${output}`)
