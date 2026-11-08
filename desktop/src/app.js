import { store } from './store.js'
import * as site from './site.js'

// ---------- 小工具 ----------

const $ = (s, root = document) => root.querySelector(s)
const view = $('#view')

/** 建立元素：h('div', { class, onclick, … }, 子元素…)；子元素可以是陣列、字串，null / false 會略過 */
function h(tag, attrs = {}, ...kids) {
  const el = document.createElement(tag)
  for (const [k, v] of Object.entries(attrs || {})) {
    if (v === null || v === undefined || v === false) continue
    if (k.startsWith('on')) el.addEventListener(k.slice(2), v)
    else if (k === 'class') el.className = v
    else if (k === 'style' && typeof v === 'object') Object.assign(el.style, v)
    else if (k in el && typeof v !== 'string') el[k] = v
    else el.setAttribute(k, v === true ? '' : v)
  }
  for (const kid of kids.flat(Infinity)) {
    if (kid === null || kid === undefined || kid === false) continue
    el.append(kid instanceof Node ? kid : String(kid))
  }
  return el
}

const errText = (e) => String(e?.message || e).replace(/^Error invoking remote method '[^']+': (Error: )?/, '')

let toastTimer = null
function toast(msg, ms = 2600) {
  const t = $('#toast')
  t.textContent = msg
  t.classList.remove('hidden')
  clearTimeout(toastTimer)
  toastTimer = setTimeout(() => t.classList.add('hidden'), ms)
}

const spinner = () => h('div', { class: 'spinner' })
const loading = () => h('div', { class: 'status' }, spinner())
const status = (msg) => h('div', { class: 'status' }, msg)
const errorBox = (e, retry) => h('div', { class: 'status error' }, h('div', {}, errText(e)), retry ? h('button', { class: 'btn', onclick: retry }, '重試') : null)

/** 秒數 → h:mm:ss */
function fmtTime(sec) {
  sec = Math.max(0, Math.floor(sec || 0))
  const hh = Math.floor(sec / 3600)
  const mm = Math.floor((sec % 3600) / 60)
  const ss = String(sec % 60).padStart(2, '0')
  return hh ? `${hh}:${String(mm).padStart(2, '0')}:${ss}` : `${mm}:${ss}`
}
const fmtSize = (b) => (b >= 1 << 30 ? `${(b / (1 << 30)).toFixed(2)} GB` : `${Math.round(b / (1 << 20))} MB`)
/** 片長字串（1:23:45 或 23:45）→ 秒數，估算下載大小用 */
const durationSeconds = (t) => String(t || '').split(':').map(Number).filter((n) => !Number.isNaN(n)).reduce((a, n) => a * 60 + n, 0)

// ---------- 本機資料：收藏、觀看紀錄、播放進度 ----------

/** 標題通常以番號開頭；番號另外顯示時把它拿掉 */
const stripCode = (title, code) => {
  const t = String(title || '').trim()
  const c = String(code || '').replace(/-uncensored-leak$|-chinese-subtitle$/i, '')
  return c && t.toLowerCase().startsWith(c.toLowerCase()) ? t.slice(c.length).trim() || t : t
}

const brief = (v) => ({ code: v.code, title: v.title || '', cover: v.cover || '', duration: v.duration || '' })

const favorites = () => store.get('favorites', [])
const isFav = (code) => favorites().some((v) => v.code === code)
function toggleFav(v) {
  const list = favorites().filter((x) => x.code !== v.code)
  const on = list.length === favorites().length
  if (on) list.unshift(brief(v))
  store.set('favorites', list)
  return on
}

const history = () => store.get('history', [])
function addHistory(v) {
  store.set('history', [brief(v), ...history().filter((x) => x.code !== v.code)].slice(0, 500))
}

/** 播放進度（秒），看完會清掉 */
const getPos = (code) => store.get('pos', {})[code] || 0
function setPos(code, sec) {
  const all = { ...store.get('pos', {}) }
  if (sec > 0) all[code] = Math.floor(sec)
  else delete all[code]
  store.set('pos', all)
}

// ---------- 網站帳號：登入後收藏、觀看紀錄用網站上的（手機、電視、電腦、網頁同一份） ----------

const account = { email: store.get('account', null), expired: false }
const accountListeners = new Set()

function setAccount(email, { expired = false } = {}) {
  const changed = email !== account.email
  account.email = email
  account.expired = expired
  store.set('account', email)
  renderAccountBtn()
  if (changed) for (const fn of accountListeners) fn()
}

/** 帳號列表的讀取器；網站說沒登入（過期、在別處登出）就改回本機模式 */
const accountLoader = (path) => async (page) => {
  try {
    return await site.accountList(path, page)
  } catch (e) {
    if (e instanceof site.NotLoggedIn) setAccount(null, { expired: true })
    throw e
  }
}

/** 啟動時確認上次的登入還有效 */
async function verifyAccount() {
  if (!account.email) return
  try {
    const r = await site.accountList('/saved', 1)
    setAccount(r.email)
  } catch (e) {
    if (e instanceof site.NotLoggedIn) setAccount(null, { expired: true })
  }
}

function renderAccountBtn() {
  const b = $('#accountBtn')
  if (!b) return
  b.textContent = account.email ? `👤 ${account.email}` : account.expired ? '登入已過期・重新登入' : '登入'
  b.classList.toggle('expired', !account.email && account.expired)
  b.title = account.email ? '帳號（收藏和觀看紀錄跟手機、電視、網站同步）' : '登入網站帳號，同步收藏和觀看紀錄'
}

/** 登入視窗：帳密直接送到網站，程式不會存密碼 */
function loginDialog() {
  const email = h('input', { type: 'email', placeholder: '電郵', autocomplete: 'username', value: account.email || '' })
  const password = h('input', { type: 'password', placeholder: '密碼', autocomplete: 'current-password' })
  const err = h('div', { class: 'login-err' })
  const submit = h('button', { class: 'btn primary', type: 'submit' }, '登入')
  const form = h(
    'form',
    {
      class: 'login-form',
      onsubmit: async (e) => {
        e.preventDefault()
        if (!email.value.trim() || !password.value) return
        submit.disabled = true
        submit.textContent = '登入中…'
        err.textContent = ''
        try {
          const who = await site.login(email.value, password.value)
          setAccount(who)
          closeOverlay()
          toast(`已登入 ${who}`)
          offerMerge()
        } catch (ex) {
          err.textContent = errText(ex)
          submit.disabled = false
          submit.textContent = '登入'
        }
      },
    },
    email,
    password,
    err,
    submit,
    h('div', { class: 'muted small' }, '使用網站的帳號。密碼只會送到網站，不會存在電腦上。'),
  )
  openOverlay(h('h2', {}, '登入網站帳號'), h('div', { class: 'muted' }, '登入後收藏和觀看紀錄會跟手機、電視和網站同步。'), form)
  setTimeout(() => (account.email ? password : email).focus(), 50)
}

async function logout() {
  if (!confirm('登出帳號？登出後收藏和觀看紀錄改回只存在這台電腦上，帳號裡的資料不會刪除。')) return
  await site.logout().catch(() => {})
  setAccount(null)
  toast('已登出')
}

