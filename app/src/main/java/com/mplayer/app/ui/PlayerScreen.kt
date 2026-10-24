package com.mplayer.app.ui

import android.app.Activity
import android.view.LayoutInflater
import android.view.WindowManager
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.mplayer.app.R
import com.mplayer.app.data.VideoSource
import com.mplayer.app.net.Store
import kotlinx.coroutines.delay

private val speeds = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)

/** 全螢幕播放器；影片來源和請求標頭見 [VideoSource] */
@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(route: Route.Player) {
    val activity = LocalContext.current as Activity
    val code = route.video.code
    var error by remember { mutableStateOf<String?>(null) }
    var buffering by remember { mutableStateOf(true) }
    var controlsVisible by remember { mutableStateOf(true) }
    var heights by remember { mutableStateOf(listOf<Int>()) }
    var quality by remember { mutableIntStateOf(-1) } // -1 = 還沒決定，0 = 自動，其他是畫面高度
    var speed by remember { mutableStateOf(1f) }
    var hint by remember { mutableStateOf<String?>(null) }
    var hintSeq by remember { mutableIntStateOf(0) }
    var holding2x by remember { mutableStateOf(false) }

    fun showHint(text: String) {
        hint = text
        hintSeq++
    }
    LaunchedEffect(hintSeq) {
        delay(800)
        hint = null
    }

    val player = remember {
        ExoPlayer.Builder(activity)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build().apply {
                setMediaSource(VideoSource.hls(route.playlist))
                val start = Store.position(code)
                if (start > 5) seekTo((start * 1000).toLong())
                prepare()
                playWhenReady = true
            }
    }

    /** 指定畫質：0 = 自動（依網速切換），其他是畫面高度 */
    fun applyQuality(h: Int) {
        quality = h
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
            clearOverridesOfType(C.TRACK_TYPE_VIDEO)
            if (h != 0) {
                player.currentTracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO }?.let { g ->
                    val idx = (0 until g.length).firstOrNull { g.getTrackFormat(it).height == h }
                    if (idx != null) setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, idx))
                }
            }
        }.build()
    }

    fun seekBy(sec: Int) {
        val dur = player.duration.takeIf { it != C.TIME_UNSET } ?: Long.MAX_VALUE
        player.seekTo((player.currentPosition + sec * 1000L).coerceIn(0, dur))
        showHint(
            when {
                sec >= 60 -> "快轉 1 分鐘"
                sec > 0 -> "快轉 $sec 秒"
                sec <= -60 -> "倒退 1 分鐘"
                else -> "倒退 ${-sec} 秒"
            }
        )
    }

    fun savePosition() {
        val dur = player.duration
        val pos = player.currentPosition
        if (pos <= 0) return
        // 快看完就當作看完，下次從頭開始
        val done = dur != C.TIME_UNSET && pos > dur - 30_000
        Store.savePosition(code, if (done) 0f else pos / 1000f)
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING || state == Player.STATE_IDLE
                if (state == Player.STATE_READY) error = null
                if (state == Player.STATE_ENDED) Store.savePosition(code, 0f)
            }

            override fun onPlayerError(e: PlaybackException) {
                val cause = generateSequence(e.cause) { it.cause }.lastOrNull()?.message
                error = "播放失敗（${e.errorCodeName}）" + (cause?.let { "\n$it" } ?: "")
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) savePosition()
            }

            override fun onTracksChanged(tracks: Tracks) {
                heights = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
                    .flatMap { g -> (0 until g.length).map { g.getTrackFormat(it).height } }
                    .filter { it > 0 }.distinct().sortedDescending()
                // 預設直接用最高畫質（有 1080p 就是 1080p），不讓自動切換先從低畫質開始
                if (quality == -1 && heights.isNotEmpty()) applyQuality(heights.first())
            }
        }
        player.addListener(listener)
        onDispose {
            savePosition()
            player.removeListener(listener)
            player.release()
        }
    }

    // 播放中每 5 秒存一次進度，被系統殺掉也不會全部遺失
    LaunchedEffect(player) {
        while (true) {
            delay(5000)
            if (player.isPlaying) savePosition()
        }
    }

    DisposableEffect(Unit) {
        val window = activity.window
        val ctrl = WindowCompat.getInsetsController(window, window.decorView)
        ctrl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        ctrl.hide(WindowInsetsCompat.Type.systemBars())
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            ctrl.show(WindowInsetsCompat.Type.systemBars())
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                (LayoutInflater.from(ctx).inflate(R.layout.video_player, null) as PlayerView).apply {
                    this.player = player
                    setShowSubtitleButton(false)
                    setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v ->
                        controlsVisible = v == android.view.View.VISIBLE
                    })
                    setOnTouchListener(VideoGestures(
                        view = this,
                        onTap = { if (isControllerFullyVisible) hideController() else showController() },
                        onMultiTap = { taps, right ->
                            val sec = if (taps >= 3) 60 else 10
                            seekBy(if (right) sec else -sec)
                        },
                        onHold = { down ->
                            holding2x = down
                            player.setPlaybackSpeed(if (down) 2f else speed)
                        },
                    ))
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        if (buffering && error == null) {
            CircularProgressIndicator(color = Accent, modifier = Modifier.align(Alignment.Center))
        }

        val badge = if (holding2x) "2 倍速播放中 ▶▶" else hint
        if (badge != null) {
            Text(
                badge,
                color = Color.White,
                fontSize = 15.sp,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 64.dp)
                    .background(Color(0x99000000), RoundedCornerShape(20.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        // 上方列：返回、標題、倍速、畫質，跟著播放器控制列一起顯示／隱藏
        if (controlsVisible || error != null) {
            Row(
                Modifier.fillMaxWidth().background(Color(0x88000000)).statusBarsPadding().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = Nav::pop) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Color.White)
                }
                Text(
                    route.video.title.ifEmpty { code.uppercase() },
                    color = Color.White,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Menu(
                    label = if (speed == 1f) "倍速" else "${speed}x",
                    options = speeds.map { "${it}x" },
                    selected = speeds.indexOf(speed),
                ) {
                    speed = speeds[it]
                    player.setPlaybackSpeed(speed)
                }
                if (heights.size > 1) {
                    Menu(
                        label = if (quality <= 0) "自動" else "${quality}p",
                        options = listOf("自動") + heights.map { "${it}p" },
                        selected = if (quality <= 0) 0 else heights.indexOf(quality) + 1,
                    ) { i -> applyQuality(if (i == 0) 0 else heights[i - 1]) }
                }
            }
        }

        error?.let { msg ->
            Column(
                Modifier.align(Alignment.Center)
                    .padding(24.dp)
                    .background(Color(0xCC000000), RoundedCornerShape(10.dp))
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(msg, color = Color.White, textAlign = TextAlign.Center, fontSize = 14.sp)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = {
                            error = null
                            player.prepare()
                            player.play()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Accent),
                    ) { Text("重試") }
                    TextButton(onClick = Nav::pop) { Text("返回", color = Color.White) }
                }
            }
        }
    }
}

@Composable
private fun Menu(label: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text(label, color = Color.White, fontSize = 14.sp) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEachIndexed { i, name ->
                DropdownMenuItem(
                    text = { Text(name, color = if (i == selected) Accent else Color.Unspecified) },
                    onClick = {
                        open = false
                        onSelect(i)
                    },
                )
            }
        }
    }
}
