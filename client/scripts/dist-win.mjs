// 打 Windows 安装包：node scripts/dist-win.mjs（先 pnpm build）。
// 在 Windows 上直接调用 electron-builder；在 Linux、macOS 上把 wine-shim 放进 PATH，代替 wine 取出卸载程序。
// exe 的图标和版本信息由 electron/after-pack.cjs 写入，也不需要 wine。
import { spawnSync } from 'node:child_process'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const env = { ...process.env }
if (process.platform !== 'win32') {
  env.PATH = `${path.join(here, 'wine-shim')}${path.delimiter}${env.PATH}`
  env.USE_SYSTEM_WINE = 'true'
}
const bin = path.join(here, '..', 'node_modules', '.bin', process.platform === 'win32' ? 'electron-builder.cmd' : 'electron-builder')
const result = spawnSync(bin, ['--win', '--x64', '--publish', 'never'], { stdio: 'inherit', env, shell: process.platform === 'win32' })
process.exit(result.status ?? 1)
