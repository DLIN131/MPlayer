# MPlayer

Android 影片播放用戶端，有手機版和電視版。Jetpack Compose + Coil，播放器用 Media3 ExoPlayer。

## 功能
- **首頁**：多種分類、可切換排序，捲到底自動載入下一頁
- **搜尋**：番號、演員、標題，保留搜尋紀錄
- **演員一覽**：點進去看該演員的所有影片，可以篩選單人／多人作品、有碼／無碼
- **影片頁**：封面、詳細資料，演員／類型／發行商／標籤點了可以延伸瀏覽
- **播放器**：依手機方向播放、預設最高畫質（最高 1080P）、切換畫質與倍速；長按 2 倍速、雙擊左右快退／快轉 10 秒、三擊 1 分鐘；自動記住播放進度
- **長按封面預覽**：列表上長按封面播放預覽短片
- **收藏、觀看紀錄**：存在手機本機，不需要登入
- **App 內更新**：開啟時檢查新版本，可選「立即更新」「稍後再說」或「略過這個版本」

## 電視版
遙控器操作的 Android TV 版本，針對電視盒子最佳化（R8 縮減、低記憶體緩衝、小尺寸封面）。
- 側邊選單：首頁、搜尋、女優、收藏、觀看紀錄
- 播放器：左右鍵倒退／快轉 10 秒（長按加速），OK 暫停／播放，長按 OK 2 倍速，上鍵或選單鍵換畫質

## 下載
到 [Releases](../../releases/latest) 下載最新版安裝：手機用 `MPlayer.apk`，電視用 `MPlayerTV.apk`。

固定下載網址（永遠是最新版）：
- 手機版：`https://github.com/DLIN131/MPlayer/releases/latest/download/MPlayer.apk`
- 電視版：`https://github.com/DLIN131/MPlayer/releases/latest/download/MPlayerTV.apk`

每次 push 到 `main`，GitHub Actions 會自動建置簽章 APK 並發佈 Release `v1.0.<run_number>`。

## 自己建置
```
./gradlew :app:assembleRelease :tv:assembleRelease
```
模組：`core`（共用的抓取、解析、收藏紀錄、更新）、`app`（手機版）、`tv`（電視版）。
簽章金鑰放在 `keystore/`（不進版控）；沒有金鑰時會改用 debug 簽章。
