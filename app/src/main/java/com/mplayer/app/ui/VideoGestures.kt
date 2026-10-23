package com.mplayer.app.ui

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs

/**
 * 播放畫面上的手勢（播放器的按鈕和進度條是子 View，會先拿到觸控，不受影響）：
 * - 點一下：顯示／隱藏控制列
 * - 連點兩下：左半邊倒退、右半邊快轉 10 秒；連點三下：1 分鐘
 * - 長按：按住期間 2 倍速，放開恢復
 *
 * 點擊要等一下看後面還有沒有下一下，才知道是單擊、雙擊還是三擊。
 */
class VideoGestures(
    private val view: View,
    private val onTap: () -> Unit,
    private val onMultiTap: (taps: Int, right: Boolean) -> Unit,
    private val onHold: (down: Boolean) -> Unit,
) : View.OnTouchListener {
    private val handler = Handler(Looper.getMainLooper())
    private val slop = ViewConfiguration.get(view.context).scaledTouchSlop
    private val tapWindow = 280L
    private val holdDelay = ViewConfiguration.getLongPressTimeout().toLong()

    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private var holding = false
    private var taps = 0
    private var lastTapRight = false

    private val startHold = Runnable {
        holding = true
        // 長按就不算點擊了
        handler.removeCallbacks(resolveTaps)
        taps = 0
        view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        onHold(true)
    }

    private val resolveTaps = Runnable {
        when {
            taps == 1 -> onTap()
            taps >= 2 -> onMultiTap(taps, lastTapRight)
        }
        taps = 0
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(v: View, e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                moved = false
                handler.postDelayed(startHold, holdDelay)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!moved && (abs(e.x - downX) > slop || abs(e.y - downY) > slop)) {
                    moved = true
                    if (!holding) handler.removeCallbacks(startHold)
                }
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(startHold)
                if (holding) {
                    holding = false
                    onHold(false)
                } else if (!moved) {
                    taps++
                    lastTapRight = e.x > v.width / 2f
                    handler.removeCallbacks(resolveTaps)
                    // 已經三下就不用再等了
                    if (taps >= 3) resolveTaps.run() else handler.postDelayed(resolveTaps, tapWindow)
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(startHold)
                if (holding) {
                    holding = false
                    onHold(false)
                }
            }
        }
        return true
    }
}
