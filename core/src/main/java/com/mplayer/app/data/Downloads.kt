package com.mplayer.app.data

import android.os.Environment
import android.os.StatFs
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mplayer.app.App
import com.mplayer.app.net.Video
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * 影片下載。HLS 的 TS 分段依序接成一個 <番號>.ts，App 內離線播放。
 * 存在 App 專屬資料夾（不會出現在相簿，移除 App 時一起刪掉）：
 * <番號>.ts、<番號>.json（進度與影片資料）、<番號>.jpg（封面，離線也看得到）。
 * 一次下載一部，每部同時抓 4 個分段；狀態都在主執行緒更新。
 */
object Downloads {
    enum class Status { Queued, Running, Paused, Failed, Done }

    class Item(
        val video: Video,
        val height: Int,
        /** 選定畫質的播放清單網址 */
        val mediaUrl: String,
        status: Status,
        total: Int,
        done: Int,
        bytes: Long,
        val addedAt: Long,
    ) {
        val code get() = video.code
        var status by mutableStateOf(status)
        var total by mutableIntStateOf(total)
        var done by mutableIntStateOf(done)
        var bytes by mutableLongStateOf(bytes)
        var error by mutableStateOf<String?>(null)
        val progress get() = if (total > 0) done.toFloat() / total else 0f
        val file get() = File(root, "$code.ts")
        /** 封面：有存到本機就用本機的 */
        val cover: Any get() = File(root, "$code.jpg").takeIf { it.exists() } ?: video.cover
    }

    private const val PARALLEL = 4
    private const val RETRIES = 3

    /** 新加入的在前面 */
    val items = mutableStateListOf<Item>()

