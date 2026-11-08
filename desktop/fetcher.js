// 網路（主程序）：抓網站頁面、影片 CDN 的標頭處理。
// 全部走 Chromium 的連線（session.fetch / 頁面），Node 自己的 https 會被 CDN 依 TLS 特徵擋下（403）。
const { app, BrowserWindow, session } = require('electron')

// 網址只用 Base64 存，原始碼裡不出現明文
const b64 = (s) => Buffer.from(s, 'base64').toString('utf8')
const BASE = b64('aHR0cHM6Ly9taXNzYXYud3M=')
/** 影片 CDN：播放清單和分段都要帶網站的 Referer / Origin，不然回 403 */
const CDN_HOSTS = new Set([b64('c3Vycml0LmNvbQ==')])

let ua = ''

const hostOf = (u) => {
  try {
    return new URL(u).hostname
  } catch {
    return ''
  }
}

const siteHeaders = () => ({ Referer: BASE + '/', Origin: BASE })

/** 播放清單在別的主機時（CDN 換了），記下來讓那台主機也補標頭 */
function allowMedia(url) {
  const h = hostOf(url)
  if (h && h !== hostOf(BASE)) CDN_HOSTS.add(h)
}

const isCdn = (u) => {
  const h = hostOf(u)
  return [...CDN_HOSTS].some((d) => h === d || h.endsWith('.' + d))
}

function init(userAgent) {
  ua = userAgent
  const s = session.defaultSession
  // 頁面（hls.js）抓 CDN：補上網站的 Referer / Origin。
  // 主程序自己發的請求（下載）直接在 fetch 帶標頭，這裡不能再改（改了反而被擋）
  s.webRequest.onBeforeSendHeaders({ urls: ['https://*/*'] }, (d, cb) => {
    if (d.webContentsId && isCdn(d.url)) Object.assign(d.requestHeaders, siteHeaders())
    cb({ requestHeaders: d.requestHeaders })
  })
  // CDN 只允許網站本身跨來源讀取；程式的頁面是 file://，改成允許所有來源
  s.webRequest.onHeadersReceived({ urls: ['https://*/*'] }, (d, cb) => {
    if (!d.webContentsId || !isCdn(d.url)) return cb({})
    const h = d.responseHeaders || {}
    for (const k of Object.keys(h)) if (/^access-control-allow-(origin|credentials)$/i.test(k)) delete h[k]
    h['Access-Control-Allow-Origin'] = ['*']
    cb({ responseHeaders: h })
  })
}

// ---------- 過 Cloudflare 用的隱藏視窗 ----------

const isChallenge = (status, text) => (status === 403 || status === 503) && /challenge-platform|Just a moment|cf-chl|Attention Required/i.test(text)
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
let worker = null
let queue = Promise.resolve()

function getWorker() {
  if (!worker || worker.isDestroyed()) {
    worker = new BrowserWindow({ show: false, width: 1000, height: 760, title: '請完成網站驗證', autoHideMenuBar: true, webPreferences: { contextIsolation: true, backgroundThrottling: false } })
    worker.removeMenu()
    worker.webContents.setAudioMuted(true)
    worker.webContents.setWindowOpenHandler(() => ({ action: 'deny' }))
    // 使用者關掉驗證視窗只是藏起來，等待中的請求會收到錯誤
    worker.on('close', (e) => {
      if (app.isQuitting) return
      e.preventDefault()
      worker.hide()
      worker.giveUp = true
    })
  }
  return worker
}
app.on('before-quit', () => {
  app.isQuitting = true
})

