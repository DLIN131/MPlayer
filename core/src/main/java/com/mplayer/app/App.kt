package com.mplayer.app

import android.app.Application
import android.content.pm.PackageManager
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.google.android.gms.net.CronetProviderInstaller

class App : Application(), ImageLoaderFactory {
    companion object {
        lateinit var instance: App
            private set

        /** 電視版（盒子效能和記憶體都比較吃緊） */
        val isTv: Boolean by lazy { instance.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // 先在背景準備好 Google Play 服務的 Cronet，第一次播放時就能用（見 VideoSource）
        runCatching { CronetProviderInstaller.installProvider(this) }
    }

    // 封面在另一個 CDN，沒有 Cloudflare，Coil 直接抓就行
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        // 電視上卡片一次很多張，淡入動畫和 ARGB 圖片都很吃效能／記憶體
        .crossfade(!isTv)
        .allowRgb565(isTv)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(if (isTv) 0.15 else 0.25).build() }
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve("images"))
                .maxSizeBytes(256L * 1024 * 1024)
                .build()
        }
        .build()
}
