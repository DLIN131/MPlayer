package com.mplayer.app.net

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.mplayer.app.App
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/** 被 Cloudflare 擋下，需要使用者在可見的 WebView 裡手動過驗證 */
class BlockedException : Exception("被 Cloudflare 擋下了，請開啟驗證頁面完成驗證")

/**
 * 網站有 Cloudflare，一般 HTTP 客戶端直接抓會被擋（403）。
 * 這裡用一個隱藏的 WebView（真正的 Chromium）先打開首頁過驗證，
 * 之後所有頁面都在它裡面用 fetch() 抓 HTML，透過 JS bridge 傳回來。
 */
object WebFetcher {
    val BASE: String = decode("aHR0cHM6Ly9taXNzYXYud3M=")

    private var web: WebView? = null
    private val bootLock = Mutex()
    @Volatile private var ready = false
    private val seq = AtomicInteger()
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<Pair<Int, String>>>()

    /** WebView 預設 UA 帶有 "; wv"，有些防火牆會針對它，改成一般手機 Chrome 的樣子 */
    val userAgent: String by lazy {
        WebSettings.getDefaultUserAgent(App.instance)
            .replace("; wv", "")
            .replace(Regex("Version/\\S+ "), "")
    }

    private class Bridge {
        @JavascriptInterface
        fun done(id: Int, status: Int, body: String) {
            pending.remove(id)?.complete(status to body)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun webView(): WebView = web ?: WebView(App.instance).also { w ->
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString = userAgent
            // 只拿 HTML，不需要載圖
            loadsImagesAutomatically = false
            blockNetworkImage = true
            mediaPlaybackRequiresUserGesture = true
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
        w.webViewClient = WebViewClient()
        w.addJavascriptInterface(Bridge(), "AppBridge")
        w.resumeTimers()
        web = w
    }

    /** 使用者在驗證頁過完驗證後呼叫，下次請求重新暖機 */
    fun reset() {
        ready = false
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val releaseLater = Runnable { release() }
    private const val RELEASE_AFTER_MS = 60_000L

    /**
     * App 退到背景或進入播放器時：先暫停隱藏 WebView 的 JS 計時器和繪製，不佔 CPU；
     * 超過 1 分鐘還沒回來（例如正在看影片）就整個釋放，把記憶體還給播放器。
     */
    fun pause() {
        web?.let {
            it.onPause()
            it.pauseTimers()
            mainHandler.removeCallbacks(releaseLater)
            mainHandler.postDelayed(releaseLater, RELEASE_AFTER_MS)
        }
    }

    fun resume() {
        mainHandler.removeCallbacks(releaseLater)
        web?.let {
            it.resumeTimers()
            it.onResume()
        }
    }

    private fun release() {
        val w = web ?: return
        web = null
        ready = false
        pending.values.forEach { it.completeExceptionally(Exception("連線中斷，請重試")) }
        pending.clear()
        w.stopLoading()
        w.removeJavascriptInterface("AppBridge")
        // pauseTimers() 是全 App 共用的，釋放前要恢復，不然下一個 WebView 的 JS 不會動
        w.resumeTimers()
        w.destroy()
    }

    suspend fun get(path: String): String {
        val url = if (path.startsWith("http")) path else BASE + path
        repeat(2) { attempt ->
            ensureReady()
            val (status, body) = fetch(url)
            if (!isChallenge(status, body)) {
                if (status !in 200..299) throw Exception("載入失敗（HTTP $status）")
                return body
            }
            // cookie 過期，重新跑一次驗證
            ready = false
            if (attempt == 1) throw BlockedException()
        }
        throw BlockedException()
    }

    private fun isChallenge(status: Int, body: String) =
        (status == 403 || status == 503) &&
            (body.contains("cf-") || body.contains("challenge-platform") || body.contains("Cloudflare"))

    private suspend fun ensureReady() {
        if (ready) return
        bootLock.withLock {
            if (ready) return
            withContext(Dispatchers.Main) {
                val w = webView()
                w.loadUrl(BASE)
                // 等驗證頁自己跳轉到正常首頁，最多 30 秒
                val ok = withTimeoutOrNull(30_000) {
                    delay(1500)
                    while (true) {
                        val state = w.eval("document.readyState+'|'+document.title+'|'+!!document.querySelector('.thumbnail')")
                        if (state.startsWith("complete") && state.endsWith("|true")) break
                        if (state.contains("Attention Required")) return@withTimeoutOrNull false
                        delay(700)
                    }
                    true
                } == true
                if (!ok) {
                    w.stopLoading()
                    throw BlockedException()
                }
                CookieManager.getInstance().flush()
            }
            ready = true
        }
    }

    private suspend fun fetch(url: String): Pair<Int, String> {
        val id = seq.incrementAndGet()
        val result = CompletableDeferred<Pair<Int, String>>()
        pending[id] = result
        val js = """
            fetch(${JSONObject.quote(url)}, {credentials: 'include'})
              .then(function (r) { return r.text().then(function (t) { AppBridge.done($id, r.status, t); }); })
              .catch(function (e) { AppBridge.done($id, -1, String(e)); });
        """.trimIndent()
        withContext(Dispatchers.Main) { webView().evaluateJavascript(js, null) }
        return try {
            val r = withTimeout(30_000) { result.await() }
            if (r.first == -1) throw Exception("網路錯誤：${r.second}")
            r
        } finally {
            pending.remove(id)
        }
    }

    private suspend fun WebView.eval(js: String): String = suspendCancellableCoroutine { cont ->
        evaluateJavascript(js) { raw ->
            // 回傳值是 JSON 字串
            val s = runCatching { JSONObject("{\"v\":$raw}").optString("v") }.getOrDefault("")
            if (cont.isActive) cont.resume(s)
        }
    }
}

/** 網址以 Base64 存放，原始碼裡不出現明文 */
internal fun decode(s: String): String = String(android.util.Base64.decode(s, android.util.Base64.DEFAULT), Charsets.UTF_8)
