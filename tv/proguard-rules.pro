# 隱藏 WebView 的 JS bridge：方法名稱被混淆後 JS 就呼叫不到
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface

# Jsoup 選用的依賴，Android 上不存在
-dontwarn org.jspecify.annotations.**
-dontwarn com.google.re2j.**

# HTML 解析器：保險起見整個保留，避免改名後找不到內建資源
-keep class org.jsoup.** { *; }
