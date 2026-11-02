const { app, BrowserWindow, ipcMain, shell } = require('electron')
const path = require('path')
const fetcher = require('./fetcher')
const downloads = require('./downloads')
const updater = require('./updater')
const state = require('./state')

// 用一般 Chrome 的 UA，網站對 Electron 字樣的 UA 可能會擋
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36'
app.userAgentFallback = UA

// 看過的縮圖 / 影片不存磁碟快取，userData 才不會越看越大（同一次執行裡還是有記憶體快取）
app.commandLine.appendSwitch('disable-http-cache')
// 滑過縮圖的預覽短片不用先點頁面也能播
app.commandLine.appendSwitch('autoplay-policy', 'no-user-gesture-required')

/** 刪掉以前留下的快取資料夾。一定要在 app ready 之前做，ready 之後 Chromium 已經開著這些檔案 */
function removeOldCaches() {
  const ofs = require('original-fs')
  const ud = app.getPath('userData')
  for (const n of ['Cache', 'Code Cache', 'GPUCache', 'DawnGraphiteCache', 'DawnWebGPUCache', 'GrShaderCache', 'ShaderCache']) {
    try {
      ofs.rmSync(path.join(ud, n), { recursive: true, force: true })
    } catch {}
  }
}

let mainWin = null

function createWindow() {
  mainWin = new BrowserWindow({
    width: 1400,
    height: 900,
    minWidth: 900,
    minHeight: 600,
    backgroundColor: '#0f0f13',
    title: 'MPlayer',
    autoHideMenuBar: true,
    icon: path.join(__dirname, 'src', 'icon.png'),
    // 自動測試時不要跳出視窗搶走焦點（測試程式會自己把視窗移到畫面外再顯示）
    show: process.env.MPLAYER_TEST !== '1',
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
    },
  })
  mainWin.removeMenu()
  mainWin.loadFile(path.join(__dirname, 'src', 'index.html'))
  mainWin.webContents.setWindowOpenHandler(({ url }) => {
    shell.openExternal(url)
    return { action: 'deny' }
  })
  mainWin.webContents.on('before-input-event', (e, input) => {
    if (input.type === 'keyDown' && input.key === 'F12') {
      mainWin.webContents.toggleDevTools()
      e.preventDefault()
    }
  })
}

function setupIpc() {
  ipcMain.handle('net:get', (_e, url) => fetcher.get(url))
  ipcMain.handle('net:media', (_e, url) => fetcher.allowMedia(url))
  ipcMain.handle('site:base', () => fetcher.BASE)
  ipcMain.handle('shell:external', (_e, url) => shell.openExternal(url))
  ipcMain.handle('win:fullscreen', (_e, on) => {
    if (mainWin && !mainWin.isDestroyed()) mainWin.setFullScreen(on === undefined ? !mainWin.isFullScreen() : !!on)
    return mainWin?.isFullScreen() || false
  })
}

// 同時只開一份：再開一次就把原本的視窗叫到前面（兩份同時跑下載會打架）
if (!app.requestSingleInstanceLock()) {
  app.quit()
} else {
  removeOldCaches()
  app.on('second-instance', () => {
    if (!mainWin || mainWin.isDestroyed()) return
    if (mainWin.isMinimized()) mainWin.restore()
    mainWin.show()
    mainWin.focus()
  })
}

app.whenReady().then(() => {
  if (!app.hasSingleInstanceLock()) return
  state.setup()
  fetcher.init(UA)
  setupIpc()
  downloads.setup()
  updater.setup()
  createWindow()
})

app.on('window-all-closed', () => app.quit())
