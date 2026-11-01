package com.mplayer.app.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.compose.runtime.snapshotFlow
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.mplayer.app.App
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 前景服務：有東西在下載時讓 App 切到背景也不會被系統殺掉，
 * 通知列顯示目前進度。實際下載邏輯在 [Downloads]。
 */
class DownloadService : Service() {
    companion object {
        private const val CHANNEL = "downloads"
        private const val NOTIFY_ID = 1001
        private var lastPercent = -1

        fun start() {
            val ctx = App.instance
            runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, DownloadService::class.java)) }
        }

        fun update(item: Downloads.Item) {
            // 通知更新太頻繁會被系統擋，進度有變才更新
            val percent = (item.progress * 100).toInt()
            if (percent == lastPercent) return
            lastPercent = percent
            val ctx = App.instance
            val nm = ctx.getSystemService(NotificationManager::class.java)
            runCatching { nm.notify(NOTIFY_ID, build(ctx, item)) }
        }

        private fun build(ctx: Context, item: Downloads.Item?): Notification {
            ensureChannel(ctx)
            val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
            val open = PendingIntent.getActivity(
                ctx, 0, launch ?: Intent(),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val queued = Downloads.items.count { it.status == Downloads.Status.Queued }
            val percent = ((item?.progress ?: 0f) * 100).toInt()
            return NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(if (item != null) "下載中 $percent%  ·  ${item.code.uppercase()}" else "準備下載…")
                .setContentText(
                    (item?.let { "${fmtSize(it.bytes)}  ·  ${it.height}p" } ?: "") +
                        if (queued > 0) "（還有 $queued 部排隊中）" else ""
                )
                .setProgress(100, percent, item == null || item.total == 0)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setContentIntent(open)
                .build()
        }

        private fun ensureChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "下載", NotificationManager.IMPORTANCE_LOW))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val running = Downloads.items.firstOrNull { it.status == Downloads.Status.Running }
        ServiceCompat.startForeground(
            this, NOTIFY_ID, build(this, running),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        // 佇列清空就自己結束（不由外面 stopService，避免還沒 startForeground 就被停掉而閃退）
        if (watcher?.isActive != true) {
            watcher = scope.launch {
                snapshotFlow {
                    Downloads.items.any { it.status == Downloads.Status.Queued || it.status == Downloads.Status.Running }
                }.first { !it }
                lastPercent = -1
                ServiceCompat.stopForeground(this@DownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watcher: Job? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
