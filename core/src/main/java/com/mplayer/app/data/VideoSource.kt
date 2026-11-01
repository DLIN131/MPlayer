package com.mplayer.app.data

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.mplayer.app.App
import com.mplayer.app.net.WebFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 影片是 HLS（分段副檔名是 .jpeg，內容其實是 TS）。
 * CDN 沒帶網站的 Referer／Origin 會回 403，所以每個請求都補上這兩個標頭。
 */
@OptIn(UnstableApi::class)
object VideoSource {
    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private val dataSource by lazy {
        OkHttpDataSource.Factory(http)
            .setUserAgent(WebFetcher.userAgent)
            .setDefaultRequestProperties(
                mapOf(
                    "Referer" to WebFetcher.BASE + "/",
                    "Origin" to WebFetcher.BASE,
                )
            )
    }

    fun hls(playlist: String): MediaSource =
        HlsMediaSource.Factory(dataSource).createMediaSource(
            MediaItem.Builder().setUri(playlist).setMimeType(MimeTypes.APPLICATION_M3U8).build()
        )

    /** 已下載到手機的影片（多個 TS 分段接成的一個檔） */
    fun local(file: File): MediaSource =
        ProgressiveMediaSource.Factory(DefaultDataSource.Factory(App.instance))
            .createMediaSource(MediaItem.fromUri(Uri.fromFile(file)))

    /** 播放清單是本機檔案路徑（離線播放）還是網址 */
    fun isLocal(playlist: String) = playlist.startsWith("/")

    fun source(playlist: String): MediaSource = if (isLocal(playlist)) local(File(playlist)) else hls(playlist)

    private fun request(url: String) = Request.Builder()
        .url(url)
        .header("User-Agent", WebFetcher.userAgent)
        .header("Referer", WebFetcher.BASE + "/")
        .header("Origin", WebFetcher.BASE)
        .build()

    /** 下載用：帶跟播放器一樣的標頭抓檔案 */
    suspend fun bytes(url: String): ByteArray = withContext(Dispatchers.IO) {
        http.newCall(request(url)).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            r.body!!.bytes()
        }
    }

    suspend fun text(url: String): String = String(bytes(url), Charsets.UTF_8)

    /** 電視盒子記憶體小：預設緩衝會吃到上百 MB，壓到 30 秒／32 MB 就夠順了 */
    fun lowMemoryLoadControl(): LoadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(10_000, 30_000, 1_500, 3_000)
        .setTargetBufferBytes(32 * 1024 * 1024)
        .setPrioritizeTimeOverSizeThresholds(false)
        .build()
}
