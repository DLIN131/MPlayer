package com.mplayer.tv

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.mplayer.app.net.Store
import kotlinx.coroutines.delay

@Composable
fun DetailScreen(route: TvRoute.Detail) {
    LaunchedEffect(route) { if (route.detail == null && route.error == null) route.load() }
    val context = LocalContext.current
    val d = route.detail
    val video = (d?.video ?: route.preview).let {
        // 列表上的封面和片長比較準，存收藏／紀錄時沿用
        it.copy(cover = route.preview.cover.ifEmpty { it.cover }, duration = route.preview.duration)
    }
    val playFocus = remember { FocusRequester() }
    val fav = Store.isFavorite(video.code)

    LaunchedEffect(d?.playlist) {
        if (d?.playlist != null) {
            delay(60)
            runCatching { playFocus.requestFocus() }
        }
    }

    Row(Modifier.fillMaxSize().padding(36.dp)) {
        // 左邊封面
        Box(
            Modifier
                .width(520.dp)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(12.dp))
                .background(CardBg)
        ) {
            val request = remember(d?.cover, route.preview.cover) {
                ImageRequest.Builder(context)
                    .data(d?.cover?.ifEmpty { null } ?: route.preview.cover)
                    .size(960, 540)
                    .build()
            }
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(Modifier.width(32.dp))
        // 右邊標題、按鈕、資訊
        LazyColumn(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item {
                Text(video.code.uppercase(), color = Accent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text(
                    video.title.ifEmpty { route.preview.title },
                    fontSize = 22.sp,
                    lineHeight = 30.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    when {
                        d?.playlist != null -> {
                            val resume = Store.position(video.code)
                            TvButton(
                                onClick = {
                                    Store.addHistory(video)
                                    PlayerActivity.start(context, video, d.playlist!!)
                                },
                                modifier = Modifier.focusRequester(playFocus),
                            ) {
                                Text(if (resume > 5) "▶  繼續播放（${fmtTime(resume)}）" else "▶  播放")
                            }
                        }
                        d != null -> Text("找不到播放來源", color = Gray, fontSize = 16.sp)
                        route.error != null -> ErrorBox(route.error!!, route.blocked, onRetry = route::load)
                        else -> Loading(size = 32)
                    }
                    TvButton(onClick = { Store.toggleFavorite(video) }, selected = fav) {
                        Text(if (fav) "♥  已收藏" else "♡  收藏")
                    }
                }
            }
            d?.rows?.forEach { row ->
                item(key = row.label) {
                    Column {
                        Text(row.label, color = Gray, fontSize = 14.sp)
                        Spacer(Modifier.height(4.dp))
                        if (row.links.isEmpty()) {
                            Text(row.text, fontSize = 15.sp, lineHeight = 21.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        } else {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                items(row.links, key = { it.url }) { link ->
                                    TvButton(onClick = { TvNav.push(TvRoute.Listing(link.name, link.url)) }) {
                                        Text(link.name, fontSize = 14.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 秒數顯示成 h:mm:ss */
fun fmtTime(sec: Float): String {
    val s = sec.toInt()
    val h = s / 3600
    val m = s % 3600 / 60
    val ss = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, ss) else "%d:%02d".format(m, ss)
}

