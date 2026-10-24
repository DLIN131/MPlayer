package com.mplayer.tv

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.annotation.OptIn
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.mplayer.app.data.VideoSource
import com.mplayer.app.net.Store
import com.mplayer.app.net.Video
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 電視版全螢幕播放器。用一般 View 不用 Compose，盒子上比較省資源。
 * 不用 PlayerView 自帶的控制列（它會搶方向鍵去移動按鈕焦點），按鍵全部自己處理，只畫一條進度列：
 * 左右 = 倒退／快轉 10 秒（長按加速），OK = 暫停／播放，長按 OK = 按住期間 2 倍速，
 * 上鍵或選單鍵 = 換畫質，下鍵 = 顯示進度列，返回 = 離開。
 */
@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_CODE = "code"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_PLAYLIST = "playlist"
        private const val SEEK_STEP_MS = 10_000L
        private const val BOOST_SPEED = 2f
        /** 兩次 OK 間隔比這短就視為長按的自動重複（人手連按不會這麼快） */
        private const val AUTO_REPEAT_GAP_MS = 200L
        private const val OVERLAY_TIMEOUT_MS = 3_500L

        fun start(context: Context, video: Video, playlist: String) {
            context.startActivity(
                Intent(context, PlayerActivity::class.java)
                    .putExtra(EXTRA_CODE, video.code)
                    .putExtra(EXTRA_TITLE, video.title)
                    .putExtra(EXTRA_PLAYLIST, playlist)
            )
        }
    }

    private lateinit var player: ExoPlayer
    private lateinit var hint: TextView
    private lateinit var controls: View
    private lateinit var status: TextView
    private lateinit var timeText: TextView
    private lateinit var progress: ProgressBar
    private var hintJob: Job? = null
    private var overlayJob: Job? = null

    private var code = ""
    private var ended = false
    /** 畫面高度清單（高到低）；-1 = 還沒決定，0 = 自動 */
    private var heights: List<Int> = emptyList()
    private var quality = -1

    /** 按住左右鍵期間累積的目標位置，放開才真的 seek，避免連續 seek 卡頓 */
    private var seekTarget: Long? = null
    private var okDown = false
    private var boosting = false
    private var lastOkUp = 0L
    private var boostRelease: Job? = null

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) showOverlay() else updateOverlay()
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED) {
                ended = true
                Store.savePosition(code, 0f)
                showOverlay()
            }
            updateOverlay()
        }

        override fun onPlayerError(error: PlaybackException) {
            showHint("播放失敗（${error.errorCodeName}），按 OK 重試", long = true)
            showOverlay()
        }

        override fun onTracksChanged(tracks: Tracks) {
            heights = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
                .flatMap { g -> (0 until g.length).map { g.getTrackFormat(it).height } }
                .filter { it > 0 }.distinct().sortedDescending()
            // 預設直接用最高畫質（有 1080p 就是 1080p）
            if (quality == -1 && heights.isNotEmpty()) applyQuality(heights.first())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        code = intent.getStringExtra(EXTRA_CODE).orEmpty()
        val playlist = intent.getStringExtra(EXTRA_PLAYLIST).orEmpty()
        if (code.isEmpty() || playlist.isEmpty()) {
            finish()
            return
        }

        player = ExoPlayer.Builder(this)
            .setLoadControl(VideoSource.lowMemoryLoadControl())
            .build()
            .also { it.addListener(listener) }

        val playerView = PlayerView(this).apply {
            player = this@PlayerActivity.player
            useController = false
            isFocusable = false
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            setBackgroundColor(Color.BLACK)
            keepScreenOn = true
        }
        hint = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 18f
            setBackgroundColor(0xAA000000.toInt())
            setPadding(28, 14, 28, 14)
            visibility = View.GONE
        }
        setContentView(FrameLayout(this).apply {
            addView(playerView)
            this@PlayerActivity.controls = buildOverlay(intent.getStringExtra(EXTRA_TITLE).orEmpty())
            addView(this@PlayerActivity.controls, FrameLayout.LayoutParams(-1, -1))
            addView(hint, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
                topMargin = 40
                rightMargin = 40
            })
        })

        // targetSdk 36 預設啟用預測返回，返回鍵不會經過 dispatchKeyEvent
        onBackPressedDispatcher.addCallback(this) { finish() }

        player.setMediaSource(VideoSource.hls(playlist))
        val startMs = (Store.position(code) * 1000).toLong()
        if (startMs > 5_000) {
            player.seekTo(startMs)
            showHint("從 ${fmtMs(startMs)} 繼續播放")
        }
        player.prepare()
        player.playWhenReady = true
        showOverlay()
        startTicker()
    }

    /** 上方標題＋下方狀態、時間、進度條 */
    private fun buildOverlay(title: String): View {
        val titleView = TextView(this).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 18f
            maxLines = 2
            setShadowLayer(6f, 0f, 0f, Color.BLACK)
            setPadding(48, 32, 48, 0)
        }
        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(0, 0, 24, 0)
        }
        timeText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(24, 0, 0, 0)
        }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(0xFFE0457B.toInt())
            secondaryProgressTintList = ColorStateList.valueOf(0x66FFFFFF)
            progressBackgroundTintList = ColorStateList.valueOf(0x33FFFFFF)
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(48, 28, 48, 36)
            setBackgroundColor(0x99000000.toInt())
            addView(status)
            addView(progress, LinearLayout.LayoutParams(0, -2, 1f))
            addView(timeText)
        }
        return FrameLayout(this).apply {
            addView(titleView, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
            addView(bar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
            visibility = View.GONE
        }
    }

    /** 指定畫質：0 = 自動（依網速切換），其他是畫面高度 */
    private fun applyQuality(h: Int) {
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

    // ---------- 進度列 ----------

    private fun showOverlay() {
        controls.visibility = View.VISIBLE
        updateOverlay()
        overlayJob?.cancel()
        overlayJob = lifecycleScope.launch {
            delay(OVERLAY_TIMEOUT_MS)
            // 暫停、拖動進度、長按加速時不自動隱藏
            while (isActive && (!player.isPlaying || seekTarget != null || boosting)) delay(500)
            controls.visibility = View.GONE
        }
    }

    private fun updateOverlay() {
        if (controls.visibility != View.VISIBLE) return
        val dur = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
        val pos = seekTarget ?: player.currentPosition
        status.text = when {
            boosting -> "▶▶ 2x"
            seekTarget != null -> if (seekTarget!! >= player.currentPosition) "▶▶" else "◀◀"
            ended -> "■"
            player.playWhenReady -> "▶"
            else -> "❚❚"
        }
        val q = if (quality > 0) "  ·  ${quality}p" else ""
        timeText.text = "${fmtMs(pos)} / ${if (dur > 0) fmtMs(dur) else "--:--"}$q"
        if (dur > 0) {
            progress.progress = (pos * 1000 / dur).toInt()
            progress.secondaryProgress = (player.bufferedPosition * 1000 / dur).toInt()
        }
    }

    private fun startTicker() {
        lifecycleScope.launch {
            while (isActive) {
                // 進度列看不到時不用一直更新
                if (controls.visibility == View.VISIBLE) updateOverlay()
                delay(500)
            }
        }
    }

    // ---------- 遙控器 ----------

    private fun seekBy(forward: Boolean, repeat: Int) {
        val dur = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return
        val base = seekTarget ?: player.currentPosition
        // 第一下 10 秒；長按時依影片長度加速，大約 7 秒可以從頭掃到尾
        val step = if (repeat == 0) SEEK_STEP_MS else (dur / 150).coerceIn(5_000L, 60_000L)
        seekTarget = (base + if (forward) step else -step).coerceIn(0L, dur)
        showOverlay()
    }

    private fun commitSeek() {
        val target = seekTarget ?: return
        seekTarget = null
        player.seekTo(target)
        if (ended) {
            ended = false
            player.play()
        }
        showOverlay()
    }

    private fun startBoost() {
        boosting = true
        seekTarget = null
        if (!player.isPlaying) player.play()
        player.setPlaybackSpeed(BOOST_SPEED)
        showOverlay()
    }

    private fun stopBoost() {
        boosting = false
        player.setPlaybackSpeed(1f)
        showOverlay()
    }

    private fun togglePlay() {
        when {
            // 出錯後按 OK 重試
            player.playerError != null -> {
                player.prepare()
                player.play()
            }
            ended -> {
                ended = false
                player.seekTo(0)
                player.play()
            }
            else -> player.playWhenReady = !player.playWhenReady
        }
        showOverlay()
    }

    private fun chooseQuality() {
        if (heights.isEmpty()) return
        val options = listOf(0) + heights
        val labels = options.map { h ->
            (if (h == 0) "自動" else "${h}p") + if (h == quality) "（目前）" else ""
        }.toTypedArray()
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("選擇畫質")
            .setItems(labels) { _, i ->
                val h = options[i]
                if (h != quality) {
                    applyQuality(h)
                    showHint(if (h == 0) "畫質：自動" else "已切換到 ${h}p")
                }
            }
            .show()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        val first = down && event.repeatCount == 0

        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                if (down) seekBy(false, event.repeatCount) else commitSeek()
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                if (down) seekBy(true, event.repeatCount) else commitSeek()
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_SPACE -> {
                // 短按：暫停／播放（放開時才判斷）；長按：放開前以 2 倍速播放。
                // 長按有兩種送法：一般遙控器送 repeatCount > 0 的 DOWN；
                // 部分紅外線遙控器則連續送很密的「按下＋放開」。
                val now = SystemClock.uptimeMillis()
                if (down) {
                    if (event.repeatCount == 0) {
                        okDown = true
                        if (now - lastOkUp < AUTO_REPEAT_GAP_MS) {
                            boostRelease?.cancel()
                            if (!boosting) startBoost()
                        }
                    } else if (okDown && !boosting) {
                        startBoost()
                    }
                } else {
                    lastOkUp = now
                    if (boosting) {
                        // 等一下確認是真的放開，而不是下一次自動重複
                        boostRelease?.cancel()
                        boostRelease = lifecycleScope.launch {
                            delay(AUTO_REPEAT_GAP_MS)
                            stopBoost()
                        }
                    } else if (okDown) {
                        togglePlay()
                    }
                    okDown = false
                }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (first) togglePlay()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                if (first) { player.play(); showOverlay() }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                if (first) { player.pause(); showOverlay() }
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS -> {
                if (first) chooseQuality()
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (first) {
                    if (controls.visibility == View.VISIBLE && player.isPlaying) controls.visibility = View.GONE
                    else showOverlay()
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun showHint(text: String, long: Boolean = false) {
        hint.text = text
        hint.visibility = View.VISIBLE
        hintJob?.cancel()
        hintJob = lifecycleScope.launch {
            delay(if (long) 6_000 else 1_800)
            hint.visibility = View.GONE
        }
    }

    override fun onPause() {
        super.onPause()
        if (!::player.isInitialized) return
        boostRelease?.cancel()
        if (boosting) stopBoost()
        okDown = false
        // 看到最後 30 秒內就當作看完，下次從頭開始
        val pos = player.currentPosition
        val dur = player.duration
        val done = ended || (dur > 0 && dur - pos < 30_000)
        if (pos > 0) Store.savePosition(code, if (done) 0f else pos / 1000f)
        player.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::player.isInitialized) player.release()
    }

    private fun fmtMs(ms: Long) = fmtTime(ms / 1000f)
}