/** 把本機收藏加到帳號：每部先讀影片頁拿網站內部編號（不會記觀看記錄） */
async function uploadLocalFavorites(onProgress) {
  const list = [...favorites()].reverse() // 舊的先加，帳號裡的順序跟本機一樣
  let ok = 0
  for (let i = 0; i < list.length; i++) {
    onProgress(i, list.length)
    try {
      const d = await site.detail(list[i].code)
      if (d.itemId) {
        await site.setSaved(d.itemId, true)
        ok++
      }
    } catch (e) {
      if (e instanceof site.NotLoggedIn) throw e
    }
  }
  onProgress(list.length, list.length)
  store.set('mergeAsked', [...new Set([...store.get('mergeAsked', []), account.email])])
  return ok
}

function mergeDialog() {
  const total = favorites().length
  const msg = h('div', {}, `這台電腦上有 ${total} 部收藏，要加到網站帳號嗎？加入後在手機、電視和網站也看得到。`)
  const bar = h('div', { class: 'bar hidden' }, h('div', { style: { width: '0%' } }))
  const go = h('button', { class: 'btn primary' }, '加入')
  const no = h('button', { class: 'btn' }, '不用')
  no.onclick = () => {
    store.set('mergeAsked', [...new Set([...store.get('mergeAsked', []), account.email])])
    closeOverlay()
  }
  go.onclick = async () => {
    go.disabled = no.disabled = true
    bar.classList.remove('hidden')
    try {
      const ok = await uploadLocalFavorites((d, t) => {
        msg.textContent = `加入中 ${d} / ${t}`
        bar.firstChild.style.width = `${t ? (d * 100) / t : 0}%`
      })
      msg.textContent = ok === total ? `已加入 ${ok} 部收藏` : `已加入 ${ok} 部收藏（${total - ok} 部無法加入，可能已下架）`
    } catch (e) {
      msg.textContent = `加入失敗：${errText(e)}`
    }
    go.remove()
    no.disabled = false
    no.textContent = '關閉'
    no.onclick = closeOverlay
    if (cur?.kind === 'favorites') nav('favorites', {}, { root: true })
  }
  openOverlay(h('h2', {}, '加入帳號收藏？'), msg, bar, h('div', { class: 'row-btns' }, go, no))
}

/** 剛登入、本機有收藏、這個帳號還沒問過 → 問要不要加到帳號 */
function offerMerge() {
  if (account.email && favorites().length && !store.get('mergeAsked', []).includes(account.email)) mergeDialog()
}

// 登入狀態變了：收藏／觀看紀錄／設定頁換成對應的內容
accountListeners.add(() => {
  if (cur && ['favorites', 'history', 'settings'].includes(cur.kind)) nav(cur.kind, {}, { root: true })
})

// ---------- 導覽：頁面堆疊（返回時列表和捲動位置都還在） ----------

const TABS = [
  ['home', '首頁'],
  ['actresses', '女優'],
  ['favorites', '收藏'],
  ['history', '觀看紀錄'],
  ['downloads', '下載'],
  ['settings', '設定'],
]
let stack = []
let cur = null

function renderTabs() {
  $('#tabs').replaceChildren(
    ...TABS.map(([k, label]) => h('button', { class: `tab${cur?.tab === k ? ' active' : ''}`, 'data-tab': k, onclick: () => nav(k, {}, { root: true }) }, label, k === 'downloads' ? h('span', { class: 'badge', id: 'dlBadge' }) : null)),
  )
  updateDlBadge()
}

/** 開新頁面；root = 從上方分頁點進來（清掉返回紀錄） */
function nav(kind, params = {}, { root = false } = {}) {
  if (cur) {
    cur.scroll = view.scrollTop
    cur.hide?.()
    if (root) {
      for (const p of stack) p.dispose?.()
      cur.dispose?.()
      stack = []
    } else stack.push(cur)
  }
  const page = PAGES[kind](params)
  page.kind = kind
  page.tab = TABS.some(([k]) => k === kind) ? kind : root ? kind : cur?.tab || 'home'
  cur = page
  view.replaceChildren(page.el)
  view.scrollTop = 0
  renderTabs()
  $('#navBack').disabled = !stack.length
}

function goBack() {
  if (!$('#player').classList.contains('hidden')) return closePlayer()
  if (!$('#overlay').classList.contains('hidden')) return closeOverlay()
  if (!stack.length) return
  cur.dispose?.()
  cur = stack.pop()
  view.replaceChildren(cur.el)
  view.scrollTop = cur.scroll || 0
  cur.show?.()
  renderTabs()
  $('#navBack').disabled = !stack.length
}

// 捲到底自動載下一頁
view.addEventListener('scroll', () => {
  if (view.scrollHeight - view.scrollTop - view.clientHeight < 900) cur?.more?.()
})

// ---------- 卡片、格狀列表 ----------

/** 影片卡片；滑鼠停一下播放預覽短片 */
function videoCard(v) {
  const thumb = h('div', { class: 'thumb' }, v.cover ? h('img', { src: v.cover, loading: 'lazy', alt: '' }) : null, v.duration ? h('span', { class: 'dur' }, v.duration) : null)
  const card = h('div', { class: 'card', title: v.title, 'data-code': v.code, onclick: () => openVideo(v) }, thumb, h('div', { class: 'card-title' }, v.title || v.code.toUpperCase()))
  const prog = getPos(v.code)
  if (prog > 5) thumb.append(h('span', { class: 'resume' }, `看到 ${fmtTime(prog)}`))
  let timer = null
  let vid = null
  card.addEventListener('mouseenter', () => {
    const url = site.previewUrl(v)
    if (!url) return
    timer = setTimeout(() => {
      vid = h('video', { class: 'preview', src: url, muted: true, autoplay: true, loop: true, playsInline: true })
      vid.addEventListener('error', () => vid?.remove())
      thumb.append(vid)
    }, 350)
  })
  card.addEventListener('mouseleave', () => {
    clearTimeout(timer)
    if (vid) {
      vid.pause()
      vid.removeAttribute('src')
      vid.load()
      vid.remove()
      vid = null
    }
  })
  return card
}

/**
 * 可以一直往下捲的影片格狀列表。
 * loader(page) → { items, hasMore }；filter 是程式端的篩選（有碼／無碼），改了就重排，不用重新抓。
 */
function feed(loader, filter = () => true) {
  const grid = h('div', { class: 'grid' })
  const foot = h('div', { class: 'foot' })
  const items = []
  const seen = new Set()
  let page = 0
  let busy = false
  let end = false
  let token = 0

  async function more() {
    if (busy || end) return
    busy = true
    const my = token
    foot.replaceChildren(loading())
    try {
      const r = await loader(page + 1)
      if (my !== token) return
      page++
      const fresh = r.items.filter((v) => !seen.has(v.code))
      fresh.forEach((v) => seen.add(v.code))
      items.push(...fresh)
      grid.append(...fresh.filter(filter).map(videoCard))
      if (!r.hasMore || !r.items.length) end = true
      foot.replaceChildren(end && !grid.children.length ? status('沒有內容') : '')
    } catch (e) {
      if (my === token) foot.replaceChildren(errorBox(e, more))
    } finally {
      if (my === token) busy = false
    }
    // 畫面還沒填滿（或篩選後太少）就繼續載
    if (my === token && !end) requestAnimationFrame(() => {
      if (grid.isConnected && view.scrollHeight - view.scrollTop - view.clientHeight < 900) more()
    })
  }

  return {
    el: h('div', {}, grid, foot),
    more,
    refilter(fn) {
      filter = fn
      grid.replaceChildren(...items.filter(filter).map(videoCard))
      if (!end) more()
      else foot.replaceChildren(grid.children.length ? '' : status('沒有內容'))
    },
    reset(newLoader) {
      token++
      loader = newLoader
      items.length = 0
      seen.clear()
      page = 0
      busy = false
      end = false
      grid.replaceChildren()
      more()
    },
  }
}

