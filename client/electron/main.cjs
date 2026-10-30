// Electron 主进程：创建窗口，开发时加载 Vite 开发服务器，打包后加载 dist/index.html
const { app, BrowserWindow, Menu, shell } = require('electron')
const path = require('node:path')

function createWindow() {
  const win = new BrowserWindow({
    width: 1440,
    height: 900,
    minWidth: 1100,
    minHeight: 700,
    title: '小麦实验田管理',
    webPreferences: {
      preload: path.join(__dirname, 'preload.cjs'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
    },
  })

  const startUrl = process.env.ELECTRON_START_URL
  if (startUrl) {
    win.loadURL(startUrl)
  } else {
    win.loadFile(path.join(__dirname, '..', 'dist', 'index.html'))
  }

  // 页面里的外部链接用系统浏览器打开
  win.webContents.setWindowOpenHandler(({ url }) => {
    shell.openExternal(url)
    return { action: 'deny' }
  })
}

app.whenReady().then(() => {
  // 打包后隐藏菜单栏；开发时保留，方便用开发者工具
  if (app.isPackaged) {
    Menu.setApplicationMenu(null)
  }
  createWindow()
  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) {
      createWindow()
    }
  })
})

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') {
    app.quit()
  }
})