/** 用隱藏視窗載入網頁拿 HTML；15 秒還在驗證頁就把視窗顯示出來讓使用者手動驗證 */
function loadViaWindow(url) {
  const run = async () => {
    const w = getWorker()
    w.giveUp = false
    w.loadURL(url).catch(() => {})
    const start = Date.now()
    while (Date.now() - start < 120000) {
      await sleep(600)
      if (w.isDestroyed()) break
      if (w.giveUp) throw new Error('驗證視窗被關閉了')
      let info
      try {
        info = await w.webContents.executeJavaScript('({ title: document.title, ready: document.readyState, html: document.documentElement.outerHTML })')
      } catch {
        continue
      }
      const challenged = /Just a moment|請稍候|Attention Required|cf-chl-/i.test(info.title + info.html.slice(0, 4000))
      if (!challenged && info.ready !== 'loading') {
        if (w.isVisible()) w.hide()
        // 網頁會一直跑廣告腳本，拿到內容就換成空白頁
        w.loadURL('about:blank').catch(() => {})
        return info.html
      }
      if (challenged && Date.now() - start > 15000 && !w.isVisible()) w.show()
    }
    if (!w.isDestroyed()) w.hide()
    throw new Error('網站驗證逾時，請稍後再試')
  }
  const p = queue.catch(() => {}).then(run)
  queue = p
  return p
}

const abs = (u) => (!u ? BASE : /^https?:/.test(u) ? u : BASE + (u.startsWith('/') ? '' : '/') + u)

/** 抓網站頁面的 HTML */
async function get(url) {
  const full = abs(url)
  let res
  try {
    // 帶登入的 cookie：個人頁（收藏、觀看記錄）要用
    res = await session.defaultSession.fetch(full, { credentials: 'include', headers: { 'User-Agent': ua, 'Accept-Language': 'zh-TW,zh;q=0.9,en;q=0.8' } })
  } catch (e) {
    const msg = e.message || String(e)
    throw new Error(/net::ERR_/.test(msg) ? `連不上網站（網路中斷或網站被擋，${msg.replace(/^.*net::/, '')}）` : msg)
  }
  const text = await res.text()
  if (isChallenge(res.status, text)) return loadViaWindow(full)
  if (res.status === 404) throw new Error('找不到這個頁面（影片可能已被刪除）')
  if (!res.ok) throw new Error(`網站回應 HTTP ${res.status}`)
  return text
}

/**
 * 網站的 JSON API（登入、收藏、觀看記錄），登入的 cookie 自動帶上。
 * 回傳 { status, text }，4xx 不丟例外（401 = 沒登入、422 = 帳密錯誤，由頁面判斷）
 */
async function api(method, url, json) {
  const full = abs(url)
  const res = await session.defaultSession.fetch(full, {
    method,
    credentials: 'include',
    headers: {
      'User-Agent': ua,
      Accept: 'application/json',
      'Content-Type': 'application/json',
      'X-Requested-With': 'XMLHttpRequest',
      Referer: BASE + '/',
      Origin: BASE,
    },
    body: method === 'GET' ? undefined : JSON.stringify(json || {}),
  })
  const text = await res.text()
  if (isChallenge(res.status, text)) {
    // 先用網頁過一次驗證再重送
    await loadViaWindow(BASE + '/')
    return api(method, url, json)
  }
  // 登入後的 cookie 馬上寫進磁碟，程式當掉也不會掉登入
  if (/\/api\/login/.test(full)) await session.defaultSession.cookies.flushStore().catch(() => {})
  return { status: res.status, text }
}

/** 登出：通知網站，再把網站的 cookie 清掉 */
async function logout() {
  try {
    await session.defaultSession.fetch(BASE + '/logout', { credentials: 'include', headers: { 'User-Agent': ua } })
  } catch {}
  const s = session.defaultSession
  const host = hostOf(BASE)
  for (const c of await s.cookies.get({})) {
    const d = (c.domain || '').replace(/^\./, '')
    if (d === host || host.endsWith('.' + d)) {
      const url = `http${c.secure ? 's' : ''}://${d}${c.path || '/'}`
      await s.cookies.remove(url, c.name).catch(() => {})
    }
  }
  await s.cookies.flushStore().catch(() => {})
}

/** 抓 CDN 上的檔案（下載用）：在請求本身帶 Referer / Origin */
async function cdnFetch(url, signal) {
  allowMedia(url)
  const res = await session.defaultSession.fetch(url, { headers: { 'User-Agent': ua, ...siteHeaders() }, signal })
  if (!res.ok) {
    res.body?.cancel().catch(() => {})
    throw new Error(`HTTP ${res.status}`)
  }
  return res
}

module.exports = { init, get, api, logout, cdnFetch, allowMedia, BASE }
