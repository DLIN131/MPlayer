package com.mplayer.app.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.mplayer.app.net.Account
import com.mplayer.app.net.Prefs
import com.mplayer.app.net.Site
import com.mplayer.app.net.Store
import com.mplayer.app.net.Video
import kotlinx.coroutines.CancellationException

/** 登入後收藏、觀看記錄改用網站帳號的；沒登入時照舊存在本機 */
object AccountSync {
    /**
     * 帳號收藏／觀看記錄的列表。登入狀態變了、或收藏有變動時換新的（舊的內容不能沿用）。
     * 用計數器當 key：畫面讀到計數器（Compose 狀態）就會在變動時自動換成新列表。
     */
    private var savedGen by mutableIntStateOf(0)
    private var historyGen by mutableIntStateOf(0)
    private val feeds = HashMap<String, Feed<Video>>()

    private fun feed(path: String, gen: Int): Feed<Video> =
        feeds.getOrPut("$path|${Account.version}|$gen") { Feed({ it.code }) { Site.accountList(path, it) } }

    val savedFeed: Feed<Video> get() = feed("/saved", savedGen)
    val historyFeed: Feed<Video> get() = feed("/history", historyGen)

    /** 收藏有變動：下次看收藏頁時重新載入 */
    fun invalidateSaved() {
        savedGen++
    }

    fun invalidateHistory() {
        historyGen++
    }

    /** 剛登入、本機有收藏、這個帳號還沒問過 → 問要不要加到帳號 */
    fun shouldOfferMerge(): Boolean {
        val email = Account.email ?: return false
        return Store.favorites.isNotEmpty() && email !in Prefs.mergeAsked
    }

    fun markMergeAsked() {
        val email = Account.email ?: return
        Prefs.mergeAsked = Prefs.mergeAsked + email
    }

    /**
     * 把本機收藏加到帳號。每部要先讀影片頁拿網站內部編號（不會記觀看記錄）。
     * 回傳成功幾部；本機收藏保留不動（登出後還看得到）。
     */
    suspend fun uploadLocalFavorites(onProgress: (done: Int, total: Int) -> Unit): Int {
        val list = Store.favorites.toList().reversed() // 舊的先加，帳號裡的順序跟本機一樣
        var ok = 0
        list.forEachIndexed { i, v ->
            onProgress(i, list.size)
            try {
                val id = Site.detail(v.code).itemId
                if (id != null) {
                    Site.setSaved(id, true)
                    ok++
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 影片下架或網路問題就跳過
            }
        }
        onProgress(list.size, list.size)
        markMergeAsked()
        invalidateSaved()
        return ok
    }
}
