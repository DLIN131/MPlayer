package com.mplayer.tv

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.mplayer.app.data.Feed
import com.mplayer.app.net.Video
import kotlinx.coroutines.delay

@Composable
fun TvButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        scale = ButtonDefaults.scale(focusedScale = 1.06f),
        colors = ButtonDefaults.colors(
            containerColor = if (selected) Accent else Color.White.copy(alpha = 0.1f),
            contentColor = Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
            pressedContainerColor = Color.White.copy(alpha = 0.8f),
            pressedContentColor = Color.Black,
        ),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
        content = content,
    )
}

private val cardShape = RoundedCornerShape(10.dp)
private val focusBorder = Border(BorderStroke(3.dp, Color.White), shape = cardShape)

@Composable
fun VideoCard(item: Video, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // 封面固定解碼成小尺寸，一頁幾十張也不會吃光記憶體
    val request = remember(item.cover) {
        ImageRequest.Builder(context).data(item.cover).size(400, 225).build()
    }
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = CardDefaults.shape(cardShape),
        scale = CardDefaults.scale(focusedScale = 1.06f),
        border = CardDefaults.border(focusedBorder = focusBorder),
        colors = CardDefaults.colors(containerColor = CardBg, focusedContainerColor = CardFocusBg),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(Color(0xFF26262D))
        ) {
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (item.duration.isNotEmpty()) {
                Text(
                    item.duration,
                    fontSize = 11.sp,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .background(Color(0xB0000000), RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
        }
        Text(
            item.title.ifEmpty { item.code.uppercase() },
            fontSize = 13.sp,
            lineHeight = 18.sp,
            maxLines = 2,
            minLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }
}

@Composable
fun Loading(modifier: Modifier = Modifier, size: Int = 48) {
    Box(modifier, contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Accent, modifier = Modifier.size(size.dp))
    }
}

/** 錯誤訊息；被 Cloudflare 擋的話多一顆「開啟驗證頁面」 */
@Composable
fun ErrorBox(message: String, blocked: Boolean, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(message, color = Gray, fontSize = 16.sp)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (blocked) TvButton(onClick = { TvNav.push(TvRoute.Verify) }) { Text("開啟驗證頁面") }
            TvButton(onClick = onRetry) { Text("重試") }
        }
    }
}

@Composable
fun ScreenTitle(title: String, modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = 28.dp, end = 28.dp, top = 22.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.weight(1f))
        actions()
    }
}

/** 一排單選按鈕（分類、排序、篩選） */
@Composable
fun <T> ChipRow(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 28.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(options, key = { it.second }) { (key, name) ->
            TvButton(onClick = { if (key != selected) onSelect(key) }, selected = key == selected) { Text(name) }
        }
    }
}

/**
 * 影片格狀列表，接近底部自動載下一頁。
 * filter 是 App 端的篩選（有碼／無碼），篩完太少會繼續往下載。
 */
@Composable
fun VideoGrid(
    feed: Feed<Video>,
    modifier: Modifier = Modifier,
    filter: (Video) -> Boolean = { true },
) {
    LaunchedEffect(feed) { if (feed.items.isEmpty() && !feed.end) feed.loadMore() }
    val items = feed.items.filter(filter)

    // 篩選後一張都沒有、但網站還有下一頁：繼續載
    LaunchedEffect(feed, items.size, feed.loading, feed.end) {
        if (items.size < 10 && !feed.end && !feed.loading && feed.error == null && feed.items.isNotEmpty()) feed.loadMore()
    }

    if (items.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val err = feed.error
            when {
                feed.loading || !feed.end && err == null -> Loading()
                err != null -> ErrorBox(err, feed.blocked, onRetry = feed::loadMore)
                else -> Text("這裡什麼都沒有", color = Gray, fontSize = 18.sp)
            }
        }
        return
    }

    val restore = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (feed.restoreFocus) {
            feed.restoreFocus = false
            delay(80)
            runCatching { restore.requestFocus() }
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(5),
        state = feed.grid,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 28.dp, vertical = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        itemsIndexed(items, key = { _, it -> it.code }, contentType = { _, _ -> "video" }) { index, item ->
            if (index >= items.size - 10) {
                LaunchedEffect(items.size) { feed.loadMore() }
            }
            VideoCard(
                item = item,
                onClick = {
                    feed.focusIndex = index
                    feed.restoreFocus = true
                    TvNav.push(TvRoute.Detail(item))
                },
                modifier = if (index == feed.focusIndex) Modifier.focusRequester(restore) else Modifier,
            )
        }
        val err = feed.error
        if (feed.loading || err != null) {
            item(span = { GridItemSpan(maxLineSpan) }, contentType = "footer") {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    if (err != null) ErrorBox(err, feed.blocked, onRetry = feed::loadMore) else Loading(size = 32)
                }
            }
        }
    }
}

/** 本機清單（收藏、觀看紀錄），一次全部列出 */
@Composable
fun LocalGrid(items: List<Video>, empty: String, modifier: Modifier = Modifier) {
    if (items.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(empty, color = Gray, fontSize = 18.sp)
        }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(5),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 28.dp, vertical = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        itemsIndexed(items, key = { _, it -> it.code }, contentType = { _, _ -> "video" }) { _, item ->
            VideoCard(item, onClick = { TvNav.push(TvRoute.Detail(item)) })
        }
    }
}
