package com.mplayer.app.ui

import android.view.LayoutInflater
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.mplayer.app.net.Video

/** 長按封面時要預覽的影片；放開手指就清掉 */
object PreviewState {
    var video by mutableStateOf<Video?>(null)
}

/** 網站每部片都有一段幾秒的預覽短片，跟封面放在同一個資料夾 */
fun previewUrl(v: Video): String? =
    v.cover.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }?.let { "$it/preview.mp4" }

/** 蓋在整個畫面上的預覽視窗，靜音循環播放 */
@OptIn(UnstableApi::class)
@Composable
fun PreviewOverlay() {
    val v = PreviewState.video ?: return
    val url = previewUrl(v) ?: return
    val context = LocalContext.current
    var ready by remember(url) { mutableStateOf(false) }
    var failed by remember(url) { mutableStateOf(false) }
    val player = remember(url) {
        ExoPlayer.Builder(context).build().apply {
            volume = 0f
            repeatMode = Player.REPEAT_MODE_ONE
            setMediaItem(MediaItem.fromUri(url))
            addListener(object : Player.Listener {
                override fun onRenderedFirstFrame() {
                    ready = true
                }

                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    failed = true
                }
            })
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }

    Box(
        Modifier.fillMaxSize().background(Color(0xAA000000)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .padding(16.dp)
                .widthIn(max = 640.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Surface)
        ) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)) {
                AndroidView(
                    factory = { ctx ->
                        (LayoutInflater.from(ctx).inflate(com.mplayer.app.R.layout.preview_player, null) as PlayerView).apply {
                            useController = false
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                            setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                            this.player = player
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                // 短片還沒出來前先顯示封面
                if (!ready) {
                    AsyncImage(
                        model = v.cover,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (failed) {
                        Text(
                            "這部沒有預覽",
                            color = Color.White,
                            modifier = Modifier.align(Alignment.Center)
                                .background(Color(0xAA000000), RoundedCornerShape(6.dp))
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    } else {
                        CircularProgressIndicator(color = Accent, modifier = Modifier.align(Alignment.Center))
                    }
                }
            }
            Text(
                v.title.ifEmpty { v.code.uppercase() },
                fontSize = 14.sp,
                lineHeight = 19.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(12.dp),
            )
        }
    }
}
