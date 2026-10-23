package com.mplayer.app.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.ActivityInfo
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.mplayer.app.net.Store
import com.mplayer.app.net.WebFetcher
import org.json.JSONObject

/**
 * 影片是 HLS，CDN 一樣有 Cloudflare，一般播放器抓不到；分段還要帶網站的 Referer 才給。
 * 所以用 WebView + hls.js 播放：直接開網站網域底下的假網址，再用 shouldInterceptRequest
 * 回傳播放器頁面（不會真的連到網站），這樣它就是正常的 https 頁面，請求會帶 Referer，
 * 跟在瀏覽器裡看一樣。loadDataWithBaseURL 不會帶 Referer，分段會被擋 403。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PlayerScreen(route: Route.Player) {
    val activity = LocalContext.current as Activity
    val code = route.video.code
    val web = remember {
        val html = playerHtml(route.playlist, Store.position(code))
        WebView(activity).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                userAgentString = WebFetcher.userAgent
            }
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    if (request.url.host != WebFetcher.host) return null
                    return when (request.url.path) {
                        "/__app/player" -> WebResourceResponse("text/html", "utf-8", html.byteInputStream())
                        "/__app/hls.js" -> WebResourceResponse("application/javascript", "utf-8", context.assets.open("hls.min.js"))
                        else -> null
                    }
                }
            }
            addJavascriptInterface(object {
                @JavascriptInterface
                fun pos(sec: Float, dur: Float) {
                    // 快看完就當作看完，下次從頭開始
                    Store.savePosition(code, if (dur > 0 && sec > dur - 30) 0f else sec)
                }
            }, "AppBridge")
            loadUrl(WebFetcher.BASE + "/__app/player")
        }
    }

    DisposableEffect(Unit) {
        val window = activity.window
        val ctrl = WindowCompat.getInsetsController(window, window.decorView)
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        ctrl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        ctrl.hide(WindowInsetsCompat.Type.systemBars())
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            web.evaluateJavascript("window.saveNow && saveNow()", null)
            web.postDelayed({ web.destroy() }, 300)
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            ctrl.show(WindowInsetsCompat.Type.systemBars())
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    AndroidView(factory = { web }, modifier = Modifier.fillMaxSize().background(Color.Black))
}

private fun playerHtml(src: String, start: Float): String = """
<!doctype html>
<html><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,user-scalable=no">
<meta name="referrer" content="origin">
<style>
  html,body{margin:0;height:100%;background:#000;overflow:hidden;font-family:sans-serif}
  video{width:100%;height:100%;object-fit:contain;background:#000}
  #bar{position:fixed;top:10px;right:12px;display:flex;gap:8px;transition:opacity .3s}
  #bar.hide{opacity:0;pointer-events:none}
  select,button{background:rgba(0,0,0,.6);color:#fff;border:1px solid rgba(255,255,255,.35);
    border-radius:6px;padding:6px 8px;font-size:14px}
  #tip{position:fixed;left:50%;top:45%;transform:translate(-50%,-50%);color:#fff;font-size:18px;
    background:rgba(0,0,0,.6);padding:8px 16px;border-radius:8px;display:none;pointer-events:none}
</style>
</head><body>
<video id="v" controls playsinline autoplay
  controlslist="nofullscreen nodownload noremoteplayback" disablepictureinpicture></video>
<div id="bar">
  <select id="rate">
    <option value="0.75">0.75x</option><option value="1" selected>1x</option>
    <option value="1.25">1.25x</option><option value="1.5">1.5x</option><option value="2">2x</option>
  </select>
  <select id="q"><option value="-1">自動</option></select>
</div>
<div id="tip"></div>
<script src="/__app/hls.js"></script>
<script>
var src = ${JSONObject.quote(src)};
var start = $start;
var v = document.getElementById('v');
var q = document.getElementById('q');
var bar = document.getElementById('bar');
var tip = document.getElementById('tip');
var tipTimer, barTimer;

function showTip(t, ms) {
  tip.textContent = t; tip.style.display = 'block';
  clearTimeout(tipTimer);
  if (ms) tipTimer = setTimeout(function () { tip.style.display = 'none'; }, ms);
}
function showBar() {
  bar.classList.remove('hide');
  clearTimeout(barTimer);
  barTimer = setTimeout(function () { if (!v.paused) bar.classList.add('hide'); }, 3000);
}
function saveNow() {
  if (v.currentTime > 0) AppBridge.pos(v.currentTime, v.duration || 0);
}
document.addEventListener('touchstart', showBar, {passive: true});
v.addEventListener('pause', function () { showBar(); saveNow(); });
v.addEventListener('ended', function () { AppBridge.pos(0, 0); });
setInterval(function () { if (!v.paused) saveNow(); }, 5000);
document.getElementById('rate').onchange = function () { v.playbackRate = parseFloat(this.value); };

// 雙擊左右兩側快退／快轉 10 秒
v.addEventListener('dblclick', function (e) {
  var d = e.clientX < window.innerWidth / 2 ? -10 : 10;
  v.currentTime = Math.max(0, v.currentTime + d);
  showTip(d > 0 ? '快轉 10 秒' : '倒退 10 秒', 700);
});

if (window.Hls && Hls.isSupported()) {
  var hls = new Hls({startPosition: start > 5 ? start : -1, maxBufferLength: 60});
  hls.loadSource(src);
  hls.attachMedia(v);
  hls.on(Hls.Events.MANIFEST_PARSED, function (ev, data) {
    data.levels
      .map(function (l, i) { return {h: l.height, i: i}; })
      .sort(function (a, b) { return b.h - a.h; })
      .forEach(function (l) {
        var o = document.createElement('option');
        o.value = l.i; o.textContent = l.h + 'p';
        q.appendChild(o);
      });
    v.play().catch(function () { showTip('點一下開始播放', 2000); });
  });
  q.onchange = function () { hls.currentLevel = parseInt(this.value); };
  var mediaRetry = 0;
  hls.on(Hls.Events.ERROR, function (ev, data) {
    if (!data.fatal) return;
    if (data.type === Hls.ErrorTypes.NETWORK_ERROR) {
      showTip('網路錯誤，重新連線中…', 2000);
      setTimeout(function () { hls.startLoad(); }, 1500);
    } else if (data.type === Hls.ErrorTypes.MEDIA_ERROR && mediaRetry++ < 3) {
      hls.recoverMediaError();
    } else {
      showTip('播放失敗：' + data.details);
    }
  });
} else {
  v.src = src;
  v.addEventListener('loadedmetadata', function () { if (start > 5) v.currentTime = start; });
  q.style.display = 'none';
}
showBar();
</script>
</body></html>
"""