/** 一排單選標籤 */
function chips(options, selected, onSelect) {
  const row = h('div', { class: 'chips' })
  const draw = (sel) =>
    row.replaceChildren(
      ...options.map(([k, label]) =>
        h('button', {
          class: `chip${k === sel ? ' active' : ''}`,
          onclick: () => {
            if (k === sel) return
            draw(k)
            onSelect(k)
          },
        }, label),
      ),
    )
  draw(selected)
  return row
}

const pageHead = (title, ...right) => h('div', { class: 'page-head' }, h('h1', {}, title), h('div', { class: 'spacer' }), ...right)

/** 沒登入時，收藏／觀看紀錄頁上方的提示 */
const loginHint = () => h('button', { class: 'btn', onclick: loginDialog }, '登入網站帳號，跨裝置同步')

/** 設定頁的「網站帳號」區塊 */
function accountSection() {
  if (!account.email) {
    return h(
      'section',
      {},
      h('h2', {}, '網站帳號'),
      account.expired ? h('div', { class: 'row', style: { color: 'var(--accent)' } }, '登入已過期，請重新登入') : null,
      h('div', { class: 'row muted' }, '登入網站帳號後，收藏和觀看紀錄會跟手機、電視和網站同步。'),
      h('div', { class: 'row' }, h('button', { class: 'btn primary', onclick: loginDialog }, '登入')),
    )
  }
  const local = favorites().length
  return h(
    'section',
    {},
    h('h2', {}, '網站帳號'),
    h('div', { class: 'row' }, '已登入：', h('b', {}, account.email)),
    h('div', { class: 'row muted' }, '「收藏」和「觀看紀錄」現在顯示帳號裡的，打開影片頁也會記進帳號。播放進度只存在這台電腦。'),
    h(
      'div',
      { class: 'row' },
      local ? h('button', { class: 'btn', onclick: mergeDialog }, `把電腦上的 ${local} 部收藏加到帳號`) : null,
      h('button', { class: 'btn', onclick: logout }, '登出'),
    ),
  )
}

// ---------- 頁面 ----------

