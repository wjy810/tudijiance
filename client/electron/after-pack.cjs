// electron-builder 打包后的处理：给 Windows 的 exe 换上图标、写上产品名称和版本。
// electron-builder 自己用 rcedit 做这件事，但 rcedit 是 Windows 程序，在 Linux、macOS 上要装 wine；
// 这里改用纯 JavaScript 的 resedit，任何系统上都能打 Windows 安装包。
const fs = require('node:fs')
const path = require('node:path')

/** @param {import('electron-builder').AfterPackContext} context */
exports.default = async function afterPack(context) {
  if (context.electronPlatformName !== 'win32') {
    return
  }
  const { load } = require('resedit/cjs')
  const ResEdit = await load()

  const appInfo = context.packager.appInfo
  const exePath = path.join(context.appOutDir, `${appInfo.productFilename}.exe`)
  const exe = ResEdit.NtExecutable.from(fs.readFileSync(exePath), { ignoreCert: true })
  const res = ResEdit.NtExecutableResource.from(exe)

  // 图标：替换 exe 里原来的第一组图标（Electron 默认图标）
  const icon = ResEdit.Data.IconFile.from(fs.readFileSync(path.join(__dirname, '..', 'build', 'icon.ico')))
  const groups = ResEdit.Resource.IconGroupEntry.fromEntries(res.entries)
  const target = groups[0] ?? { id: 1, lang: 1033 }
  ResEdit.Resource.IconGroupEntry.replaceIconsForResource(
    res.entries, target.id, target.lang, icon.icons.map((item) => item.data),
  )

  // 版本信息：资源管理器"属性 → 详细信息"里看到的名称和版本
  const versions = ResEdit.Resource.VersionInfo.fromEntries(res.entries)
  const vi = versions[0] ?? ResEdit.Resource.VersionInfo.createEmpty()
  const [major, minor, patch] = appInfo.version.split('.').map((n) => Number.parseInt(n, 10) || 0)
  vi.setFileVersion(major, minor, patch, 0, 1033)
  vi.setProductVersion(major, minor, patch, 0, 1033)
  vi.setStringValues({ lang: 1033, codepage: 1200 }, {
    ProductName: appInfo.productName,
    FileDescription: appInfo.productName,
    CompanyName: '',
    LegalCopyright: '',
    OriginalFilename: `${appInfo.productFilename}.exe`,
    InternalName: appInfo.productFilename,
  })
  vi.outputToResourceEntries(res.entries)

  res.outputResource(exe)
  fs.writeFileSync(exePath, Buffer.from(exe.generate()))
}
