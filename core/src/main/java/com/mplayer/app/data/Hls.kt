package com.mplayer.app.data

import okhttp3.HttpUrl.Companion.toHttpUrl

/** 解析 HLS 播放清單（下載用；播放交給 ExoPlayer） */
object Hls {
    /** 主清單裡的一種畫質 */
    data class Variant(val height: Int, val bandwidth: Long, val url: String)

    data class Segment(val url: String, val duration: Double)

    class UnsupportedException(msg: String) : Exception(msg)

    /** 主清單 → 各畫質，高到低 */
    fun variants(text: String, base: String): List<Variant> {
        val lines = text.lines().map { it.trim() }
        val out = mutableListOf<Variant>()
        for ((i, line) in lines.withIndex()) {
            if (!line.startsWith("#EXT-X-STREAM-INF:")) continue
            val uri = lines.drop(i + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: continue
            val height = Regex("RESOLUTION=\\d+x(\\d+)").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            // 平均位元率比較接近實際檔案大小，沒有才用峰值
            val bw = Regex("AVERAGE-BANDWIDTH=(\\d+)").find(line)?.groupValues?.get(1)?.toLongOrNull()
                ?: Regex("(?<!-)BANDWIDTH=(\\d+)").find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0
            out += Variant(height, bw, resolve(base, uri))
        }
        return out.distinctBy { it.height }.sortedByDescending { it.height }
    }

    /** 單一畫質的清單 → 分段（依序） */
    fun segments(text: String, base: String): List<Segment> {
        val lines = text.lines().map { it.trim() }
        lines.firstOrNull { it.startsWith("#EXT-X-KEY:") }?.let { key ->
            if (!key.contains("METHOD=NONE")) throw UnsupportedException("這部影片有加密，無法下載")
        }
        val out = mutableListOf<Segment>()
        var duration = 0.0
        for (line in lines) {
            when {
                line.startsWith("#EXTINF:") ->
                    duration = line.removePrefix("#EXTINF:").substringBefore(',').toDoubleOrNull() ?: 0.0
                line.isNotEmpty() && !line.startsWith("#") -> {
                    out += Segment(resolve(base, line), duration)
                    duration = 0.0
                }
            }
        }
        return out
    }

    private fun resolve(base: String, uri: String): String =
        if (uri.startsWith("http")) uri else base.toHttpUrl().resolve(uri)?.toString() ?: uri
}