const PAGES = {
  home() {
    const saved = store.get('home', {})
    let cat = site.categories.some(([k]) => k === saved.cat) ? saved.cat : site.categories[0][0]
    let sort = saved.sort || ''
    const f = feed((p) => site.list(cat, p, sort))
    const reload = () => {
      store.set('home', { cat, sort })
      f.reset((p) => site.list(cat, p, sort))
    }
    const el = h(
      'div',
      { class: 'page' },
      chips(site.categories, cat, (k) => ((cat = k), reload())),
      h('div', { class: 'sort-row' }, h('span', { class: 'label' }, '排序'), chips(site.sorts, sort, (k) => ((sort = k), reload()))),
      f.el,
    )
    f.more()
    return { el, more: f.more }
  },

  /** 女優、類型、標籤、搜尋結果 */
  list({ title, url }) {
    let sort = ''
    let cast = ''
    let censor = ''
    const accept = (v) => site.censorAccept(censor, v)
    const f = feed((p) => site.list(url, p, sort, cast), accept)
    const reload = () => f.reset((p) => site.list(url, p, sort, cast))
    const actress = site.isActressUrl(url)
    const el = h(
      'div',
      { class: 'page' },
      pageHead(title),
      h('div', { class: 'sort-row' }, h('span', { class: 'label' }, '排序'), chips(site.sorts, sort, (k) => ((sort = k), reload()))),
      actress ? h('div', { class: 'sort-row' }, h('span', { class: 'label' }, '演出'), chips(site.casts, cast, (k) => ((cast = k), reload()))) : null,
      actress ? h('div', { class: 'sort-row' }, h('span', { class: 'label' }, '類型'), chips(site.censors, censor, (k) => ((censor = k), f.refilter(accept)))) : null,
      f.el,
    )
    f.more()
    return { el, more: f.more }
  },

  actresses() {
    const grid = h('div', { class: 'agrid' })
    const foot = h('div', { class: 'foot' })
    let page = 0
    let busy = false
    let end = false
    async function more() {
      if (busy || end) return
      busy = true
      foot.replaceChildren(loading())
      try {
        const r = await site.actresses(page + 1)
        page++
        grid.append(
          ...r.items.map((a) =>
            h('div', { class: 'acard', onclick: () => nav('list', { title: a.name, url: a.url }) }, h('img', { src: a.avatar, loading: 'lazy', alt: '' }), h('div', { class: 'aname' }, a.name), h('div', { class: 'ainfo' }, a.info)),
          ),
        )
        if (!r.hasMore) end = true
        foot.replaceChildren()
      } catch (e) {
        foot.replaceChildren(errorBox(e, more))
      } finally {
        busy = false
      }
    }
    more()
    return { el: h('div', { class: 'page' }, pageHead('女優一覽'), grid, foot), more }
  },

  favorites() {
    if (account.email) {
      const f = feed(accountLoader('/saved'))
      f.more()
      return {
        el: h('div', { class: 'page' }, pageHead('收藏', h('span', { class: 'muted small' }, `網站帳號 ${account.email}`)), f.el),
        more: f.more,
        // 在影片頁改過收藏，回來時重新載入
        show: () => f.reset(accountLoader('/saved')),
      }
    }
    const body = h('div')
    const draw = () => body.replaceChildren(favorites().length ? h('div', { class: 'grid' }, favorites().map(videoCard)) : status('還沒有收藏，在影片頁按「收藏」加入'))
    draw()
    return { el: h('div', { class: 'page' }, pageHead('收藏', loginHint()), body), show: draw }
  },

  history() {
    const clearLocal = () => {
      store.set('history', [])
      store.set('pos', {})
    }
    if (account.email) {
      const f = feed(accountLoader('/history'))
      const clear = h('button', {
        class: 'btn',
        onclick: async () => {
          if (!confirm('清除網站帳號的所有觀看紀錄（手機、電視、網站都會清掉）和這台電腦的播放進度？')) return
          try {
            await site.clearHistory()
            clearLocal()
            f.reset(accountLoader('/history'))
          } catch (e) {
            toast(`清除失敗：${errText(e)}`, 5000)
          }
        },
      }, '清除紀錄')
      f.more()
      return {
        el: h('div', { class: 'page' }, pageHead('觀看紀錄', h('span', { class: 'muted small' }, `網站帳號 ${account.email}`), clear), f.el),
        more: f.more,
        show: () => f.reset(accountLoader('/history')),
      }
    }
    const body = h('div')
    const draw = () => body.replaceChildren(history().length ? h('div', { class: 'grid' }, history().map(videoCard)) : status('還沒有觀看紀錄'))
    const clear = h('button', {
      class: 'btn',
      onclick: () => {
        if (!confirm('清除所有觀看紀錄和播放進度？')) return
        clearLocal()
        draw()
      },
    }, '清除紀錄')
    draw()
    return { el: h('div', { class: 'page' }, pageHead('觀看紀錄', loginHint(), clear), body), show: draw }
  },

  downloads() {
    const list = h('div', { class: 'dl-list' })
    const dirText = h('span', { class: 'muted' })
    window.hub.dl.dir().then((d) => (dirText.textContent = d))
    const draw = () => {
      const all = [...jobs.values()].sort((a, b) => b.addedAt - a.addedAt)
      list.replaceChildren(...(all.length ? all.map(dlRow) : [status('還沒有下載，在影片頁按「下載」加入')]))
    }
    draw()
    const unsub = onJobs(draw)
    return {
      el: h('div', { class: 'page' }, pageHead('下載', h('button', { class: 'btn', onclick: () => window.hub.dl.openDir() }, '開啟資料夾')), h('div', { class: 'dl-dir' }, '下載位置：', dirText), list),
      show: draw,
      dispose: unsub,
    }
  },

  settings() {
    const dirText = h('span', { class: 'muted' })
    window.hub.dl.dir().then((d) => (dirText.textContent = d))
    const upd = h('span', { class: 'muted' })
    return {
      el: h(
        'div',
        { class: 'page settings' },
        pageHead('設定'),
        accountSection(),
        h(
          'section',
          {},
          h('h2', {}, '版本'),
          h('div', { class: 'row' }, h('b', {}, versionText()), upd),
          h(
            'div',
            { class: 'row' },
            h('button', {
              class: 'btn',
              onclick: async () => {
                upd.textContent = '檢查中…'
                const r = await checkUpdate(true)
                upd.textContent = { dev: '開發版不檢查更新', latest: '已經是最新版本', shown: '發現新版本（右下角）', error: '檢查失敗，請確認網路' }[r] || ''
              },
            }, '檢查更新'),
            h('button', { class: 'btn', onclick: () => showChangelog() }, '更新紀錄'),
          ),
        ),
        h(
          'section',
          {},
          h('h2', {}, '下載'),
          h('div', { class: 'row' }, '下載位置：', dirText),
          h(
            'div',
            { class: 'row' },
            h('button', { class: 'btn', onclick: async () => (dirText.textContent = await window.hub.dl.chooseDir()) }, '變更位置'),
            h('button', { class: 'btn', onclick: () => window.hub.dl.openDir() }, '開啟資料夾'),
          ),
          h('div', { class: 'muted small' }, '下載的影片會轉成 MP4，任何播放器都能開；預設放在程式資料夾裡的 downloads。'),
        ),
        h(
          'section',
          {},
          h('h2', {}, '播放器快捷鍵'),
          h(
            'ul',
            { class: 'keys' },
            h('li', {}, '空白鍵／K：暫停、播放'),
            h('li', {}, '← →：倒退、快轉 10 秒；Shift + ← →：1 分鐘'),
            h('li', {}, '↑ ↓：音量；M：靜音；F 或雙擊畫面中間：全螢幕'),
            h('li', {}, '畫面左右兩側：雙擊倒退／快轉 10 秒，三擊 1 分鐘'),
            h('li', {}, '按住畫面：2 倍速播放，放開恢復'),
            h('li', {}, 'Esc：離開全螢幕／關閉播放器'),
          ),
        ),
      ),
    }
  },

  video(v) {
    let d = null
    const cover = h('div', { class: 'vp-cover' }, v.cover ? h('img', { src: v.cover, alt: '' }) : null)
    const title = h('h1', { class: 'vp-title' }, stripCode(v.title, v.code) || v.code.toUpperCase())
    const actions = h('div', { class: 'vp-actions' }, loading())
    const rows = h('div', { class: 'vp-rows' })
    // 用番號查女優（素人、企劃片網站上常常沒寫），點名字就搜尋她的影片
    const wikiBody = h('div', { class: 'vp-text muted' }, '查詢中…')
    const wikiRow = h('div', { class: 'vp-row' }, h('div', { class: 'vp-label' }, '女優（番號查詢）'), wikiBody)
    site
      .wikiActresses(v.code)
      .then((list) =>
        wikiBody.replaceWith(
          list.length
            ? h('div', { class: 'vp-links' }, list.map((a) => h('button', { class: 'chip', onclick: () => nav('list', { title: `搜尋：${a.name}`, url: site.searchUrl(a.name) }) }, a.name)))
            : h('div', { class: 'vp-text muted' }, '查不到這部的女優'),
        ),
      )
      .catch(() => (wikiBody.textContent = '查詢失敗'))
    const favBtn = h('button', { class: 'btn' })
    // 登入時：這部在帳號收藏裡嗎（null = 還不知道）
    let remote = null
    let viewed = false
    const useRemote = () => !!(account.email && d?.itemId)
    const drawFav = () => {
      const on = useRemote() ? remote === true : isFav(v.code)
      favBtn.textContent = on ? '♥ 已收藏' : '♡ 收藏'
      favBtn.classList.toggle('on', on)
    }
    favBtn.onclick = async () => {
      if (!useRemote()) {
        toggleFav(full())
        return drawFav()
      }
      const target = remote !== true
      remote = target
      favBtn.disabled = true
      drawFav()
      try {
        await site.setSaved(d.itemId, target)
        toast(target ? '已加入帳號收藏' : '已從帳號收藏移除')
      } catch (e) {
        remote = !target
        if (e instanceof site.NotLoggedIn) setAccount(null, { expired: true })
        toast(`收藏失敗：${errText(e)}`, 5000)
      } finally {
        favBtn.disabled = false
        drawFav()
      }
    }
    drawFav()
    // 列表上的封面和片長比較準，存紀錄時沿用
    const full = () => ({ ...v, title: v.title || d?.title || '', cover: v.cover || d?.cover || '' })
    const dlBtn = h('button', { class: 'btn' })
    const drawDl = () => {
      const j = jobs.get(v.code.toLowerCase())
      dlBtn.disabled = false
      if (!j) dlBtn.textContent = '⬇ 下載'
      else if (j.status === 'done') dlBtn.textContent = j.missing ? '⬇ 重新下載（檔案已刪除）' : `✓ 已下載 ${j.height}p・播放本機檔案`
      else if (j.status === 'running') dlBtn.textContent = `下載中 ${pct(j)}%`
      else if (j.status === 'merging') dlBtn.textContent = '轉成 MP4 中…'
      else if (j.status === 'queued') dlBtn.textContent = '排隊中…'
      else if (j.status === 'paused') dlBtn.textContent = `▶ 繼續下載（${pct(j)}%）`
      else dlBtn.textContent = '下載失敗・重試'
    }
    dlBtn.onclick = () => {
      const j = jobs.get(v.code.toLowerCase())
      if (j?.status === 'done' && !j.missing) return openPlayer(full(), j.fileUrl, { local: true })
      if (j && (j.status === 'paused' || j.status === 'error')) return window.hub.dl.resume(j.id)
      if (j && j.status !== 'done') return nav('downloads', {}, { root: true })
      if (d?.playlist) chooseQuality(full(), d.playlist)
    }
    const unsub = onJobs(drawDl)

    async function load() {
      actions.replaceChildren(loading())
      try {
        d = await site.detail(v.code)
        // 跟網站一樣：登入時打開影片頁會記進帳號的觀看記錄，同時拿收藏狀態（只在第一次打開時）
        if (account.email && d.itemId && !viewed) {
          viewed = true
          remote = await site.itemView(d.itemId).catch(() => null)
          if (remote === null) verifyAccount()
        }
        drawFav()
        if (!v.title) title.textContent = stripCode(d.title, v.code)
        if (!v.cover && d.cover) cover.replaceChildren(h('img', { src: d.cover, alt: '' }))
        const resume = getPos(v.code)
        const play = d.playlist
          ? h('button', { class: 'btn primary big', onclick: () => openPlayer(full(), d.playlist) }, resume > 5 ? `▶ 繼續播放（${fmtTime(resume)}）` : '▶ 播放')
          : h('span', { class: 'muted' }, '找不到播放來源')
        drawDl()
        actions.replaceChildren(play, d.playlist ? dlBtn : null, favBtn)
        rows.replaceChildren(
          ...d.rows.map((r) =>
            h('div', { class: 'vp-row' }, h('div', { class: 'vp-label' }, r.label), r.links.length ? h('div', { class: 'vp-links' }, r.links.map((l) => h('button', { class: 'chip', onclick: () => nav('list', { title: l.name, url: l.url }) }, l.name))) : h('div', { class: 'vp-text' }, r.text)),
          ),
        )
      } catch (e) {
        actions.replaceChildren(errorBox(e, load), favBtn)
      }
    }
    load()
    return {
      el: h('div', { class: 'page video-page' }, cover, h('div', { class: 'vp-info' }, h('div', { class: 'vp-code' }, v.code.toUpperCase()), title, actions, rows, wikiRow)),
      // 從播放器回來：更新「繼續播放」的時間
      show: () => d && load(),
      dispose: unsub,
    }
  },
}

