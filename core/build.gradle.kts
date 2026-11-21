plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 手機版和電視版共用：抓網頁、解析、收藏／紀錄、播放器設定、App 內更新
android {
    namespace = "com.mplayer.core"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    lint {
        disable += setOf("SetJavaScriptEnabled", "UnsafeOptInUsageError")
    }
}

dependencies {
    api(platform("androidx.compose:compose-bom:2025.06.00"))
    api("androidx.compose.runtime:runtime")
    api("androidx.compose.foundation:foundation")
    api("androidx.core:core-ktx:1.16.0")
    api("org.jsoup:jsoup:1.18.3")
    api("com.squareup.okhttp3:okhttp:4.12.0")
    api("io.coil-kt:coil:2.7.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    api("androidx.media3:media3-exoplayer:1.6.1")
    api("androidx.media3:media3-exoplayer-hls:1.6.1")
    api("androidx.media3:media3-datasource-okhttp:1.6.1")
    // 影片 CDN 偶爾會擋 OkHttp（403）；改用 Chromium 的網路層 Cronet（Google Play 服務提供，沒有時退回 OkHttp）
    api("androidx.media3:media3-datasource-cronet:1.6.1")
    api("com.google.android.gms:play-services-cronet:18.1.0")

    testImplementation("junit:junit:4.13.2")
}
