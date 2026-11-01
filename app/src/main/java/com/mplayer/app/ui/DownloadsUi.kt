package com.mplayer.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.mplayer.app.data.Downloads
import com.mplayer.app.data.Hls
import com.mplayer.app.data.fmtSize
import com.mplayer.app.net.Store
import com.mplayer.app.net.Video
import kotlinx.coroutines.launch

/** 片長字串（1:23:45 或 23:45）換成秒數，估算檔案大小用 */
private fun durationSeconds(text: String): Long =
    text.split(':').mapNotNull { it.trim().toLongOrNull() }.fold(0L) { acc, n -> acc * 60 + n }

/** 離線播放已下載的影片 */
fun playDownloaded(item: Downloads.Item) {
    Store.addHistory(item.video)
    Nav.push(Route.Player(item.video, item.file.absolutePath))
}

/** 影片頁的下載按鈕：沒下載 → 選畫質；下載中 → 顯示進度；已下載 → 離線播放 */
@Composable
fun DownloadButton(video: Video, masterUrl: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }
    var options by remember { mutableStateOf<List<Hls.Variant>?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val item = Downloads.find(video.code)

    // Android 13 以上要問通知權限，下載進度才看得到（拒絕也照樣下載）
    val notifyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    fun askNotify() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    when (item?.status) {
        Downloads.Status.Done -> OutlinedButton(onClick = { playDownloaded(item) }, modifier = modifier) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = Accent)
            Spacer(Modifier.width(6.dp))
            Text("已下載（${item.height}p）· 離線播放")
        }
        Downloads.Status.Running, Downloads.Status.Queued -> OutlinedButton(
            onClick = {
                MainState.tab = 3
                MainState.libraryTab = 2
                Nav.stack.removeRange(1, Nav.stack.size)
            },
            modifier = modifier,
        ) {
            Text(
                if (item.status == Downloads.Status.Queued) "排隊中…"
                else "下載中 ${(item.progress * 100).toInt()}%（查看下載）"
            )
        }
        else -> OutlinedButton(
            onClick = {
                if (item != null) {
                    // 暫停或失敗的接著下載
                    askNotify()
                    Downloads.resume(item.code)
                    return@OutlinedButton
                }
                if (loading) return@OutlinedButton
                loading = true
                scope.launch {
                    try {
                        options = Downloads.options(masterUrl).ifEmpty { null }
                        if (options == null) message = "找不到可以下載的畫質"
                    } catch (e: Exception) {
                        message = "讀取畫質失敗：${e.message}"
                    } finally {
                        loading = false
                    }
                }
            },
            modifier = modifier,
        ) {
            if (loading) {
                CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(
                when (item?.status) {
                    Downloads.Status.Paused -> "已暫停 ${(item.progress * 100).toInt()}%・繼續下載"
                    Downloads.Status.Failed -> "下載失敗・重試"
                    else -> "下載"
                }
            )
        }
    }

    options?.let { list ->
        val seconds = durationSeconds(video.duration)
        val free = Downloads.freeSpace()
        AlertDialog(
            onDismissRequest = { options = null },
            title = { Text("選擇下載畫質") },
            text = {
                Column {
                    list.forEach { v ->
                        val size = if (seconds > 0 && v.bandwidth > 0) v.bandwidth * seconds / 8 else 0L
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    options = null
                                    if (size > 0 && size > free) {
                                        message = "空間不足：需要約 ${fmtSize(size)}，手機只剩 ${fmtSize(free)}"
                                    } else {
                                        askNotify()
                                        Downloads.start(video, v)
                                    }
                                }
                                .padding(horizontal = 8.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("${v.height}p", fontSize = 16.sp, modifier = Modifier.weight(1f))
                            if (size > 0) Text("約 ${fmtSize(size)}", color = Muted, fontSize = 14.sp)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("剩餘空間 ${fmtSize(free)}", color = Muted, fontSize = 12.sp)
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { options = null }) { Text("取消") } },
        )
    }
    message?.let {
        AlertDialog(
            onDismissRequest = { message = null },
            text = { Text(it) },
            confirmButton = { TextButton(onClick = { message = null }) { Text("確定") } },
        )
    }
}

/** 「我的 → 下載」清單 */
@Composable
fun DownloadList() {
    var confirmDelete by remember { mutableStateOf<Downloads.Item?>(null) }
    if (Downloads.items.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Text("還沒有下載，在影片頁按「下載」加入", color = Muted)
        }
        return
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            val total = Downloads.items.sumOf { it.bytes }
            Text(
                "共 ${Downloads.items.size} 部・${fmtSize(total)}・剩餘空間 ${fmtSize(Downloads.freeSpace())}",
                color = Muted,
                fontSize = 12.sp,
            )
        }
        items(Downloads.items, key = { it.code }) { item ->
            DownloadRow(item, onDelete = { confirmDelete = item })
        }
    }
    confirmDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("刪除下載？") },
            text = { Text("${item.code.uppercase()} 的影片檔（${fmtSize(item.bytes)}）會從手機刪除。") },
            confirmButton = {
                TextButton(onClick = {
                    Downloads.delete(item.code)
                    confirmDelete = null
                }) { Text("刪除", color = Accent) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun DownloadRow(item: Downloads.Item, onDelete: () -> Unit) {
    val done = item.status == Downloads.Status.Done
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Surface)
            .clickable {
                if (done) playDownloaded(item) else Nav.push(Route.VideoPage(item.video))
            }
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(128.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(6.dp)).background(Background)) {
            AsyncImage(
                model = item.cover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (done) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.align(Alignment.Center).size(32.dp)
                        .background(Color(0x88000000), RoundedCornerShape(16.dp)),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.video.title.ifEmpty { item.code.uppercase() },
                fontSize = 13.sp,
                lineHeight = 17.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            val status = when (item.status) {
                Downloads.Status.Done -> "${item.height}p・${fmtSize(item.bytes)}"
                Downloads.Status.Running -> "下載中 ${(item.progress * 100).toInt()}%・${fmtSize(item.bytes)}"
                Downloads.Status.Queued -> "排隊中"
                Downloads.Status.Paused -> "已暫停 ${(item.progress * 100).toInt()}%"
                Downloads.Status.Failed -> "失敗：${item.error ?: "未知錯誤"}"
            }
            Text(
                status,
                color = if (item.status == Downloads.Status.Failed) Accent else Muted,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!done) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { item.progress },
                    color = Accent,
                    trackColor = Color.White.copy(alpha = 0.12f),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        when (item.status) {
            Downloads.Status.Running, Downloads.Status.Queued ->
                TextButton(onClick = { Downloads.pause(item.code) }) { Text("暫停", color = Color.White) }
            Downloads.Status.Paused, Downloads.Status.Failed ->
                IconButton(onClick = { Downloads.resume(item.code) }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "繼續", tint = Color.White)
                }
            Downloads.Status.Done -> {}
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, contentDescription = "刪除", tint = Muted)
        }
    }
}
