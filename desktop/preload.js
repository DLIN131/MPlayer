// 頁面不能直接碰網路 session 和檔案，透過這裡呼叫主程序
const { contextBridge, ipcRenderer } = require('electron')

contextBridge.exposeInMainWorld('updater', {
  supported: () => ipcRenderer.invoke('update:supported'),
  download: (url, onProgress) => {
    const listener = (_e, p) => onProgress(p)
    ipcRenderer.on('update:progress', listener)
    return ipcRenderer.invoke('update:download', url).finally(() => ipcRenderer.removeListener('update:progress', listener))
  },
  install: () => ipcRenderer.invoke('update:install'),
})

contextBridge.exposeInMainWorld('hub', {
  // 程式資料（收藏、紀錄、播放進度、設定…）存在主程序的 state.json
  state: {
    all: () => ipcRenderer.sendSync('state:all'),
    set: (key, value) => ipcRenderer.send('state:set', key, value),
  },
  /** 抓網站頁面的 HTML（主程序處理 Cloudflare） */
  get: (url) => ipcRenderer.invoke('net:get', url),
  /** 播放前登記影片 CDN，讓它的請求補上網站的標頭 */
  media: (url) => ipcRenderer.invoke('net:media', url),
  /** 網站的 JSON API（登入、收藏、觀看記錄）：回傳 { status, text } */
  api: (method, url, json) => ipcRenderer.invoke('net:api', method, url, json),
  logout: () => ipcRenderer.invoke('net:logout'),
  base: () => ipcRenderer.invoke('site:base'),
  external: (url) => ipcRenderer.invoke('shell:external', url),
  fullscreen: (on) => ipcRenderer.invoke('win:fullscreen', on),
  dl: {
    list: () => ipcRenderer.invoke('dl:list'),
    options: (masterUrl) => ipcRenderer.invoke('dl:options', masterUrl),
    add: (v) => ipcRenderer.invoke('dl:add', v),
    pause: (id) => ipcRenderer.invoke('dl:pause', id),
    resume: (id) => ipcRenderer.invoke('dl:resume', id),
    remove: (id, deleteFile) => ipcRenderer.invoke('dl:remove', id, deleteFile),
    show: (id) => ipcRenderer.invoke('dl:show', id),
    dir: () => ipcRenderer.invoke('dl:dir'),
    chooseDir: () => ipcRenderer.invoke('dl:chooseDir'),
    openDir: () => ipcRenderer.invoke('dl:openDir'),
    onUpdate: (fn) => ipcRenderer.on('dl:update', (_e, j) => fn(j)),
    onRemoved: (fn) => ipcRenderer.on('dl:removed', (_e, id) => fn(id)),
  },
})
