package com.mplayer.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mplayer.app.util.Updater
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun UpdateDialog(release: Updater.Release, onDismiss: () -> Unit, onSkip: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var progress by remember { mutableStateOf<Float?>(null) } // null = 沒在下載
    var file by remember { mutableStateOf<File?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }

    fun tryInstall(apk: File) {
        if (Updater.canInstall(context)) {
            message = null
            Updater.install(context, apk)
        } else {
            message = "請在接下來的設定畫面允許本 App「安裝不明應用程式」，返回後再按「安裝」。"
            if (!Updater.openInstallPermissionSettings(context)) {
                message = "找不到設定頁面，請到系統設定允許本 App 安裝不明應用程式，再回來按「安裝」。"
            }
        }
    }

    fun start() {
        if (progress != null) return
        file?.let {
            tryInstall(it)
            return
        }
        message = null
        progress = 0f
        job = scope.launch {
            try {
                val apk = Updater.download(context, release) { progress = it }
                file = apk
                progress = null
                tryInstall(apk)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                progress = null
                message = "下載失敗：${e.message}"
            }
        }
    }

    fun dismiss() {
        job?.cancel()
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = ::dismiss,
        title = { Text("發現新版本") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("${Updater.currentVersionName}  →  ${release.name.removePrefix("MPlayer ")}", color = Accent)
                if (release.notes.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Text("更新內容", color = Color.Gray, fontSize = 13.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(release.notes, lineHeight = 20.sp)
                }
                progress?.let { p ->
                    Spacer(Modifier.height(16.dp))
                    LinearProgressIndicator(progress = { p }, color = Accent, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(4.dp))
                    Text("下載中 ${(p * 100).toInt()}%", color = Color.Gray, fontSize = 13.sp)
                }
                message?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(it, color = Color.Gray, fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = ::start) {
                Text(
                    when {
                        progress != null -> "下載中…"
                        file != null -> "安裝"
                        else -> "立即更新"
                    }
                )
            }
        },
        dismissButton = {
            Column {
                TextButton(onClick = ::dismiss) { Text("稍後再說") }
                TextButton(onClick = {
                    job?.cancel()
                    onSkip()
                }) { Text("略過這個版本") }
            }
        },
    )
}
