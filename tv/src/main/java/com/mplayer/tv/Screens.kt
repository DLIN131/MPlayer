package com.mplayer.tv

import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.mplayer.app.net.Site
import com.mplayer.app.net.Store
import com.mplayer.app.net.WebFetcher

/** 首頁：分類 × 排序 */
@Composable
fun BrowseScreen() {
    Column(Modifier.fillMaxSize()) {
        ScreenTitle("MPlayer") {
            TvButton(onClick = { TvState.feed.refresh() }) { Text("重新整理") }
        }
        ChipRow(Site.categories.map { it.path to it.name }, TvState.category) { TvState.category = it }
        ChipRow(Site.sorts, TvState.sort) { TvState.sort = it }
        VideoGrid(TvState.feed)
    }
}

/** 女優、類型、標籤等延伸列表 */
@Composable
fun ListingScreen(route: TvRoute.Listing) {
    Column(Modifier.fillMaxSize()) {
        ScreenTitle(route.title)
        ChipRow(Site.sorts, route.sort) { route.sort = it }
        if (route.isActress) {
            ChipRow(Site.casts, route.cast) { route.cast = it }
            ChipRow(Site.Censor.entries.map { it to it.label }, route.censor) { route.censor = it }
        }
        val censor = route.censor
        VideoGrid(route.feed, filter = { censor.accept(it) })
    }
}

@Composable
fun SearchScreen() {
    var query by rememberSaveable { mutableStateOf("") }

    fun search(q: String) {
        val word = q.trim()
        if (word.isEmpty()) return
        Store.addSearchWord(word)
        TvNav.push(TvRoute.Listing("搜尋：$word", Site.searchUrl(word)))
    }

    Column(Modifier.fillMaxSize()) {
        ScreenTitle("搜尋")
        Row(
            Modifier.padding(horizontal = 28.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { androidx.compose.material3.Text("番號、女優、標題") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { search(query) }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Accent,
                    cursorColor = Accent,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                ),
                modifier = Modifier.width(520.dp),
            )
            Spacer(Modifier.width(16.dp))
            TvButton(onClick = { search(query) }) { Text("搜尋") }
        }
        if (Store.searchWords.isNotEmpty()) {
            Row(
                Modifier.padding(start = 28.dp, end = 28.dp, top = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("搜尋紀錄", color = Gray, fontSize = 16.sp)
                Spacer(Modifier.width(16.dp))
                TvButton(onClick = Store::clearSearchWords) { Text("清除") }
            }
            ChipRow(Store.searchWords.map { it to it }, "") { search(it) }
        }
    }
}

@Composable
fun ActressScreen() {
    val feed = TvState.actresses
    LaunchedEffect(feed) { if (feed.items.isEmpty() && !feed.end) feed.loadMore() }
    Column(Modifier.fillMaxSize()) {
        ScreenTitle("女優一覽")
        if (feed.items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val err = feed.error
                if (err != null) ErrorBox(err, feed.blocked, onRetry = feed::loadMore) else Loading()
            }
            return
        }
        val context = LocalContext.current
        val shape = RoundedCornerShape(12.dp)
        LazyVerticalGrid(
            columns = GridCells.Fixed(8),
            state = feed.grid,
            contentPadding = PaddingValues(horizontal = 28.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(feed.items, key = { _, a -> a.url }, contentType = { _, _ -> "actress" }) { index, a ->
                if (index >= feed.items.size - 16) {
                    LaunchedEffect(feed.items.size) { feed.loadMore() }
                }
                Card(
                    onClick = { TvNav.push(TvRoute.Listing(a.name, a.url)) },
                    shape = CardDefaults.shape(shape),
                    scale = CardDefaults.scale(focusedScale = 1.08f),
                    border = CardDefaults.border(focusedBorder = Border(BorderStroke(3.dp, Color.White), shape = shape)),
                    colors = CardDefaults.colors(containerColor = CardBg, focusedContainerColor = CardFocusBg),
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        val request = remember(a.avatar) {
                            ImageRequest.Builder(context).data(a.avatar).size(200, 200).build()
                        }
                        AsyncImage(
                            model = request,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            alignment = Alignment.TopCenter,
                            modifier = Modifier.size(84.dp).clip(CircleShape).background(Color(0xFF26262D)),
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(a.name, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            a.info,
                            fontSize = 11.sp,
                            color = Gray,
                            maxLines = 2,
                            textAlign = TextAlign.Center,
                            lineHeight = 14.sp,
                        )
                    }
                }
            }
            val err = feed.error
            if (feed.loading || err != null) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        if (err != null) ErrorBox(err, feed.blocked, onRetry = feed::loadMore) else Loading(size = 32)
                    }
                }
            }
        }
    }
}

@Composable
fun LibraryScreen(favorites: Boolean) {
    Column(Modifier.fillMaxSize()) {
        ScreenTitle(if (favorites) "收藏" else "觀看紀錄") {
            if (!favorites && Store.history.isNotEmpty()) {
                TvButton(onClick = Store::clearHistory) { Text("清除紀錄") }
            }
        }
        if (favorites) LocalGrid(Store.favorites, "還沒有收藏，在影片頁按「收藏」加入")
        else LocalGrid(Store.history, "還沒有觀看紀錄")
    }
}

/** 自動驗證失敗時，讓使用者在看得到的 WebView 裡自己過 Cloudflare 驗證 */
@Composable
fun VerifyScreen() {
    Column(Modifier.fillMaxSize().background(Bg)) {
        Text(
            "完成網站驗證，看到首頁後按「返回」鍵回到 App，再按「重試」",
            color = Gray,
            fontSize = 15.sp,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 10.dp),
        )
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.userAgentString = WebFetcher.userAgent
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webViewClient = WebViewClient()
                    loadUrl(WebFetcher.BASE)
                    requestFocus()
                }
            },
            onRelease = {
                CookieManager.getInstance().flush()
                it.destroy()
            },
        )
    }
}
