package com.mplayer.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.mplayer.app.net.Prefs
import com.mplayer.app.net.WebFetcher
import com.mplayer.app.util.Updater

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                val holder = rememberSaveableStateHolder()
                BackHandler(enabled = Nav.stack.size > 1) { Nav.pop() }
                val route = Nav.top
                // 播放器全螢幕，其他畫面避開狀態列和導覽列
                val mod = if (route is Route.Player) Modifier.fillMaxSize().background(Color.Black)
                else Modifier.fillMaxSize().background(Background).safeDrawingPadding()
                Box(mod) {
                    holder.SaveableStateProvider(route.key) {
                        when (route) {
                            Route.Main -> MainScreen()
                            is Route.Listing -> ListingScreen(route)
                            is Route.VideoPage -> VideoScreen(route)
                            is Route.Player -> PlayerScreen(route)
                            Route.Verify -> VerifyScreen()
                        }
                    }
                    PreviewOverlay()
                }
                LaunchedEffect(Unit) {
                    runCatching { Updater.check() }.getOrNull()
                        ?.takeIf { it.versionCode != Prefs.skippedVersion }
                        ?.let { Updater.pending = it }
                }
                Updater.pending?.let { release ->
                    UpdateDialog(
                        release = release,
                        onDismiss = { Updater.pending = null },
                        onSkip = {
                            Prefs.skippedVersion = release.versionCode
                            Updater.pending = null
                        },
                    )
                }
            }
        }
    }

    // 退到背景時讓隱藏的 WebView 休息
    override fun onStart() {
        super.onStart()
        WebFetcher.resume()
    }

    override fun onStop() {
        super.onStop()
        WebFetcher.pause()
    }
}
