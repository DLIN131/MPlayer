// 網站沒有 API，全部從 HTML 解析（和 Android 版的 Site.kt 同一套規則）；網站改版時要改的就是這個檔案
export const BASE = await window.hub.base()

export const categories = [
  ['/dm539/new', '最近更新'],
  ['/dm635/release', '新作上市'],
  ['/dm278/chinese-subtitle', '中文字幕'],
  ['/dm301/today-hot', '今日熱門'],
  ['/dm170/weekly-hot', '本週熱門'],
  ['/dm273/monthly-hot', '本月熱門'],
  ['/dm817/uncensored-leak', '無碼流出'],
  ['/genres/VR', 'VR'],
  ['/dm36/siro', 'SIRO'],
  ['/dm34/luxu', 'LUXU'],
  ['/dm34/gana', 'GANA'],
  ['/dm1004/maan', 'PRESTIGE'],
  ['/dm597/fc2', 'FC2'],
  ['/dm2208642/heyzo', 'HEYZO'],
  ['/dm42/tokyohot', '東京熱'],
  ['/dm4551174/1pondo', '一本道'],
  ['/dm6840521/caribbeancom', 'Caribbeancom'],
  ['/dm6238682/10musume', '10musume'],
  ['/dm63/madou', '麻豆'],
]

/** 排序：網址參數、顯示名稱（'' 是網站預設） */
export const sorts = [
  ['', '預設'],
  ['released_at', '發行日期'],
  ['published_at', '最近更新'],
  ['saved', '收藏數'],
  ['today_views', '今日瀏覽'],
  ['weekly_views', '本週瀏覽'],
  ['monthly_views', '本月瀏覽'],
  ['views', '總瀏覽數'],
]

/** 女優頁的演出人數篩選（網站端） */
export const casts = [
  ['', '全部'],
  ['individual', '單人作品'],
  ['multiple', '多人作品'],
]

/** 有碼／無碼：網站沒有這個參數，無碼片的番號都帶 uncensored，在程式端過濾 */
export const censors = [
  ['', '全部'],
  ['censored', '有碼'],
  ['uncensored', '無碼'],
]
export const isUncensored = (v) => v.code.includes('uncensored')
export const censorAccept = (censor, v) => (censor === 'censored' ? !isUncensored(v) : censor === 'uncensored' ? isUncensored(v) : true)

export const isActressUrl = (url) => url.includes('/actresses/')
export const abs = (u) => (!u ? '' : new URL(u, BASE).href)
export const searchUrl = (q) => abs('/search/' + encodeURIComponent(q.trim()))
/** 網站每部片都有一段幾秒的預覽短片，跟封面放在同一個資料夾 */
export const previewUrl = (v) => (v.cover && v.cover.includes('/') ? v.cover.slice(0, v.cover.lastIndexOf('/')) + '/preview.mp4' : '')

const parser = new DOMParser()
async function doc(url) {
  return parser.parseFromString(await window.hub.get(url), 'text/html')
}

function withParams(url, page, sort = '', filters = '') {
  const u = new URL(abs(url))
  if (filters) u.searchParams.set('filters', filters)
  if (sort) u.searchParams.set('sort', sort)
  if (page > 1) u.searchParams.set('page', page)
  return u.href
}

const hasNext = (d, page) => !!d.querySelector(`a[href*="page=${page + 1}"]`)

