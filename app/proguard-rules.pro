# ---------------------------------------------------------
# THE ENGINE SHIELD (BotGuard & QuickJS)
# ---------------------------------------------------------

# 1. Protect the Javascript Interfaces (Ghost Browser)
-keepattributes JavascriptInterface
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# 2. Protect the API, Cipher, and PoToken packages from renaming
-keep class com.yt.lite.api.** { *; }

# 3. Protect QuickJS Math Engine
-keep class app.cash.quickjs.** { *; }
-dontwarn app.cash.quickjs.**

# 4. Protect Kotlinx Serialization (Crucial for JSON parsing)
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keep,includedescriptorclasses class com.yt.lite.api.**$$serializer { *; }
-keepclassmembers class com.yt.lite.api.** {
    *** Companion;
}
-keepclasseswithmembers class com.yt.lite.api.** {
    kotlinx.serialization.KSerializer serializer(...);
}
