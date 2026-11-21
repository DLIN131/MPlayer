package com.mplayer.app.data

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceUtil
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cronet.CronetDataSource
import androidx.media3.datasource.cronet.CronetUtil
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.mplayer.app.App
import com.mplayer.app.net.WebFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.chromium.net.CronetEngine
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 影片是 HLS（分段副檔名是 .jpeg，內容其實是 TS）。
 * CDN 沒帶網站的 Referer／Origin 會回 403，所以每個請求都補上這兩個標頭。
 *
 * CDN 前面有 Cloudflare，會看連線的「指紋」判斷是不是瀏覽器：OkHttp 大多會過，但偶爾被擋 403，
 * 看到一半就停住。所以優先用 Cronet（Chrome 同一套網路層，Google Play 服務提供），
 * 沒有 Play 服務的盒子才退回 OkHttp；另外 403／5xx 會自動重試，不會因為一次被擋就整個停掉。
 */
@OptIn(UnstableApi::class)
object VideoSource {
    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private val headers by lazy {
        mapOf(
            "Referer" to WebFetcher.BASE + "/",
            "Origin" to WebFetcher.BASE,
        )
    }

    /** Play 服務的 Cronet；App 啟動時已在背景安裝，還沒好或沒有 Play 服務就是 null */
    private var cronet: CronetEngine? = null
    private val cronetExecutor by lazy { Executors.newFixedThreadPool(4) }

    private fun cronetEngine(): CronetEngine? {
        cronet?.let { return it }
        // 只用 Play 服務版，不用 Cronet 內建的 Java 備援實作（那個指紋跟 OkHttp 差不多）
        return runCatching {
            CronetUtil.buildCronetEngine(App.instance, WebFetcher.userAgent, /* preferGooglePlayServices = */ true)
        }.getOrNull()?.also { cronet = it }
    }

    /** 每次播放都重新挑：Play 服務的 Cronet 晚一點才裝好的話，下一部就會用上 */
    private fun dataSource(): DataSource.Factory {
        val engine = cronetEngine()
        if (engine != null) {
            return CronetDataSource.Factory(engine, cronetExecutor)
                .setUserAgent(WebFetcher.userAgent)
                .setDefaultRequestProperties(headers)
                .setConnectionTimeoutMs(15_000)
                .setReadTimeoutMs(30_000)
        }
        return okHttpDataSource
    }

    private val okHttpDataSource by lazy {
        OkHttpDataSource.Factory(http)
            .setUserAgent(WebFetcher.userAgent)
            .setDefaultRequestProperties(headers)
    }

    /**
     * 被 CDN 擋（403）、限流（429）或伺服器出錯（5xx）時：等一下再重試同一個檔案，最多 6 次。
     * 預設的做法是把這個畫質整個排除，但我們固定了畫質（預設最高），排除後就沒得播，直接報錯停掉。
     */
    private class RetryPolicy : DefaultLoadErrorHandlingPolicy(6) {
        override fun getFallbackSelectionFor(
            fallbackOptions: LoadErrorHandlingPolicy.FallbackOptions,
            loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo,
        ): LoadErrorHandlingPolicy.FallbackSelection? =
            if (isTransient(loadErrorInfo.exception)) null
            else super.getFallbackSelectionFor(fallbackOptions, loadErrorInfo)

        override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long =
            if (isTransient(loadErrorInfo.exception)) (loadErrorInfo.errorCount * 1_000L).coerceAtMost(5_000L)
            else super.getRetryDelayMsFor(loadErrorInfo)
    }

    private fun isTransient(e: Throwable): Boolean {
        val code = (e as? HttpDataSource.InvalidResponseCodeException)?.responseCode ?: return false
        return code == 403 || code == 429 || code >= 500
    }

    /** 播放器遇到 HTTP 錯誤時看這個決定要不要自動從目前位置重新連線 */
    fun isTransientError(error: PlaybackException): Boolean =
        generateSequence<Throwable>(error) { it.cause }.any { isTransient(it) } ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT

    fun hls(playlist: String): MediaSource =
        HlsMediaSource.Factory(dataSource())
            .setLoadErrorHandlingPolicy(RetryPolicy())
            .createMediaSource(
                MediaItem.Builder().setUri(playlist).setMimeType(MimeTypes.APPLICATION_M3U8).build()
            )

    /** 已下載到手機的影片（多個 TS 分段接成的一個檔） */
    fun local(file: File): MediaSource =
        ProgressiveMediaSource.Factory(DefaultDataSource.Factory(App.instance))
            .createMediaSource(MediaItem.fromUri(Uri.fromFile(file)))

    /** 播放清單是本機檔案路徑（離線播放）還是網址 */
    fun isLocal(playlist: String) = playlist.startsWith("/")

    fun source(playlist: String): MediaSource = if (isLocal(playlist)) local(File(playlist)) else hls(playlist)

    /** 下載用：跟播放器同一套連線（Cronet 優先）和標頭抓檔案，被擋也一樣會重試 */
    suspend fun bytes(url: String): ByteArray = withContext(Dispatchers.IO) {
        var attempt = 0
        while (true) {
            val ds = dataSource().createDataSource()
            try {
                ds.open(DataSpec(Uri.parse(url)))
                return@withContext DataSourceUtil.readToEnd(ds)
            } catch (e: HttpDataSource.InvalidResponseCodeException) {
                if (!isTransient(e) || ++attempt >= 5) throw IOException("HTTP ${e.responseCode}")
                delay(attempt * 1_000L)
            } finally {
                runCatching { ds.close() }
            }
        }
        @Suppress("UNREACHABLE_CODE")
        ByteArray(0)
    }

    suspend fun text(url: String): String = String(bytes(url), Charsets.UTF_8)

    /** 電視盒子記憶體小：預設緩衝會吃到上百 MB，壓到 30 秒／32 MB 就夠順了 */
    fun lowMemoryLoadControl(): LoadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(10_000, 30_000, 1_500, 3_000)
        .setTargetBufferBytes(32 * 1024 * 1024)
        .setPrioritizeTimeOverSizeThresholds(false)
        .build()
}