function parseCards(d) {
  const out = new Map()
  for (const card of d.querySelectorAll('div.thumbnail')) {
    const a = card.querySelector('a[href]')
    if (!a) continue
    const code = abs(a.getAttribute('href')).split('/').pop().split(/[?#]/)[0]
    if (!code || out.has(code)) continue
    const img = card.querySelector('img')
    const cover = abs(img?.getAttribute('data-src') || img?.getAttribute('src') || '')
    const title = (card.querySelector('div.my-2 a')?.textContent || img?.getAttribute('alt') || '').trim().replace(/\s+/g, ' ')
    const duration = (card.querySelector('span.absolute')?.textContent || '').trim()
    out.set(code, { code, title, cover: cover.startsWith('data:') ? '' : cover, duration })
  }
  return [...out.values()]
}

/** 分類／搜尋／女優／類型頁 */
export async function list(url, page, sort = '', filters = '') {
  const d = await doc(withParams(url, page, sort, filters))
  const items = parseCards(d)
  return { items, hasMore: items.length > 0 && hasNext(d, page) }
}

export async function actresses(page) {
  const d = await doc(withParams('/actresses', page))
  const items = []
  for (const li of d.querySelectorAll('li')) {
    const name = li.querySelector('h4')?.textContent.trim()
    const a = li.querySelector('a[href]')
    if (!name || !a) continue
    items.push({
      name,
      url: abs(a.getAttribute('href')),
      avatar: abs(li.querySelector('img')?.getAttribute('src') || ''),
      info: [...li.querySelectorAll('p')].map((p) => p.textContent.trim()).join(' · '),
    })
  }
  return { items, hasMore: items.length > 0 && hasNext(d, page) }
}

/** 影片頁：標題、封面、資訊列（演員、類型…可以點）、HLS 播放清單 */
export async function detail(code) {
  const html = await window.hub.get('/' + code)
  const d = parser.parseFromString(html, 'text/html')
  const rows = []
  for (const div of d.querySelectorAll('div.space-y-2 > div')) {
    const text = div.textContent.trim().replace(/\s+/g, ' ')
    const i = text.indexOf(':')
    const label = i > 0 ? text.slice(0, i).trim() : ''
    if (!label || label.length > 8 || rows.some((r) => r.label === label)) continue
    const links = [...div.querySelectorAll('a[href]')].map((a) => ({ name: a.textContent.trim(), url: abs(a.getAttribute('href')) })).filter((l) => l.name)
    rows.push({ label, text: text.slice(i + 1).trim(), links })
  }
  const cover = d.querySelector('meta[property="og:image"]')?.getAttribute('content') || ''
  const title = (d.querySelector('h1')?.textContent || '').trim().replace(/\s+/g, ' ')
  // 網站內部的影片編號（收藏、觀看記錄的 API 用）
  const itemId = (html.match(/\/api\/items\/([A-Za-z0-9]+)\/(?:save|view)/) || [])[1] || null
  return { code, title, cover, rows, playlist: findPlaylist(html), itemId }
}

/** 播放網址藏在 p,a,c,k,e,d 打包過的 JS 裡，先解開再找 m3u8 */
export function findPlaylist(html) {
  const m3u8 = /https?:\/\/[^'"\s]+?\.m3u8/g
  const packed = /eval\(function\(p,a,c,k,e,d\)\{.*?\}\('(.*?)',(\d+),(\d+),'(.*?)'\.split\('\|'\)/gs
  for (const m of html.matchAll(packed)) {
    const words = m[4].split('|')
    const radix = Math.min(36, Math.max(2, Number(m[2])))
    const code = m[1].replace(/\\'/g, "'").replace(/\b\w+\b/g, (w) => {
      const i = parseInt(w, radix)
      return !Number.isNaN(i) && i.toString(radix) === w.toLowerCase() && i < words.length && words[i] ? words[i] : w
    })
    const all = code.match(m3u8) || []
    const found = all.find((u) => u.endsWith('playlist.m3u8')) || all[0]
    if (found) return found
  }
  return (html.match(m3u8) || [])[0] || null
}

// ---------- 網站帳號 ----------

/**
 * 從網頁判斷有沒有登入：只有個人頁（收藏、觀看記錄）有使用者選單，
 * 一般列表頁是網站快取的，看不出來。回傳登入的帳號（電郵），null = 沒登入
 */
export function parseLoginEmail(html) {
  if (!html.includes("showDropdown = 'user'")) return null
  const m = html.match(/登入為\s*<\/p>\s*<p[^>]*>\s*([^<]+?)\s*<\/p>/)
  return (m && m[1].trim()) || '已登入'
}

export class NotLoggedIn extends Error {
  constructor() {
    super('登入已過期，請重新登入')
  }
}

/** 帳號的影片收藏／觀看記錄（path = /saved 或 /history）；同時回傳登入狀態 */
export async function accountList(path, page) {
  const html = await window.hub.get(withParams(path, page))
  const email = parseLoginEmail(html)
  if (!email) throw new NotLoggedIn()
  const d = parser.parseFromString(html, 'text/html')
  const items = parseCards(d)
  return { items, hasMore: items.length > 0 && hasNext(d, page), email }
}

/** 打開影片頁時通知網站（跟網站一樣會記一筆觀看記錄），順便拿有沒有收藏；沒登入回傳 null */
export async function itemView(itemId) {
  const r = await window.hub.api('GET', `/api/items/${itemId}/view`)
  if (r.status < 200 || r.status >= 300) return null
  try {
    const o = JSON.parse(r.text)
    return o.user ? !!o.saved : null
  } catch {
    return null
  }
}

/** 加入／移除帳號收藏 */
export async function setSaved(itemId, saved) {
  const r = await window.hub.api(saved ? 'POST' : 'DELETE', `/api/items/${itemId}/save`, {})
  if (r.status === 401) throw new NotLoggedIn()
  if (r.status < 200 || r.status >= 300) throw new Error(`網站回應 HTTP ${r.status}`)
}

/** 清除帳號的所有觀看記錄 */
export async function clearHistory() {
  const r = await window.hub.api('DELETE', '/api/history', {})
  if (r.status === 401) throw new NotLoggedIn()
  if (r.status < 200 || r.status >= 300) throw new Error(`網站回應 HTTP ${r.status}`)
}

/** 登入；成功回傳帳號（電郵），失敗丟出給使用者看的原因。密碼只送到網站，不會存 */
export async function login(email, password) {
  const r = await window.hub.api('POST', '/api/login', { email: email.trim(), password, remember: true })
  if (r.status >= 200 && r.status < 300) {
    const who = parseLoginEmail(await window.hub.get('/saved'))
    if (!who) throw new Error('登入沒有成功，請再試一次')
    return who
  }
  if (r.status === 422) {
    let msg = ''
    try {
      const o = JSON.parse(r.text)
      const first = o.errors && Object.values(o.errors)[0]
      msg = (Array.isArray(first) ? first[0] : first) || o.message || ''
    } catch {}
    throw new Error(msg || '電郵或密碼不正確')
  }
  if (r.status === 429) throw new Error('嘗試太多次了，請稍後再試')
  throw new Error(`登入失敗（HTTP ${r.status}）`)
}

export const logout = () => window.hub.logout()

// ---------- 用番號查女優 ----------

// 另一個整理女優名字的網站（素人、企劃片也有）；網址一樣只用 Base64 存
const WIKI = atob('aHR0cHM6Ly9hdi13aWtpLm5ldA==')
const wikiCache = new Map()

/** 番號後面的版本標記（無碼流出、中文字幕…）拿掉，轉大寫 */
export const normalizeCode = (code) =>
  String(code || '')
    .trim()
    .toLowerCase()
    .replace(/-(uncensored-leak|chinese-subtitle|english-subtitle|uncensored|leak)$/, '')
    .toUpperCase()

/** 搜尋結果：每筆 article.archive-list，番號和女優名字在 ul.post-meta 裡；搜尋是模糊比對，只留番號對得上的 */
export function parseWiki(html, code) {
  const q = normalizeCode(code)
  const esc = q.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const prefixed = new RegExp(`^\\d+${esc}$`)
  const d = parser.parseFromString(html, 'text/html')
  const out = new Map()
  for (const art of d.querySelectorAll('article.archive-list')) {
    const texts = [...art.querySelectorAll('ul.post-meta li')].map((li) => li.textContent.trim().toUpperCase())
    if (!texts.some((t) => t === q || prefixed.test(t))) continue
    for (const a of art.querySelectorAll('li.actress-name a')) {
      const name = a.textContent.trim()
      if (name && !out.has(name)) out.set(name, { name, url: a.getAttribute('href') })
    }
  }
  return [...out.values()]
}

export async function wikiActresses(code) {
  const q = normalizeCode(code)
  if (wikiCache.has(q)) return wikiCache.get(q)
  const list = parseWiki(await window.hub.get(`${WIKI}/?s=${encodeURIComponent(q)}`), q)
  wikiCache.set(q, list)
  return list
}
