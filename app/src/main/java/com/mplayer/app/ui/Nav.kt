package com.mplayer.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mplayer.app.data.Feed
import com.mplayer.app.data.appScope
import com.mplayer.app.data.videoFeed
import com.mplayer.app.net.Actress
import com.mplayer.app.net.BlockedException
import com.mplayer.app.net.Detail
import com.mplayer.app.net.Site
import com.mplayer.app.net.Video
import kotlinx.coroutines.launch

sealed class Route {
    abstract val key: String

    data object Main : Route() {
        override val key = "main"
    }

    /** 分類／搜尋／女優／類型的影片列表，可以切換排序 */
    class Listing(val title: String, val url: String) : Route() {
        override val key = "list:$url"
        var sort by mutableStateOf("")
        /** 女優頁才有：單人／多人（網站端），有碼／無碼（App 端），跟排序各自獨立 */
        var cast by mutableStateOf("")
        var censor by mutableStateOf(Site.Censor.All)
        val isActress = Site.isActressUrl(url)
        private val feeds = HashMap<String, Feed<Video>>()
        val feed: Feed<Video> get() = feeds.getOrPut("$sort|$cast") { videoFeed(url, sort, cast) }
    }

    class VideoPage(val preview: Video) : Route() {
        override val key = "video:${preview.code}"
        var detail by mutableStateOf<Detail?>(null)
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

    class Player(val video: Video, val playlist: String) : Route() {
        override val key = "player:${video.code}"
    }

    data object Verify : Route() {
        override val key = "verify"
    }
}

object Nav {
    val stack = mutableStateListOf<Route>(Route.Main)
    val top: Route get() = stack.last()

    fun push(r: Route) {
        stack.add(r)
    }

    fun pop() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }
}

/** 主畫面各分頁的狀態 */
object MainState {
    var tab by mutableIntStateOf(0)
    var category by mutableIntStateOf(0)
    var sort by mutableStateOf("")
    private val feeds = HashMap<String, Feed<Video>>()

    val feed: Feed<Video>
        get() {
            val path = Site.categories[category].path
            return feeds.getOrPut("$path|$sort") { videoFeed(path, sort) }
        }

    val actresses = Feed<Actress>({ it.url }) { Site.actresses(it) }

    var libraryTab by mutableIntStateOf(0)
}
