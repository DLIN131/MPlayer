package com.mplayer.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.mplayer.app.net.Store

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VideoScreen(route: Route.VideoPage) {
    LaunchedEffect(route) { if (route.detail == null && route.error == null) route.load() }
    val d = route.detail
    val video = d?.video ?: route.preview
    val fav = Store.isFavorite(video.code)

    Column(Modifier.fillMaxSize()) {
        AppBar(video.code.uppercase(), back = true) {
            IconButton(onClick = { Store.toggleFavorite(video.copy(cover = route.preview.cover.ifEmpty { video.cover }, duration = route.preview.duration)) }) {
                Icon(
                    if (fav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = "收藏",
                    tint = if (fav) Accent else Color.White,
                )
            }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Surface)) {
                AsyncImage(
                    model = d?.cover?.ifEmpty { null } ?: route.preview.cover,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Column(Modifier.padding(16.dp)) {
                SelectionContainer {
                    Text(
                        video.title.ifEmpty { route.preview.title },
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Medium,
                        lineHeight = 24.sp,
                    )
                }
                Spacer(Modifier.height(16.dp))
                when {
                    d != null && d.playlist != null -> {
                        val resume = Store.position(video.code)
                        Button(
                            onClick = {
                                Store.addHistory(
                                    video.copy(
                                        cover = route.preview.cover.ifEmpty { video.cover },
                                        duration = route.preview.duration,
                                    )
                                )
                                Nav.push(Route.Player(video, d.playlist))
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Accent),
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                        ) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(if (resume > 5) "繼續播放（${formatTime(resume)}）" else "播放", fontSize = 16.sp)
                        }
                    }
                    d != null -> Text("找不到播放來源", color = Muted)
                    route.error != null -> FeedFooter(false, route.error, route.blocked, false, route::load)
                    else -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Accent, modifier = Modifier.size(32.dp))
                    }
                }
                d?.rows?.forEach { row ->
                    Spacer(Modifier.height(14.dp))
                    Text(row.label, color = Muted, fontSize = 13.sp)
                    Spacer(Modifier.height(4.dp))
                    if (row.links.isEmpty()) {
                        SelectionContainer { Text(row.text, fontSize = 14.sp, lineHeight = 20.sp) }
                    } else {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.links.forEach { link ->
                                AssistChip(
                                    onClick = { Nav.push(Route.Listing(link.name, link.url)) },
                                    label = { Text(link.name) },
                                )
                            }
                        }
                    }
                }
                Row(Modifier.height(24.dp)) {}
            }
        }
    }
}