    private val root: File by lazy {
        (App.instance.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: File(App.instance.filesDir, "downloads"))
            .apply {
                mkdirs()
                // 保險起見叫媒體掃描不要收錄
                File(this, ".nomedia").takeIf { !it.exists() }?.createNewFile()
            }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    /** 進度存檔一次只寫一個，順序才不會亂 */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val saveDispatcher = Dispatchers.IO.limitedParallelism(1)
    private var runner: Job? = null
    private var current: Pair<String, Job>? = null
    private var loaded = false

    fun load() {
        if (loaded) return
        loaded = true
        root.listFiles { f -> f.name.endsWith(".json") }.orEmpty()
            .mapNotNull { runCatching { fromJson(JSONObject(it.readText())) }.getOrNull() }
            .sortedByDescending { it.addedAt }
            .forEach { items.add(it) }
    }

    fun find(code: String): Item? = items.firstOrNull { it.code == code }

    /** 可用空間（位元組） */
    fun freeSpace(): Long = runCatching { StatFs(root.path).availableBytes }.getOrDefault(Long.MAX_VALUE)

    /** 下載前讓使用者選畫質：讀主清單 */
    suspend fun options(masterUrl: String): List<Hls.Variant> = Hls.variants(VideoSource.text(masterUrl), masterUrl)

    fun start(video: Video, variant: Hls.Variant) {
        find(video.code)?.let { delete(it.code) }
        val item = Item(video, variant.height, variant.url, Status.Queued, 0, 0, 0, System.currentTimeMillis())
        items.add(0, item)
        save(item)
        kick()
    }

    fun pause(code: String) {
        val item = find(code) ?: return
        if (current?.first == code) current?.second?.cancel()
        if (item.status == Status.Running || item.status == Status.Queued) item.status = Status.Paused
        save(item)
    }

    fun resume(code: String) {
        val item = find(code) ?: return
        if (item.status == Status.Done) return
        item.error = null
        item.status = Status.Queued
        save(item)
        kick()
    }

    fun delete(code: String) {
        val item = find(code) ?: return
        if (current?.first == code) current?.second?.cancel()
        items.remove(item)
        scope.launch(Dispatchers.IO) {
            // 等正在寫的分段停下來再刪
            delay(300)
            listOf("ts", "json", "jpg").forEach { File(root, "$code.$it").delete() }
        }
    }

    /** 依序處理排隊中的下載 */
    private fun kick() {
        DownloadService.start()
        if (runner?.isActive == true) return
        runner = scope.launch {
            while (true) {
                val next = items.lastOrNull { it.status == Status.Queued } ?: break
                val job = launch { run(next) }
                current = next.code to job
                job.join()
                current = null
            }
        }
    }

    private suspend fun run(item: Item) {
        item.status = Status.Running
        item.error = null
        DownloadService.update(item)
        try {
            val segments = Hls.segments(VideoSource.text(item.mediaUrl), item.mediaUrl)
            if (segments.isEmpty()) throw Exception("找不到影片分段")
            item.total = segments.size
            withContext(Dispatchers.IO) {
                // 上次中斷時寫到一半的分段丟掉，從記錄的位置接著寫
                RandomAccessFile(item.file, "rw").use { it.setLength(item.bytes) }
            }
            saveCover(item)
            FileOutputStream(item.file, true).use { out ->
                while (item.done < segments.size) {
                    val from = item.done
                    val to = minOf(from + PARALLEL, segments.size)
                    // 同時抓幾個分段，但依序寫入
                    val chunks = coroutineScope {
                        (from until to).map { i -> async { fetch(segments[i].url) } }.map { it.await() }
                    }
                    withContext(Dispatchers.IO) { chunks.forEach(out::write) }
                    item.done = to
                    item.bytes += chunks.sumOf { it.size.toLong() }
                    save(item)
                    DownloadService.update(item)
                }
            }
            item.status = Status.Done
        } catch (e: CancellationException) {
            if (item.status == Status.Running) item.status = Status.Paused
            throw e
        } catch (e: Exception) {
            item.status = Status.Failed
            item.error = e.message ?: e.toString()
        } finally {
            if (items.contains(item)) save(item)
        }
    }

    private suspend fun fetch(url: String): ByteArray {
        var last: Exception? = null
        repeat(RETRIES) { attempt ->
            try {
                return VideoSource.bytes(url)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
                delay(1000L * (attempt + 1))
            }
        }
        throw Exception("網路錯誤：${last?.message}")
    }

    private suspend fun saveCover(item: Item) {
        val f = File(root, "${item.code}.jpg")
        if (f.exists() || item.video.cover.isEmpty()) return
        runCatching {
            val data = VideoSource.bytes(item.video.cover)
            withContext(Dispatchers.IO) { f.writeBytes(data) }
        }
    }

    private fun save(item: Item) {
        val json = JSONObject()
            .put("code", item.code)
            .put("title", item.video.title)
            .put("cover", item.video.cover)
            .put("duration", item.video.duration)
            .put("height", item.height)
            .put("mediaUrl", item.mediaUrl)
            .put("total", item.total)
            .put("done", item.done)
            .put("bytes", item.bytes)
            .put("complete", item.status == Status.Done)
            .put("addedAt", item.addedAt)
            .toString()
        scope.launch(saveDispatcher) { runCatching { File(root, "${item.code}.json").writeText(json) } }
    }

    private fun fromJson(o: JSONObject): Item {
        val complete = o.optBoolean("complete")
        return Item(
            video = Video(
                code = o.getString("code"),
                title = o.optString("title"),
                cover = o.optString("cover"),
                duration = o.optString("duration"),
            ),
            height = o.optInt("height"),
            mediaUrl = o.getString("mediaUrl"),
            // App 被關掉時還沒下載完的，先當作暫停
            status = if (complete) Status.Done else Status.Paused,
            total = o.optInt("total"),
            done = o.optInt("done"),
            bytes = o.optLong("bytes"),
            addedAt = o.optLong("addedAt"),
        )
    }
}

/** 位元組顯示成 MB／GB */
fun fmtSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.2f GB".format(bytes / (1L shl 30).toDouble())
    else -> "%.0f MB".format(bytes / (1L shl 20).toDouble())
}
