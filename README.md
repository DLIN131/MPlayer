# MPlayer

Android 手機用的影片播放用戶端。Jetpack Compose + Material 3 + Coil，播放器用 WebView + hls.js。

## 功能
- **首頁**：多種分類、可切換排序，捲到底自動載入下一頁
- **搜尋**：番號、演員、標題，保留搜尋紀錄
- **演員一覽**：點進去看該演員的所有影片
- **影片頁**：封面、詳細資料，演員／類型／發行商／標籤點了可以延伸瀏覽
- **播放器**：全螢幕橫向、切換畫質與倍速、雙擊左右快退／快轉 10 秒，自動記住播放進度
- **收藏、觀看紀錄**：存在手機本機，不需要登入
- **App 內更新**：開啟時檢查新版本，可選「立即更新」「稍後再說」或「略過這個版本」

## 下載
到 [Releases](../../releases/latest) 下載最新的 `MPlayer.apk` 安裝。

固定下載網址（永遠是最新版）：
`https://github.com/DLIN131/MPlayer/releases/latest/download/MPlayer.apk`

每次 push 到 `main`，GitHub Actions 會自動建置簽章 APK 並發佈 Release `v1.0.<run_number>`。

## 自己建置
```
./gradlew assembleRelease
```
簽章金鑰放在 `keystore/`（不進版控）；沒有金鑰時會改用 debug 簽章。