function openVideo(v) {
  nav('video', v)
}

// ---------- 下載 ----------

const jobs = new Map()
const jobListeners = new Set()
const pct = (j) => (j.total ? Math.floor((j.done * 100) / j.total) : 0)

function onJobs(fn) {
  jobListeners.add(fn)
  return () => jobListeners.delete(fn)
}
function jobsChanged() {
  for (const fn of jobListeners) fn()
  updateDlBadge()
}
function updateDlBadge() {
  const n = [...jobs.values()].filter((j) => ['running', 'queued', 'merging'].includes(j.status)).length
  const b = $('#dlBadge')
  if (b) b.textContent = n ? String(n) : ''
}

window.hub.dl.list().then((list) => {
  for (const j of list) jobs.set(j.id, j)
  jobsChanged()
})
window.hub.dl.onUpdate((j) => {
  const prev = jobs.get(j.id)
  jobs.set(j.id, j)
  if (prev && prev.status !== j.status) {
    if (j.status === 'done') toast(`下載完成：${j.code.toUpperCase()}${j.note ? '（' + j.note + '）' : ''}`)
    if (j.status === 'error') toast(`下載失敗：${j.code.toUpperCase()}，${j.error}`, 5000)
  }
  jobsChanged()
})
window.hub.dl.onRemoved((id) => {
  jobs.delete(id)
  jobsChanged()
})

/** 選畫質（顯示預估大小）後加入下載 */
async function chooseQuality(v, master) {
  const box = openOverlay(h('h2', {}, '選擇下載畫質'), loading())
  try {
    const variants = await window.hub.dl.options(master)
    if (!variants.length) throw new Error('找不到可以下載的畫質')
    const secs = durationSeconds(v.duration)
    box.lastChild.replaceWith(
      h(
        'div',
        { class: 'q-list' },
        variants.map((q) =>
          h('button', {
            class: 'q-item',
            onclick: async () => {
              closeOverlay()
              try {
                const j = await window.hub.dl.add({ ...brief(v), height: q.height, mediaUrl: q.url })
                jobs.set(j.id, j)
                jobsChanged()
                toast(`已加入下載：${v.code.toUpperCase()}（${q.height}p）`)
              } catch (e) {
                toast(`無法下載：${errText(e)}`, 5000)
              }
            },
          }, h('b', {}, `${q.height}p`), secs && q.bandwidth ? h('span', { class: 'muted' }, `約 ${fmtSize((q.bandwidth * secs) / 8)}`) : null),
        ),
      ),
    )
  } catch (e) {
    box.lastChild.replaceWith(errorBox(e))
  }
}

function dlRow(j) {
  const done = j.status === 'done'
  const statusText = {
    running: `下載中 ${pct(j)}%・${fmtSize(j.bytes || 0)}${j.speed ? `・${(j.speed / (1 << 20)).toFixed(1)} MB/s` : ''}`,
    queued: '排隊中',
    merging: '轉成 MP4 中…',
    paused: `已暫停 ${pct(j)}%・${fmtSize(j.bytes || 0)}`,
    error: `失敗：${j.error || '未知錯誤'}`,
    done: j.missing ? '檔案已被刪除' : `${j.height}p・${fmtSize(j.size || j.bytes || 0)}${j.note ? `・${j.note}` : ''}`,
  }[j.status] || j.status
  const v = { code: j.code, title: j.title, cover: j.cover, duration: j.duration }
  const btns = []
  if (done && !j.missing) {
    btns.push(h('button', { class: 'btn primary', onclick: () => openPlayer(v, j.fileUrl, { local: true }) }, '▶ 播放'))
    btns.push(h('button', { class: 'btn', onclick: () => window.hub.dl.show(j.id) }, '顯示檔案'))
  }
  if (j.status === 'running' || j.status === 'queued') btns.push(h('button', { class: 'btn', onclick: () => window.hub.dl.pause(j.id) }, '暫停'))
  if (j.status === 'paused' || j.status === 'error') btns.push(h('button', { class: 'btn', onclick: () => window.hub.dl.resume(j.id) }, '繼續'))
  btns.push(
    h('button', {
      class: 'btn',
      onclick: () => {
        const msg = done && !j.missing ? `刪除「${j.code.toUpperCase()}」的影片檔（${fmtSize(j.size || 0)}）？\n按「取消」只從清單移除、保留檔案。` : `移除「${j.code.toUpperCase()}」的下載？`
        if (done && !j.missing) {
          const del = confirm(msg)
          window.hub.dl.remove(j.id, del)
        } else if (confirm(msg)) window.hub.dl.remove(j.id, true)
      },
    }, '刪除'),
  )
  return h(
    'div',
    { class: `dl-row ${j.status}` },
    h('div', { class: 'dl-thumb', onclick: () => openVideo(v) }, j.cover ? h('img', { src: j.cover, alt: '' }) : null),
    h(
      'div',
      { class: 'dl-main' },
      h('div', { class: 'dl-title' }, h('b', {}, j.code.toUpperCase()), ' ', stripCode(j.title, j.code)),
      h('div', { class: `dl-status${j.status === 'error' ? ' error' : ''}` }, statusText),
      done ? null : h('div', { class: 'bar' }, h('div', { style: { width: `${pct(j)}%` } })),
    ),
    h('div', { class: 'dl-btns' }, btns),
  )
}

