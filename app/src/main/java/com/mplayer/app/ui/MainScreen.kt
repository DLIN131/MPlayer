package com.mplayer.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.mplayer.app.net.Site
import com.mplayer.app.net.Store
import com.mplayer.app.util.Updater
import kotlinx.coroutines.launch

private data class TabItem(val label: String, val icon: ImageVector)

private val tabs = listOf(
    TabItem("首頁", Icons.Filled.Home),
    TabItem("搜尋", Icons.Filled.Search),
    TabItem("女優", Icons.Filled.Person),
    TabItem("收藏", Icons.Filled.Favorite),
)

@Composable
fun MainScreen() {
    Scaffold(
        containerColor = Background,
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            NavigationBar(containerColor = Surface, windowInsets = WindowInsets(0)) {
                tabs.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = MainState.tab == i,
                        onClick = { MainState.tab = i },
                        icon = { Icon(t.icon, contentDescription = t.label) },
                        label = { Text(t.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color.White,
                            selectedTextColor = Accent,
                            indicatorColor = Accent,
                        ),
                    )
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (MainState.tab) {
                0 -> HomeTab()
                1 -> SearchTab()
                2 -> ActressTab()
                else -> LibraryTab()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppBar(title: String, back: Boolean = false, actions: @Composable () -> Unit = {}) {
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 19.sp) },
        navigationIcon = {
            if (back) IconButton(onClick = Nav::pop) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
        },
        actions = { actions() },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Background),
        windowInsets = WindowInsets(0),
    )
}

/** 排序選單按鈕 */
@Composable
fun SortButton(sort: String, onSort: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.AutoMirrored.Filled.List, contentDescription = "排序")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Site.sorts.forEach { (key, name) ->
                DropdownMenuItem(
                    text = { Text(name, color = if (key == sort) Accent else Color.Unspecified) },
                    onClick = {
                        open = false
                        onSort(key)
                    },
                )
            }
        }
    }
}

@Composable
private fun HomeTab() {
    Column(Modifier.fillMaxSize()) {
        AppBar("MPlayer") {
            SortButton(MainState.sort) { MainState.sort = it }
            IconButton(onClick = { MainState.feed.refresh() }) {
                Icon(Icons.Filled.Refresh, contentDescription = "重新整理")
            }
        }
        ScrollableTabRow(
            selectedTabIndex = MainState.category,
            containerColor = Background,
            contentColor = Accent,
            edgePadding = 8.dp,
        ) {
            Site.categories.forEachIndexed { i, c ->
                Tab(
                    selected = MainState.category == i,
                    onClick = { MainState.category = i },
                    text = { Text(c.name, color = if (MainState.category == i) Accent else Muted) },
                )
            }
        }
        FeedGrid(MainState.feed)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchTab() {
    var query by rememberSaveable { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current

    fun search(q: String) {
        val word = q.trim()
        if (word.isEmpty()) return
        keyboard?.hide()
        Store.addSearchWord(word)
        Nav.push(Route.Listing("搜尋：$word", Site.searchUrl(word)))
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        AppBar("搜尋")
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("番號、女優、標題") },
            singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { search(query) }),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Accent, cursorColor = Accent),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        if (Store.searchWords.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("搜尋紀錄", color = Muted, modifier = Modifier.weight(1f))
                TextButton(onClick = Store::clearSearchWords) { Text("清除", color = Muted) }
            }
            FlowRow(
                Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Store.searchWords.forEach { w ->
                    AssistChip(onClick = { query = w; search(w) }, label = { Text(w) })
                }
            }
        }
    }
}

