# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in /sdk/tools/proguard/proguard-android.txt

# Keep NanoHTTPD
-keep class fi.iki.elonen.** { *; }

# Keep Kotlin
-keep class kotlin.** { *; }
-keep class org.jetbrains.** { *; }

# Keep JSON
-keep class org.json.** { *; }
