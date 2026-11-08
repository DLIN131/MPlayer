package com.mplayer.app.net

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject

/**
 * 從網頁判斷有沒有登入。只有個人頁（收藏、觀看記錄）會有使用者選單，
 * 一般列表頁是網站快取的，看不出來。回傳登入的帳號（電郵），null = 沒登入。
 */
fun parseLoginEmail(html: String): String? {
    if (!html.contains("showDropdown = 'user'")) return null
    val m = Regex("登入為\\s*</p>\\s*<p[^>]*>\\s*([^<]+?)\\s*</p>").find(html)
    return m?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() } ?: "已登入"
}

/** 網站說沒登入（登入過期、或在別的地方登出了） */
class NotLoggedInException : Exception("登入已過期，請重新登入")

/**
 * 網站帳號。登入後收藏和觀看記錄都直接用網站上的，手機、電視、電腦、網頁同一份。
 * 登入狀態就是 WebView 裡網站的 cookie；這裡只記帳號名稱給畫面顯示，密碼不會存。
 */
object Account {
    /** 登入的帳號（電郵）；null = 沒登入 */
    var email by mutableStateOf(Prefs.accountEmail)
        private set
    val loggedIn get() = email != null

    /** 登入狀態變了（登入、登出、過期）就 +1，畫面上的帳號清單看到就重新載入 */
    var version by mutableIntStateOf(0)
        private set

    /** 登入過期（不是使用者自己登出）時顯示提示 */
    var expired by mutableStateOf(false)
        private set

    private fun set(newEmail: String?) {
        if (newEmail == email) return
        email = newEmail
        Prefs.accountEmail = newEmail
        version++
    }

    /** 用網頁上抓到的狀態更新（例如載入收藏頁時） */
    fun update(html: String) {
        val e = parseLoginEmail(html)
        if (e == null && email != null) {
            expired = true
            set(null)
        } else if (e != null) {
            expired = false
            set(e)
        }
    }

    /** API 回 401：登入過期 */
    fun markExpired() {
        if (email != null) {
            expired = true
            set(null)
        }
    }

    /** 重新確認登入狀態（App 開啟時） */
    suspend fun refresh() {
        if (email == null) return
        runCatching { update(WebFetcher.get("/saved")) }
    }

    /** 登入；成功回傳 null，失敗回傳給使用者看的原因 */
    suspend fun login(email: String, password: String): String? {
        val body = JSONObject().put("email", email.trim()).put("password", password).put("remember", true).toString()
        val (status, text) = try {
            WebFetcher.api("POST", "/api/login", body)
        } catch (e: BlockedException) {
            return e.message
        } catch (e: Exception) {
            return "連線失敗：${e.message}"
        }
        return when (status) {
            in 200..299 -> {
                // 確認真的登入了，順便拿帳號名稱
                val html = runCatching { WebFetcher.get("/saved") }.getOrNull()
                val who = html?.let { parseLoginEmail(it) }
                if (html != null && who == null) return "登入沒有成功，請再試一次"
                expired = false
                set(who ?: email.trim())
                null
            }
            422 -> errorMessage(text) ?: "電郵或密碼不正確"
            429 -> "嘗試太多次了，請稍後再試"
            else -> "登入失敗（HTTP $status）"
        }
    }

    /** 網站回的驗證錯誤：{ message, errors: { email: ["…"] } } */
    private fun errorMessage(text: String): String? = runCatching {
        val o = JSONObject(text)
        val errors = o.optJSONObject("errors")
        val first = errors?.keys()?.asSequence()?.firstOrNull()?.let { errors.optJSONArray(it)?.optString(0) }
        first?.takeIf { it.isNotBlank() } ?: o.optString("message").takeIf { it.isNotBlank() }
    }.getOrNull()

    suspend fun logout() {
        runCatching { WebFetcher.get("/logout") }
        runCatching { WebFetcher.clearCookies() }
        expired = false
        set(null)
    }
}