// ---------- 播放器 ----------

const SPEEDS = [0.75, 1, 1.25, 1.5, 2]
let player = null

/** 開啟播放器；src 是 HLS 播放清單，local 時是下載好的檔案網址 */
async function openPlayer(video, src, { local = false } = {}) {
  closePlayer()
  addHistory(video)
  const root = $('#player')
  root.classList.remove('hidden')
  const v = h('video', { class: 'pv', autoplay: true })
  v.volume = store.get('volume', 1)
  v.muted = store.get('muted', false)
  const hint = h('div', { class: 'p-hint hidden' })
  const wait = h('div', { class: 'p-wait' }, spinner())
  const err = h('div', { class: 'p-err hidden' })
  const played = h('div', { class: 'seek-played' })
  const buffered = h('div', { class: 'seek-buf' })
  const tip = h('div', { class: 'seek-tip hidden' })
  const seek = h('div', { class: 'seek' }, buffered, played, tip)
  const time = h('span', { class: 'p-time' }, '0:00 / 0:00')
  const playBtn = h('button', { class: 'pbtn', title: '暫停／播放（空白鍵）' }, '▶')
  const speedSel = h('select', { class: 'psel', title: '播放速度' }, SPEEDS.map((s) => h('option', { value: s, selected: s === 1 }, s === 1 ? '1x 倍速' : `${s}x`)))
  const qSel = h('select', { class: 'psel hidden', title: '畫質' })
  const vol = h('input', { class: 'pvol', type: 'range', min: 0, max: 1, step: 0.05, value: v.muted ? 0 : v.volume, title: '音量（↑ ↓）' })
  const fsBtn = h('button', { class: 'pbtn', title: '全螢幕（F）' }, '⛶')
  const closeBtn = h('button', { class: 'pbtn', title: '關閉（Esc）' }, '‹ 返回')
  const top = h('div', { class: 'p-top' }, closeBtn, h('div', { class: 'p-title' }, h('b', {}, video.code.toUpperCase()), ' ', stripCode(video.title, video.code)))
  const bottom = h(
    'div',
    { class: 'p-bottom' },
    seek,
    h(
      'div',
      { class: 'p-row' },
      playBtn,
      h('button', { class: 'pbtn', title: '倒退 10 秒（←）', onclick: () => seekBy(-10) }, '«10'),
      h('button', { class: 'pbtn', title: '快轉 10 秒（→）', onclick: () => seekBy(10) }, '10»'),
      time,
      h('div', { class: 'spacer' }),
      speedSel,
      qSel,
      vol,
      fsBtn,
    ),
  )
  root.replaceChildren(v, wait, hint, err, top, bottom)

  const p = { video, v, hls: null, timers: [], speed: 1, boosting: false }
  player = p
  const start = getPos(video.code)

  // ---- 載入 ----
  function showError(msg) {
    wait.classList.add('hidden')
    err.replaceChildren(h('div', {}, msg), h('button', { class: 'btn', onclick: () => openPlayer(video, src, { local }) }, '重試'))
    err.classList.remove('hidden')
  }
  if (local) {
    v.src = src
    v.addEventListener('loadedmetadata', () => start > 5 && start < v.duration - 5 && (v.currentTime = start), { once: true })
  } else if (window.Hls?.isSupported()) {
    await window.hub.media(src)
    if (player !== p) return
    const hls = new window.Hls({ maxBufferLength: 60, startPosition: start > 5 ? start : -1 })
    p.hls = hls
    let netRetry = 0
    let mediaRetry = 0
    hls.on(window.Hls.Events.MANIFEST_PARSED, (_e, data) => {
      const levels = data.levels.map((l, i) => ({ height: l.height, i })).sort((a, b) => b.height - a.height)
      if (levels.length > 1) {
        qSel.replaceChildren(h('option', { value: -1 }, '自動'), ...levels.map((l) => h('option', { value: l.i }, `${l.height}p`)))
        qSel.classList.remove('hidden')
        // 預設直接用最高畫質（有 1080p 就是 1080p）
        hls.currentLevel = levels[0].i
        qSel.value = String(levels[0].i)
      }
      v.play().catch(() => {})
    })
    hls.on(window.Hls.Events.ERROR, (_e, data) => {
      if (!data.fatal) return
      if (data.type === window.Hls.ErrorTypes.NETWORK_ERROR && netRetry++ < 3) {
        flash('網路不穩，重新連線中…')
        setTimeout(() => hls.startLoad(), 1500)
      } else if (data.type === window.Hls.ErrorTypes.MEDIA_ERROR && mediaRetry++ < 3) hls.recoverMediaError()
      else showError(`播放失敗（${data.details}）`)
    })
    qSel.onchange = () => {
      hls.currentLevel = Number(qSel.value)
      flash(qSel.value === '-1' ? '畫質：自動' : `畫質：${qSel.selectedOptions[0].textContent}`)
    }
    hls.loadSource(src)
    hls.attachMedia(v)
  } else showError('這台電腦不支援這種影片格式')
  if (start > 5) flash(`從 ${fmtTime(start)} 繼續播放`)

  // ---- 狀態顯示 ----
  const sync = () => {
    playBtn.textContent = v.paused ? '▶' : '❚❚'
    const d = v.duration || 0
    time.textContent = `${fmtTime(v.currentTime)} / ${fmtTime(d)}`
    played.style.width = d ? `${(v.currentTime / d) * 100}%` : '0'
    const b = v.buffered.length ? v.buffered.end(v.buffered.length - 1) : 0
    buffered.style.width = d ? `${(b / d) * 100}%` : '0'
  }
  for (const ev of ['timeupdate', 'play', 'pause', 'durationchange', 'progress']) v.addEventListener(ev, sync)
  v.addEventListener('waiting', () => wait.classList.remove('hidden'))
  for (const ev of ['playing', 'canplay', 'seeked']) v.addEventListener(ev, () => wait.classList.add('hidden'))
  v.addEventListener('error', () => local && showError('影片檔無法播放（可能已被移動或刪除）'))
  v.addEventListener('pause', () => savePos())
  v.addEventListener('ended', () => {
    setPos(video.code, 0)
    showControls()
  })
  v.addEventListener('volumechange', () => {
    store.set('volume', v.volume)
    store.set('muted', v.muted)
    vol.value = v.muted ? 0 : v.volume
  })
  p.timers.push(setInterval(() => !v.paused && savePos(), 5000))

  function savePos() {
    const d = v.duration || 0
    if (!v.currentTime) return
    // 看到最後 30 秒內就當作看完，下次從頭開始
    setPos(video.code, d && v.currentTime > d - 30 ? 0 : v.currentTime)
  }
  p.savePos = savePos

  // ---- 操作 ----
  const togglePlay = () => (v.paused ? v.play().catch(() => {}) : v.pause())
  function seekBy(sec) {
    const d = v.duration || 0
    v.currentTime = Math.max(0, Math.min(d ? d - 0.5 : Infinity, v.currentTime + sec))
    flash(sec >= 60 ? '快轉 1 分鐘' : sec > 0 ? `快轉 ${sec} 秒` : sec <= -60 ? '倒退 1 分鐘' : `倒退 ${-sec} 秒`)
  }
  p.seekBy = seekBy
  p.togglePlay = togglePlay
  function flash(text, ms = 900) {
    hint.textContent = text
    hint.classList.remove('hidden')
    clearTimeout(p.hintTimer)
    if (ms) p.hintTimer = setTimeout(() => hint.classList.add('hidden'), ms)
  }
  p.flash = flash
  const toggleFs = () => (document.fullscreenElement ? document.exitFullscreen() : root.requestFullscreen()).catch(() => {})
  p.toggleFs = toggleFs

  playBtn.onclick = togglePlay
  fsBtn.onclick = toggleFs
  closeBtn.onclick = closePlayer
  speedSel.onchange = () => {
    p.speed = Number(speedSel.value)
    v.playbackRate = p.speed
  }
  vol.oninput = () => {
    v.volume = Number(vol.value)
    v.muted = v.volume === 0
  }

  // 進度條：點、拖曳；滑過顯示時間
  const seekAt = (e) => {
    const r = seek.getBoundingClientRect()
    return Math.max(0, Math.min(1, (e.clientX - r.left) / r.width)) * (v.duration || 0)
  }
  seek.addEventListener('pointerdown', (e) => {
    seek.setPointerCapture(e.pointerId)
    v.currentTime = seekAt(e)
    const move = (ev) => (v.currentTime = seekAt(ev))
    seek.addEventListener('pointermove', move)
    seek.addEventListener('pointerup', () => seek.removeEventListener('pointermove', move), { once: true })
  })
  seek.addEventListener('mousemove', (e) => {
    const r = seek.getBoundingClientRect()
    tip.textContent = fmtTime(seekAt(e))
    tip.style.left = `${e.clientX - r.left}px`
    tip.classList.remove('hidden')
  })
  seek.addEventListener('mouseleave', () => tip.classList.add('hidden'))

  // 畫面上的滑鼠：按住 = 2 倍速；點一下 = 暫停／播放；左右兩側雙擊 ±10 秒、三擊 ±1 分鐘；中間雙擊 = 全螢幕
  let holdTimer = null
  let clicks = 0
  let clickTimer = null
  let lastX = 0
  v.addEventListener('pointerdown', (e) => {
    if (e.button !== 0) return
    holdTimer = setTimeout(() => {
      p.boosting = true
      v.playbackRate = 2
      if (v.paused) v.play().catch(() => {})
      flash('2 倍速播放中 ▶▶', 0)
    }, 450)
  })
  const endHold = () => {
    clearTimeout(holdTimer)
    if (!p.boosting) return false
    p.boosting = false
    v.playbackRate = p.speed
    hint.classList.add('hidden')
    return true
  }
  v.addEventListener('pointerleave', endHold)
  v.addEventListener('pointerup', (e) => {
    if (e.button !== 0) return
    if (endHold()) return
    clicks++
    lastX = e.clientX / root.clientWidth
    clearTimeout(clickTimer)
    const resolve = () => {
      const n = clicks
      clicks = 0
      const side = lastX < 0.33 ? -1 : lastX > 0.67 ? 1 : 0
      if (n === 1) togglePlay()
      else if (side === 0) toggleFs()
      else seekBy(side * (n >= 3 ? 60 : 10))
    }
    if (clicks >= 3) resolve()
    else clickTimer = setTimeout(resolve, 260)
  })
  v.addEventListener('dblclick', (e) => e.preventDefault())

  // 控制列：滑鼠一段時間沒動就藏起來（暫停時一直顯示）
  function showControls() {
    root.classList.remove('idle')
    clearTimeout(p.idleTimer)
    p.idleTimer = setTimeout(() => !v.paused && root.classList.add('idle'), 2500)
  }
  p.showControls = showControls
  root.addEventListener('mousemove', showControls)
  showControls()
  sync()
}

