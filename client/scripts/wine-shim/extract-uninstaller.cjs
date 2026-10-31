// 在 Linux 上打 Windows 安装包时代替 wine。
// electron-builder 在 Linux 上只用 wine 做一件事：运行一次中间安装包，让它写出卸载程序。
// 这里改用 electron-builder 自带的 UninstallerReader 直接从中间安装包里读出卸载程序（它在 macOS 上就是这么做的），
// 不用装 wine。卸载程序的路径和 electron-builder 的算法一致：和安装包同一目录，名字加 "__uninstaller.exe"。
const path = require('node:path')

const installer = process.argv[2]
if (!installer) {
  console.error('用法：wine <安装包路径>')
  process.exit(2)
}
const appBuilderLib = path.dirname(require.resolve('app-builder-lib/package.json', {
  paths: [require.resolve('electron-builder')],
}))
const { UninstallerReader } = require(path.join(appBuilderLib, 'out', 'targets', 'nsis', 'nsisUtil.js'))
const uninstaller = path.join(path.dirname(installer), `${path.basename(installer, 'exe')}__uninstaller.exe`)

UninstallerReader.exec(installer, uninstaller).catch((e) => {
  console.error(e)
  process.exit(1)
})
