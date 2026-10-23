package com.mplayer.app.net

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import com.mplayer.app.App
import org.json.JSONArray
import org.json.JSONObject

/** 本機的觀看紀錄與收藏（不登入網站帳號，全部存在手機上） */
object Store {
    private const val MAX_HISTORY = 300
    private val sp by lazy { App.instance.getSharedPreferences("store", Context.MODE_PRIVATE) }

    val history = mutableStateListOf<Video>().apply { addAll(load("history")) }
    val favorites = mutableStateListOf<Video>().apply { addAll(load("favorites")) }

    val searchWords = mutableStateListOf<String>().apply {
        addAll(sp.getString("search_words", "").orEmpty().split('\n').filter { it.isNotBlank() })
    }

    fun addSearchWord(word: String) {
        searchWords.remove(word)
        searchWords.add(0, word)
        while (searchWords.size > 20) searchWords.removeAt(searchWords.lastIndex)
        sp.edit().putString("search_words", searchWords.joinToString("\n")).apply()
    }

    fun clearSearchWords() {
        searchWords.clear()
        sp.edit().remove("search_words").apply()
    }

    fun addHistory(v: Video) {
        history.removeAll { it.code == v.code }
        history.add(0, v)
        while (history.size > MAX_HISTORY) history.removeAt(history.lastIndex)
        save("history", history)
    }

    fun clearHistory() {
        history.clear()
        save("history", history)
        sp.edit().apply { sp.all.keys.filter { it.startsWith("pos_") }.forEach(::remove) }.apply()
    }

    fun isFavorite(code: String) = favorites.any { it.code == code }

    fun toggleFavorite(v: Video) {
        if (!favorites.removeAll { it.code == v.code }) favorites.add(0, v)
        save("favorites", favorites)
    }

    /** 播放進度（秒），看完會清掉 */
    fun position(code: String) = sp.getFloat("pos_$code", 0f)

    fun savePosition(code: String, sec: Float) {
        sp.edit().apply { if (sec > 0) putFloat("pos_$code", sec) else remove("pos_$code") }.apply()
    }

    private fun load(key: String): List<Video> = runCatching {
        val arr = JSONArray(sp.getString(key, "[]"))
        List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            Video(
                code = o.getString("code"),
                title = o.optString("title"),
                cover = o.optString("cover"),
                duration = o.optString("duration"),
            )
        }
    }.getOrDefault(emptyList())

    private fun save(key: String, list: List<Video>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject()
                    .put("code", it.code)
                    .put("title", it.title)
                    .put("cover", it.cover)
                    .put("duration", it.duration)
            )
        }
        sp.edit().putString(key, arr.toString()).apply()
    }
}

/** 偏好設定 */
object Prefs {
    private val sp by lazy { App.instance.getSharedPreferences("prefs", Context.MODE_PRIVATE) }

    /** 使用者選「略過這個版本」的 versionCode，自動檢查時不再提示這一版 */
    var skippedVersion: Int
        get() = sp.getInt("skipped_version", 0)
        set(value) = sp.edit().putInt("skipped_version", value).apply()
}