function closePlayer() {
  const p = player
  if (!p) return
  player = null
  p.savePos?.()
  p.timers.forEach(clearInterval)
  clearTimeout(p.idleTimer)
  clearTimeout(p.hintTimer)
  p.hls?.destroy()
  p.v.pause()
  p.v.removeAttribute('src')
  p.v.load()
  if (document.fullscreenElement) document.exitFullscreen().catch(() => {})
  const root = $('#player')
  root.classList.add('hidden')
  root.replaceChildren()
  // 影片頁的「繼續播放」時間要更新
  cur?.show?.()
}

document.addEventListener('keydown', (e) => {
  const p = player
  if (!p) return
  if (e.target.tagName === 'INPUT' && e.target.type !== 'range') return
  const v = p.v
  const k = e.key
  let handled = true
  if (k === ' ' || k === 'k' || k === 'K') p.togglePlay()
  else if (k === 'ArrowLeft' || k === 'j' || k === 'J') p.seekBy(e.shiftKey ? -60 : -10)
  else if (k === 'ArrowRight' || k === 'l' || k === 'L') p.seekBy(e.shiftKey ? 60 : 10)
  else if (k === 'ArrowUp') {
    v.muted = false
    v.volume = Math.min(1, v.volume + 0.1)
    p.flash(`音量 ${Math.round(v.volume * 100)}%`)
  } else if (k === 'ArrowDown') {
    v.volume = Math.max(0, v.volume - 0.1)
    p.flash(`音量 ${Math.round(v.volume * 100)}%`)
  } else if (k === 'm' || k === 'M') {
    v.muted = !v.muted
    p.flash(v.muted ? '靜音' : '取消靜音')
  } else if (k === 'f' || k === 'F') p.toggleFs()
  else if (k === 'Escape') {
    if (document.fullscreenElement) document.exitFullscreen().catch(() => {})
    else closePlayer()
  } else handled = false
  if (handled) {
    e.preventDefault()
    e.stopPropagation()
    p.showControls()
  }
}, true)

// ---------- 返回鍵 ----------

document.addEventListener('keydown', (e) => {
  if (player) return
  const typing = e.target.tagName === 'INPUT' || e.target.tagName === 'TEXTAREA'
  if ((e.altKey && e.key === 'ArrowLeft') || (e.key === 'Backspace' && !typing)) {
    e.preventDefault()
    goBack()
  } else if (e.key === 'Escape' && !$('#overlay').classList.contains('hidden')) closeOverlay()
})
window.addEventListener('mouseup', (e) => {
  if (e.button === 3) {
    e.preventDefault()
    goBack()
  }
})

// ---------- 對話框 ----------

function openOverlay(...kids) {
  const box = h('div', { class: 'ov-box' }, h('button', { class: 'close', onclick: closeOverlay, title: '關閉 (Esc)' }, '×'), ...kids)
  $('#overlay').replaceChildren(box)
  $('#overlay').classList.remove('hidden')
  return box
}
function closeOverlay() {
  $('#overlay').classList.add('hidden')
  $('#overlay').replaceChildren()
}
$('#overlay').addEventListener('click', (e) => e.target.id === 'overlay' && closeOverlay())

