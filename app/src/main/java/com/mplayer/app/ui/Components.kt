package com.mplayer.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.mplayer.app.net.Video

val Accent = Color(0xFFE0457B)
val Background = Color(0xFF16181D)
val Surface = Color(0xFF22252C)
val Muted = Color(0xFF9AA0AA)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            secondary = Accent,
            background = Background,
            surface = Background,
            surfaceContainer = Surface,
            surfaceVariant = Surface,
        ),
        content = content,
    )
}

/** 載入中／錯誤／沒有資料，放在列表最下面 */
@Composable
fun FeedFooter(loading: Boolean, error: String?, blocked: Boolean, empty: Boolean, onRetry: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        when {
            loading -> CircularProgressIndicator(color = Accent, modifier = Modifier.size(32.dp))
            error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(error, color = Muted, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                if (blocked) {
                    Button(
                        onClick = { Nav.push(Route.Verify) },
                        colors = ButtonDefaults.buttonColors(containerColor = Accent),
                    ) { Text("開啟驗證頁") }
                    Spacer(Modifier.height(8.dp))
                }
                OutlinedButton(onClick = onRetry) { Text("重試") }
            }
            empty -> Text("沒有內容", color = Muted)
        }
    }
}

/** 影片格狀列表，捲到底自動載入下一頁 */
@Composable
fun VideoGrid(
    items: List<Video>,
    state: LazyGridState = rememberLazyGridState(),
    loading: Boolean = false,
    error: String? = null,
    blocked: Boolean = false,
    end: Boolean = true,
    onLoadMore: () -> Unit = {},
    onRetry: () -> Unit = {},
    emptyText: String = "沒有內容",
) {
    val nearEnd by remember(state) {
        derivedStateOf {
            val last = state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= state.layoutInfo.totalItemsCount - 6
        }
    }
    LaunchedEffect(nearEnd, end, loading, error, items.size) {
        if (nearEnd && !end && !loading && error == null) onLoadMore()
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(170.dp),
        state = state,
        contentPadding = PaddingValues(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(items, key = { it.code }) { v ->
            VideoCard(v) { Nav.push(Route.VideoPage(v)) }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            if (items.isEmpty() && !loading && error == null && end) {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(emptyText, color = Muted)
                }
            } else {
                FeedFooter(loading, error, blocked, false, onRetry)
            }
        }
    }
}

@Composable
fun FeedGrid(feed: Feed<Video>) {
    LaunchedEffect(feed) { if (feed.items.isEmpty() && !feed.end) feed.loadMore() }
    VideoGrid(
        items = feed.items,
        state = feed.grid,
        loading = feed.loading,
        error = feed.error,
        blocked = feed.blocked,
        end = feed.end,
        onLoadMore = feed::loadMore,
        onRetry = feed::loadMore,
    )
}

@Composable
fun VideoCard(v: Video, onClick: () -> Unit) {
    Column(Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(Surface)) {
            AsyncImage(
                model = v.cover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (v.duration.isNotEmpty()) {
                Text(
                    v.duration,
                    color = Color.White,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .background(Color(0xB0000000), RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
        }
        Text(
            v.title.ifEmpty { v.code.uppercase() },
            color = Color(0xFFE6E6E6),
            fontSize = 13.sp,
            lineHeight = 17.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp),
        )
    }
}
