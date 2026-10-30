// 预加载脚本：只向页面暴露最少的信息，页面不能直接使用 Node.js
const { contextBridge } = require('electron')

contextBridge.exposeInMainWorld('desktop', {
  isElectron: true,
  platform: process.platform,
})
