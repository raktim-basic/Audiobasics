# ---------------------------------------------------------
# THE ENGINE & HILT SHIELD
# ---------------------------------------------------------

# 1. Protect the Javascript Interfaces (Cipher / PoToken WebViews)
-keepattributes JavascriptInterface
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# 2. Protect the API, Cipher, and PoToken packages
-keep class com.rkd.audiobasics.api.** { *; }

# 3. Protect Kotlinx Serialization (BotGuard JSON)
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keep,includedescriptorclasses class com.rkd.audiobasics.api.**$$serializer { *; }
-keepclassmembers class com.rkd.audiobasics.api.** {
    *** Companion;
}
-keepclasseswithmembers class com.rkd.audiobasics.api.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# 4. Protect Hilt & Dagger
-keep class dagger.** { *; }
-dontwarn dagger.**
-keep class hilt_aggregated_deps.** { *; }

# 5. NewPipeExtractor ships Rhino for its own internal signature/cipher
#    deobfuscation path. Required by NewPipeExtractor's own docs even
#    though Audiobasics also has its own WebView-based cipher solver.
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-dontwarn org.mozilla.javascript.tools.**

# Rhino's optional javax.script (JSR-223) bridge references JDK classes
# that don't exist on Android (java.beans.*, javax.script.*, jdk.dynalink.*).
# This bridge is never invoked on Android — safe to ignore at build time.
-dontwarn java.beans.**
-dontwarn javax.script.**
-dontwarn jdk.dynalink.**

# 6. OkHttp references optional platform providers not present on Android
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
