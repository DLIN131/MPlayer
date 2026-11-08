package com.mplayer.app.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class WikiActress(val name: String, val url: String)

/**
 * 用番號查女優：另一個整理女優名字的網站（素人、企劃片也有），沒有 Cloudflare，直接抓就行。
 * 搜尋是模糊比對（LUXU-1800 也會找到 259LUXU-1800），所以只留番號真的對得上的結果。
 */
object ActressLookup {
    private val BASE: String by lazy { decode("aHR0cHM6Ly9hdi13aWtpLm5ldA==") }

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }
    private val cache = HashMap<String, List<WikiActress>>()

    /** 番號後面的版本標記（無碼流出、中文字幕…）拿掉，轉大寫 */
    fun normalize(code: String): String =
        code.trim().lowercase()
            .replace(Regex("-(uncensored-leak|chinese-subtitle|english-subtitle|uncensored|leak)$"), "")
            .uppercase()

    suspend fun find(code: String): List<WikiActress> {
        val q = normalize(code)
        cache[q]?.let { return it }
        val html = withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url("$BASE/?s=${URLEncoder.encode(q, "UTF-8")}")
                .header("User-Agent", WebFetcher.userAgent)
                .build()
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) throw Exception("女優查詢失敗（HTTP ${r.code}）")
                r.body!!.string()
            }
        }
        val list = withContext(Dispatchers.Default) { parse(html, q) }
        cache[q] = list
        return list
    }

    /** 搜尋結果：每筆 article.archive-list，番號和女優名字在 ul.post-meta 裡 */
    fun parse(html: String, code: String): List<WikiActress> {
        val q = normalize(code)
        // 番號一樣，或前面多了數字（MGS 的 259LUXU-1800 這種）
        val matches = { text: String -> text == q || Regex("\\d+${Regex.escape(q)}").matches(text) }
        // 連結都是完整網址，不用基準網址（單元測試裡也不用碰 Android 的 Base64）
        val d = Jsoup.parse(html)
        val out = LinkedHashMap<String, WikiActress>()
        for (art in d.select("article.archive-list")) {
            val texts = art.select("ul.post-meta li").map { it.text().trim().uppercase() }
            if (texts.none(matches)) continue
            for (a in art.select("li.actress-name a")) {
                val name = a.text().trim()
                if (name.isNotEmpty()) out.putIfAbsent(name, WikiActress(name, a.attr("href")))
            }
        }
        return out.values.toList()
    }
}
