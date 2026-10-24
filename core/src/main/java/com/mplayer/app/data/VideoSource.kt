package com.mplayer.app.data

import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import com.mplayer.app.net.WebFetcher
import okhttp3.OkHttpClient
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

    /** 電視盒子記憶體小：預設緩衝會吃到上百 MB，壓到 30 秒／32 MB 就夠順了 */
    fun lowMemoryLoadControl(): LoadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(10_000, 30_000, 1_500, 3_000)
        .setTargetBufferBytes(32 * 1024 * 1024)
        .setPrioritizeTimeOverSizeThresholds(false)
        .build()
}