// ---------- 程式內更新 / 更新紀錄 ----------

const REPO = 'DLIN131/MPlayer'
const ZIP_NAME = 'MPlayer-Windows.zip'

/** 目前版本的文字；version.js 由 CI 寫入（APP_VERSION = run_number），本機開發是 0 */
const versionText = () => (Number(window.APP_VERSION) ? `v1.0.${Number(window.APP_VERSION)}` : '開發版')

/** 發布說明：第一行是標題，下面是條列；--- 以下是固定的下載網址，Co-Authored-By 也不用給使用者看 */
const noteLines = (body) =>
  String(body || '')
    .split('\n---')[0]
    .split('\n')
    .map((l) => l.trim())
    .filter((l) => l && !l.startsWith('Co-Authored-By'))

/** 從 GitHub 的發布說明拿每一版的內容；抓不到（離線）就用上次存的 */
async function fetchChangelog() {
  try {
    const ctl = new AbortController()
    const t = setTimeout(() => ctl.abort(), 8000)
    const res = await fetch(`https://api.github.com/repos/${REPO}/releases?per_page=100`, { headers: { Accept: 'application/vnd.github+json' }, signal: ctl.signal })
    clearTimeout(t)
    if (!res.ok) throw new Error(`HTTP ${res.status}`)
    const list = (await res.json())
      .map((r) => {
        const code = Number(String(r.tag_name || '').split('.').pop()) || 0
        const lines = noteLines(r.body)
        return { code, date: (r.published_at || '').slice(0, 10), title: lines[0] || '', items: lines.slice(1).map((l) => l.replace(/^[-*•]\s*/, '')) }
      })
      .filter((r) => r.code)
      .sort((a, b) => b.code - a.code)
    store.set('changelog', list)
    return list
  } catch {
    return store.get('changelog', [])
  }
}

/** 更新紀錄視窗；since 有值時只顯示比它新的版本（剛更新完用） */
async function showChangelog({ since = 0, title = '更新紀錄' } = {}) {
  const box = openOverlay(h('h2', {}, title), loading())
  const list = (await fetchChangelog()).filter((r) => r.code > since)
  const now = Number(window.APP_VERSION) || 0
  box.lastChild.replaceWith(
    list.length
      ? h(
          'div',
          { class: 'cl-list' },
          list.map((r) =>
            h(
              'div',
              { class: `cl-item${r.code === now ? ' current' : ''}` },
              h('div', { class: 'cl-head' }, h('b', {}, `v1.0.${r.code}`), r.code === now ? h('span', { class: 'cl-now' }, '目前版本') : null, h('span', { class: 'cl-date' }, r.date)),
              h('div', { class: 'cl-title' }, r.title),
              r.items.length ? h('ul', {}, r.items.map((i) => h('li', {}, i))) : null,
            ),
          ),
        )
      : status('讀不到更新紀錄（第一次查看需要網路）'),
  )
}

/** 更新後第一次開：在背景抓更新紀錄，抓得到才顯示（抓不到也不會擋住畫面） */
async function showWhatsNew() {
  const now = Number(window.APP_VERSION) || 0
  if (!now) return
  const seen = store.get('lastSeenVersion', 0)
  store.set('lastSeenVersion', now)
  if (!seen || seen >= now) return
  const list = (await fetchChangelog()).filter((r) => r.code > seen)
  if (list.length) showChangelog({ since: seen, title: `已更新到 v1.0.${now}` })
}

/** 檢查新版；有的話右下角跳更新提示。回傳 'dev' | 'latest' | 'shown' | 'error' */
async function checkUpdate(manual = false) {
  const now = Number(window.APP_VERSION) || 0
  if (!now) return 'dev'
  try {
    const res = await fetch(`https://api.github.com/repos/${REPO}/releases/latest`, { headers: { Accept: 'application/vnd.github+json' } })
    if (!res.ok) return 'error'
    const r = await res.json()
    const code = Number(String(r.tag_name || '').split('.').pop()) || 0
    const asset = (r.assets || []).find((a) => a.name === ZIP_NAME)
    if (code <= now || !asset) return 'latest'
    if (!manual && store.get('skippedVersion', 0) === code) return 'latest'
    document.querySelector('.update-bar')?.remove()
    const st = h('div', { class: 'status-line' })
    const primary = h('button', { class: 'btn primary' }, '立即更新')
    const skip = h('button', { class: 'btn' }, '略過這個版本')
    const later = h('button', { class: 'btn' }, '稍後')
    const bar = h('div', { class: 'update-bar' }, h('div', {}, h('b', {}, `發現新版本 v1.0.${code}`), `（目前 v1.0.${now}）`, h('div', { class: 'notes' }, noteLines(r.body).join('\n')), st), h('div', { class: 'row' }, primary, skip, later))
    const inApp = window.updater && (await window.updater.supported().catch(() => false))
    let busy = false
    primary.onclick = async () => {
      if (!inApp) {
        window.hub.external(asset.browser_download_url)
        return bar.remove()
      }
      if (busy) return
      const active = [...jobs.values()].some((j) => j.status === 'running' || j.status === 'merging')
      if (active && !confirm('還有影片在下載，更新後會自動從中斷的地方繼續。要現在更新嗎？')) return
      busy = true
      primary.textContent = '下載中…'
      skip.disabled = later.disabled = true
      try {
        await window.updater.download(asset.browser_download_url, (pc) => {
          st.textContent = pc >= 0 ? `下載中 ${pc}%` : '下載中…'
          if (pc >= 100) st.textContent = '解壓縮中…'
        })
        st.textContent = '下載完成，程式會關閉並自動重新開啟'
        primary.textContent = '安裝中…'
        await window.updater.install()
      } catch (err) {
        busy = false
        primary.textContent = '重試'
        skip.disabled = later.disabled = false
        st.textContent = `更新失敗：${errText(err)}`
      }
    }
    skip.onclick = () => {
      store.set('skippedVersion', code)
      bar.remove()
    }
    later.onclick = () => bar.remove()
    document.body.append(bar)
    return 'shown'
  } catch {
    return 'error'
  }
}

// ---------- 啟動 ----------

$('.brand').onclick = () => nav('home', {}, { root: true })
$('#navBack').onclick = goBack
const verEl = $('#version')
verEl.textContent = versionText()
verEl.onclick = () => showChangelog()

$('#searchForm').addEventListener('submit', (e) => {
  e.preventDefault()
  const q = $('#searchInput').value.trim()
  if (!q) return
  nav('list', { title: `搜尋：${q}`, url: site.searchUrl(q) })
})

window.addEventListener('beforeunload', () => player?.savePos?.())

$('#accountBtn').onclick = () => (account.email ? nav('settings', {}, { root: true }) : loginDialog())
renderAccountBtn()

nav('home', {}, { root: true })
verifyAccount()
setTimeout(() => checkUpdate(), 2500)
showWhatsNew()

// 測試用
window.__app = { account, setAccount, verifyAccount, nav, goBack, openVideo, openPlayer, closePlayer, checkUpdate, showChangelog, jobs, get player() { return player } }
