# Preserve the JavaScript Interface so the Ghost Browser can talk to Kotlin
-keepclassmembers class com.yt.lite.api.potoken.PoTokenWebView {
    @android.webkit.JavascriptInterface <methods>;
}

# Preserve the PoToken models so they aren't scrambled
-keep class com.yt.lite.api.potoken.** { *; }

# Preserve QuickJS and the Cipher Engine so the math doesn't break
-keep class app.cash.quickjs.** { *; }
-keep class com.yt.lite.api.YouTubeCipher { *; }
