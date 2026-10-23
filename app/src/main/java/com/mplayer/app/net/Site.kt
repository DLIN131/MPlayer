package com.mplayer.app.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder

data class Video(
    /** 番號，也是網址路徑，例如 abc-123 */
    val code: String,
    val title: String,
    val cover: String,
    val duration: String = "",
)

data class Link(val name: String, val url: String)

data class InfoRow(val label: String, val text: String, val links: List<Link>)

data class Detail(
    val video: Video,
    val cover: String,
    val rows: List<InfoRow>,
    /** HLS 主播放清單 */
    val playlist: String?,
)

data class Page<T>(val items: List<T>, val hasMore: Boolean)

data class Actress(val name: String, val url: String, val avatar: String, val info: String)

data class Category(val name: String, val path: String)

/** 網站沒有 API，全部從 HTML 解析；網站改版時要改的就是這個檔案 */
object Site {
    val categories = listOf(
        Category("最近更新", "/dm539/new"),
        Category("新作上市", "/dm635/release"),
        Category("中文字幕", "/dm278/chinese-subtitle"),
        Category("今日熱門", "/dm301/today-hot"),
        Category("本週熱門", "/dm170/weekly-hot"),
        Category("本月熱門", "/dm273/monthly-hot"),
        Category("無碼流出", "/dm817/uncensored-leak"),
        Category("VR", "/genres/VR"),
        Category("SIRO", "/dm36/siro"),
        Category("LUXU", "/dm34/luxu"),
        Category("GANA", "/dm34/gana"),
        Category("PRESTIGE", "/dm1004/maan"),
        Category("FC2", "/dm597/fc2"),
        Category("HEYZO", "/dm2208642/heyzo"),
        Category("東京熱", "/dm42/tokyohot"),
        Category("一本道", "/dm4551174/1pondo"),
        Category("Caribbeancom", "/dm6840521/caribbeancom"),
        Category("10musume", "/dm6238682/10musume"),
        Category("麻豆", "/dm63/madou"),
    )

    /** 排序：網址參數 to 顯示名稱，"" 是網站預設 */
    val sorts = listOf(
        "" to "預設",
        "released_at" to "發行日期",
        "published_at" to "最近更新",
        "saved" to "收藏數",
        "today_views" to "今日瀏覽",
        "weekly_views" to "本週瀏覽",
        "monthly_views" to "本月瀏覽",
        "views" to "總瀏覽數",
    )

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    fun abs(path: String) = if (path.startsWith("http")) path else WebFetcher.BASE + path

    fun searchUrl(query: String) = abs("/search/${enc(query.trim())}")

    private suspend fun doc(url: String): Document {
        val html = WebFetcher.get(url)
        return withContext(Dispatchers.Default) { Jsoup.parse(html, WebFetcher.BASE) }
    }

    private fun withParams(url: String, page: Int, sort: String): String {
        val params = buildList {
            if (sort.isNotEmpty()) add("sort=$sort")
            if (page > 1) add("page=$page")
        }
        if (params.isEmpty()) return url
        return url + (if (url.contains('?')) "&" else "?") + params.joinToString("&")
    }

    /** 分類／搜尋／女優／類型頁 */
    suspend fun list(url: String, page: Int, sort: String = ""): Page<Video> {
        val d = doc(withParams(abs(url), page, sort))
        val items = parseCards(d)
        val hasMore = items.isNotEmpty() && d.select("a[href*=page=${page + 1}]").isNotEmpty()
        return Page(items, hasMore)
    }

    private fun parseCards(d: Document): List<Video> {
        val out = LinkedHashMap<String, Video>()
        for (card in d.select("div.thumbnail")) {
            val a = card.selectFirst("a[href]") ?: continue
            val code = a.absUrl("href").substringAfterLast('/').substringBefore('?').substringBefore('#')
            if (code.isEmpty() || code in out) continue
            val img = card.selectFirst("img")
            val cover = img?.absUrl("data-src")?.ifEmpty { null } ?: img?.absUrl("src").orEmpty()
            val title = card.selectFirst("div.my-2 a")?.text()?.trim()
                ?: img?.attr("alt")?.trim().orEmpty()
            val duration = card.selectFirst("span.absolute")?.text()?.trim().orEmpty()
            out[code] = Video(code, title, cover, duration)
        }
        return out.values.toList()
    }

    suspend fun detail(code: String): Detail {
        val html = WebFetcher.get("/$code")
        return withContext(Dispatchers.Default) {
            val d = Jsoup.parse(html, WebFetcher.BASE)
            val rows = d.select("div.space-y-2 > div").mapNotNull { div ->
                val text = div.text().trim()
                val label = text.substringBefore(':', "").trim()
                if (label.isEmpty() || label.length > 8) return@mapNotNull null
                val links = div.select("a[href]").map { Link(it.text().trim(), it.absUrl("href")) }
                    .filter { it.name.isNotEmpty() }
                InfoRow(label, text.substringAfter(':').trim(), links)
            }.distinctBy { it.label }
            val cover = d.selectFirst("meta[property=og:image]")?.attr("content").orEmpty()
            val title = d.selectFirst("h1")?.text()?.trim().orEmpty()
            Detail(
                video = Video(code, title, cover.replace("cover-n.jpg", "cover-t.jpg")),
                cover = cover,
                rows = rows,
                playlist = findPlaylist(html),
            )
        }
    }

    /** 播放網址藏在 p,a,c,k,e,d 打包過的 JS 裡，先解開再找 m3u8 */
    private fun findPlaylist(html: String): String? {
        val m3u8 = Regex("https?://[^'\"\\s]+?\\.m3u8")
        val packed = Regex(
            """eval\(function\(p,a,c,k,e,d\)\{.*?\}\('(.*?)',(\d+),(\d+),'(.*?)'\.split\('\|'\)""",
            RegexOption.DOT_MATCHES_ALL,
        )
        for (m in packed.findAll(html)) {
            val (p, a, _, k) = m.destructured
            val words = k.split('|')
            val radix = a.toInt().coerceIn(2, 36)
            val code = Regex("\\b\\w+\\b").replace(p.replace("\\'", "'")) { w ->
                val i = w.value.toIntOrNull(radix)
                if (i != null && i < words.size && words[i].isNotEmpty()) words[i] else w.value
            }
            val all = m3u8.findAll(code).map { it.value }.toList()
            (all.firstOrNull { it.endsWith("playlist.m3u8") } ?: all.firstOrNull())?.let { return it }
        }
        return m3u8.find(html)?.value
    }

    suspend fun actresses(page: Int): Page<Actress> {
        val d = doc(withParams(abs("/actresses"), page, ""))
        val items = d.select("li").mapNotNull { li ->
            val name = li.selectFirst("h4")?.text()?.trim() ?: return@mapNotNull null
            val a = li.selectFirst("a[href]") ?: return@mapNotNull null
            Actress(
                name = name,
                url = a.absUrl("href"),
                avatar = li.selectFirst("img")?.absUrl("src").orEmpty(),
                info = li.select("p").joinToString(" · ") { it.text().trim() },
            )
        }
        val hasMore = items.isNotEmpty() && d.select("a[href*=page=${page + 1}]").isNotEmpty()
        return Page(items, hasMore)
    }
}
