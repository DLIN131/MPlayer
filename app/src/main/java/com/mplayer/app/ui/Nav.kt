package com.mplayer.app.ui

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mplayer.app.net.Actress
import com.mplayer.app.net.BlockedException
import com.mplayer.app.net.Detail
import com.mplayer.app.net.Page
import com.mplayer.app.net.Site
import com.mplayer.app.net.Video
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/** 整個 App 共用的 coroutine scope，列表載入不跟著畫面消失而取消 */
val appScope: CoroutineScope = MainScope()

sealed class Route {
    abstract val key: String

    data object Main : Route() {
        override val key = "main"
    }

    /** 分類／搜尋／女優／類型的影片列表，可以切換排序 */
    class Listing(val title: String, val url: String) : Route() {
        override val key = "list:$url"
        var sort by mutableStateOf("")
        private val feeds = HashMap<String, Feed<Video>>()
        val feed: Feed<Video> get() = feeds.getOrPut(sort) { videoFeed(url, sort) }
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

/**
 * 可無限往下捲的列表。狀態（含捲動位置）存在物件裡，
 * 跟著導覽堆疊走，回上一頁時列表和位置都還在。
 */
class Feed<T>(private val key: (T) -> String, private val loader: suspend (page: Int) -> Page<T>) {
    val items = mutableStateListOf<T>()
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var blocked by mutableStateOf(false)
        private set
    var end by mutableStateOf(false)
        private set
    val grid = LazyGridState()
    private var page = 0
    private var job: Job? = null

    fun loadMore() {
        if (loading || end) return
        loading = true
        error = null
        blocked = false
        job = appScope.launch {
            try {
                val r = loader(page + 1)
                page++
                val seen = items.mapTo(HashSet()) { key(it) }
                items.addAll(r.items.filter { key(it) !in seen })
                if (!r.hasMore) end = true
            } catch (e: BlockedException) {
                blocked = true
                error = e.message
            } catch (e: Exception) {
                error = e.message ?: e.toString()
            } finally {
                loading = false
            }
        }
    }

    fun refresh() {
        job?.cancel()
        loading = false
        items.clear()
        page = 0
        end = false
        error = null
        appScope.launch { grid.scrollToItem(0) }
        loadMore()
    }
}

fun videoFeed(url: String, sort: String = "") = Feed<Video>({ it.code }) { Site.list(url, it, sort) }

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
