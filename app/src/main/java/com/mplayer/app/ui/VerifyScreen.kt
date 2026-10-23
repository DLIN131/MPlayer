package com.mplayer.app.ui

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.mplayer.app.net.WebFetcher

/** 自動驗證失敗時，讓使用者在看得到的 WebView 裡自己過 Cloudflare 驗證 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun VerifyScreen() {
    val context = LocalContext.current
    val web = remember {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = WebFetcher.userAgent
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webViewClient = WebViewClient()
            loadUrl(WebFetcher.BASE)
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            CookieManager.getInstance().flush()
            WebFetcher.reset()
            web.destroy()
        }
    }
    Column(Modifier.fillMaxSize()) {
        AppBar("完成驗證後按「完成」", back = true) {
            TextButton(onClick = Nav::pop) { Text("完成", color = Accent) }
        }
        AndroidView(factory = { web }, modifier = Modifier.fillMaxSize())
    }
}
