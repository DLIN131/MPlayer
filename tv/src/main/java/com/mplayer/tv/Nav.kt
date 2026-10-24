package com.mplayer.tv

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mplayer.app.data.Feed
import com.mplayer.app.data.appScope
import com.mplayer.app.data.videoFeed
import com.mplayer.app.net.Actress
import com.mplayer.app.net.BlockedException
import com.mplayer.app.net.Site
import com.mplayer.app.net.Video
import com.mplayer.app.net.WebFetcher
import kotlinx.coroutines.launch

/** 主畫面上疊的頁面；狀態存在物件裡，返回時列表和捲動位置都還在 */
sealed class TvRoute {
    class Detail(val preview: Video) : TvRoute() {
        var detail by mutableStateOf<com.mplayer.app.net.Detail?>(null) // 網站解析結果，和這個頁面類別同名
        var error by mutableStateOf<String?>(null)
        var blocked by mutableStateOf(false)

        fun load() {
            error = null
            blocked = false
            appScope.launch {
                try {
                    detail = Site.detail(preview.code)
                } catch (e: BlockedException) {
                    blocked = true
                    error = e.message
                } catch (e: Exception) {
                    error = e.message ?: e.toString()
                }
            }
        }
    }

    /** 分類／女優／類型的影片列表；女優頁多了單人／多人、有碼／無碼篩選 */
    class Listing(val title: String, val url: String) : TvRoute() {
        var sort by mutableStateOf("")
        var cast by mutableStateOf("")
        var censor by mutableStateOf(Site.Censor.All)
        val isActress = Site.isActressUrl(url)
        private val feeds = HashMap<String, Feed<Video>>()
        val feed: Feed<Video> get() = feeds.getOrPut("$sort|$cast") { videoFeed(url, sort, cast) }
    }

    data object Verify : TvRoute()
}

object TvNav {
    val stack = mutableStateListOf<TvRoute>()

    fun push(r: TvRoute) {
        stack.add(r)
    }

    fun pop() {
        if (stack.isEmpty()) return
        if (stack.removeAt(stack.lastIndex) == TvRoute.Verify) WebFetcher.reset()
    }
}

enum class Tab(val label: String) {
    Home("首頁"),
    Search("搜尋"),
    Actresses("女優"),
    Favorites("收藏"),
    History("觀看紀錄"),
}

/** 側邊選單各頁的狀態 */
object TvState {
    var tab by mutableStateOf(Tab.Home)
    var category by mutableStateOf(Site.categories.first().path)
    var sort by mutableStateOf("")
    private val feeds = HashMap<String, Feed<Video>>()

    val feed: Feed<Video> get() = feeds.getOrPut("$category|$sort") { videoFeed(category, sort) }

    val actresses = Feed<Actress>({ it.url }) { Site.actresses(it) }
}