@Composable
private fun ActressTab() {
    val feed = MainState.actresses
    LaunchedEffect(feed) { if (feed.items.isEmpty() && !feed.end) feed.loadMore() }
    Column(Modifier.fillMaxSize()) {
        AppBar("女優一覽")
        val state = feed.grid
        val last = state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        LaunchedEffect(last, feed.items.size, feed.loading) {
            if (last >= state.layoutInfo.totalItemsCount - 9 && !feed.end && !feed.loading && feed.error == null) {
                feed.loadMore()
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(104.dp),
            state = state,
            contentPadding = PaddingValues(10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(feed.items, key = { it.url }) { a ->
                Column(
                    Modifier.clickable { Nav.push(Route.Listing(a.name, a.url)) }.padding(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    AsyncImage(
                        model = a.avatar,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        alignment = Alignment.TopCenter,
                        modifier = Modifier.size(80.dp).clip(CircleShape).background(Surface),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(a.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                    Text(
                        a.info,
                        color = Muted,
                        fontSize = 11.sp,
                        maxLines = 2,
                        textAlign = TextAlign.Center,
                        lineHeight = 14.sp,
                    )
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                FeedFooter(feed.loading, feed.error, feed.blocked, false, feed::loadMore)
            }
        }
    }
}

@Composable
private fun LibraryTab() {
    var menu by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var updateMsg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize()) {
        AppBar("我的") {
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "更多") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(if (checking) "檢查中…" else "檢查更新（${Updater.currentVersionName}）") },
                        onClick = {
                            menu = false
                            if (checking) return@DropdownMenuItem
                            checking = true
                            scope.launch {
                                try {
                                    val r = Updater.check()
                                    if (r != null) Updater.pending = r else updateMsg = "已經是最新版本"
                                } catch (e: Exception) {
                                    updateMsg = "檢查更新失敗：${e.message}"
                                } finally {
                                    checking = false
                                }
                            }
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("清除觀看紀錄") },
                        onClick = {
                            menu = false
                            confirmClear = true
                        },
                    )
                }
            }
        }
        TabRow(selectedTabIndex = MainState.libraryTab, containerColor = Background, contentColor = Accent) {
            listOf("收藏", "觀看紀錄").forEachIndexed { i, name ->
                Tab(
                    selected = MainState.libraryTab == i,
                    onClick = { MainState.libraryTab = i },
                    text = { Text(name, color = if (MainState.libraryTab == i) Accent else Muted) },
                )
            }
        }
        if (MainState.libraryTab == 0) {
            VideoGrid(Store.favorites, emptyText = "還沒有收藏，在影片頁按愛心加入")
        } else {
            VideoGrid(Store.history, emptyText = "還沒有觀看紀錄")
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清除觀看紀錄？") },
            text = { Text("觀看紀錄和播放進度都會刪除，收藏不受影響。") },
            confirmButton = {
                TextButton(onClick = {
                    Store.clearHistory()
                    confirmClear = false
                }) { Text("清除", color = Accent) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } },
        )
    }
    updateMsg?.let { msg ->
        AlertDialog(
            onDismissRequest = { updateMsg = null },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { updateMsg = null }) { Text("確定") } },
        )
    }
}

@Composable
fun ListingScreen(route: Route.Listing) {
    Column(Modifier.fillMaxSize()) {
        AppBar(route.title, back = true) {
            SortButton(route.sort) { route.sort = it }
        }
        if (route.isActress) {
            FilterRow(Site.casts, route.cast) { route.cast = it }
            FilterRow(Site.Censor.entries.map { it to it.label }, route.censor) { route.censor = it }
        }
        val censor = route.censor
        FeedGrid(route.feed) { censor.accept(it) }
    }
}

/** 一排可以單選的篩選標籤 */
@Composable
fun <T> FilterRow(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { (key, name) ->
            FilterChip(
                selected = key == selected,
                onClick = { if (key != selected) onSelect(key) },
                label = { Text(name) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Accent,
                    selectedLabelColor = Color.White,
                ),
            )
        }
    }
}

/** 小工具：顯示秒數為 h:mm:ss */
fun formatTime(sec: Float): String {
    val s = sec.toInt()
    val h = s / 3600
    val m = s % 3600 / 60
    val ss = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, ss) else "%d:%02d".format(m, ss)
}
