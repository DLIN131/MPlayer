// 頁面用的小資料庫：讀寫都走主程序的 state.json（見 state.js）。
// 不用 localStorage：關掉馬上重開時，Chromium 的儲存服務可能還鎖著資料庫，上次寫的資料會不見。
const cache = (() => {
  try {
    return window.hub.state.all() || {}
  } catch {
    return {}
  }
})()

export const store = {
  get(k, d) {
    return cache[k] ?? d
  },
  set(k, v) {
    cache[k] = v
    window.hub.state.set(k, v)
  },
}
