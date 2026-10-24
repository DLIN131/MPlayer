package com.mplayer.tv

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.DrawerValue
import androidx.tv.material3.Icon
import androidx.tv.material3.ModalNavigationDrawer
import androidx.tv.material3.NavigationDrawerItem
import androidx.tv.material3.Text
import androidx.tv.material3.rememberDrawerState
import com.mplayer.app.net.Prefs
import com.mplayer.app.net.WebFetcher
import com.mplayer.app.util.Updater
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TvAppTheme { AppRoot() }
        }
    }

    // 播放影片或退到背景時讓隱藏的 WebView 休息，CPU 和記憶體留給播放器
    override fun onStart() {
        super.onStart()
        WebFetcher.resume()
    }

    override fun onStop() {
        super.onStop()
        WebFetcher.pause()
    }
}

private val Tab.icon: ImageVector
    get() = when (this) {
        Tab.Home -> Icons.Filled.Home
        Tab.Search -> Icons.Filled.Search
        Tab.Actresses -> Icons.Filled.Person
        Tab.Favorites -> Icons.Filled.Favorite
        Tab.History -> Icons.Filled.DateRange
    }

@Composable
fun AppRoot() {
    var firstLaunch by remember { mutableStateOf(true) }
    Box(Modifier.fillMaxSize().background(Bg)) {
        val top = TvNav.stack.lastOrNull()
        if (top == null) {
            HomeScreen(focusDrawer = firstLaunch, onFocused = { firstLaunch = false })
        } else {
            key(TvNav.stack.size, top) {
                when (top) {
                    is TvRoute.Detail -> DetailScreen(top)
                    is TvRoute.Listing -> ListingScreen(top)
                    TvRoute.Verify -> VerifyScreen()
                }
            }
        }
    }
    BackHandler(enabled = TvNav.stack.isNotEmpty()) { TvNav.pop() }

    // 開啟時檢查 GitHub 有沒有新版，由使用者決定要不要更新
    LaunchedEffect(Unit) {
        runCatching { Updater.check() }.getOrNull()
            ?.takeIf { it.versionCode != Prefs.skippedVersion }
            ?.let { Updater.pending = it }
    }
    Updater.pending?.let { release ->
        UpdateDialog(
            release,
            onDismiss = { Updater.pending = null },
            onSkip = {
                Prefs.skippedVersion = release.versionCode
                Updater.pending = null
            },
        )
    }
}

@Composable
fun HomeScreen(focusDrawer: Boolean, onFocused: () -> Unit) {
    val activity = LocalContext.current as Activity
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val requesters = remember { Tab.entries.associateWith { FocusRequester() } }
    var drawerFocused by remember { mutableStateOf(false) }
    val tab = TvState.tab

    LaunchedEffect(Unit) {
        if (focusDrawer) {
            delay(100)
            runCatching { requesters.getValue(tab).requestFocus() }
            onFocused()
        }
    }

    // 內容區按返回鍵先回到側邊選單，在選單上再按返回才離開
    BackHandler {
        if (drawerFocused) activity.finish()
        else runCatching { requesters.getValue(tab).requestFocus() }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = { value ->
            Column(
                Modifier
                    .fillMaxHeight()
                    .background(if (value == DrawerValue.Open) Color(0xFF17171C) else Color.Transparent)
                    .padding(12.dp)
                    .onFocusChanged { drawerFocused = it.hasFocus }
                    .selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Spacer(Modifier.height(16.dp))
                Text(
                    if (value == DrawerValue.Open) "MPlayer" else "M",
                    color = Accent,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 16.dp, bottom = 16.dp),
                )
                Tab.entries.forEach { t ->
                    NavigationDrawerItem(
                        selected = t == tab,
                        onClick = { TvState.tab = t },
                        leadingContent = { Icon(t.icon, contentDescription = null) },
                        modifier = Modifier.focusRequester(requesters.getValue(t)),
                    ) {
                        Text(t.label)
                    }
                }
            }
        },
    ) {
        Box(Modifier.fillMaxSize().padding(start = 92.dp)) {
            when (tab) {
                Tab.Home -> BrowseScreen()
                Tab.Search -> SearchScreen()
                Tab.Actresses -> ActressScreen()
                Tab.Favorites -> LibraryScreen(favorites = true)
                Tab.History -> LibraryScreen(favorites = false)
            }
        }
    }
}
