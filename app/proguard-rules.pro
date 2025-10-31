# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Hilt
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }
-keep class * extends dagger.hilt.android.internal.lifecycle.HiltViewModelFactory { *; }
-keepclassmembers class * {
    @dagger.hilt.android.internal.lifecycle.* <methods>;
}

# Keep application class
-keep class com.peerchat.app.PeerChatApp { *; }

# Keep native methods for JNI
-keepclasseswithmembernames class * {
    native <methods>;
}

# Room database
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# Keep data classes used in Room entities
-keep class com.peerchat.data.db.** { *; }

# Compose (keep annotations and runtime classes)
-keep class androidx.compose.** { *; }
-keep class androidx.compose.runtime.** { *; }
-keep class androidx.compose.ui.** { *; }
-dontwarn androidx.compose.**

# Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# Kotlin serialization (if used)
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# JSON parsing
-keep class org.json.** { *; }
-keepclassmembers class * {
    @org.json.JSONObject <fields>;
}

# Keep Parcelable implementations
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# ML Kit OCR
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# PDF Box
-keep class org.apache.pdfbox.** { *; }
-dontwarn org.apache.pdfbox.**

# Markdown library
-keep class dev.jeziellago.compose.markdowntext.** { *; }
-dontwarn dev.jeziellago.compose.markdowntext.**

# WorkManager
-keep class androidx.work.** { *; }
-dontwarn androidx.work.**

# Security crypto
-keep class androidx.security.crypto.** { *; }
-dontwarn androidx.security.crypto.**

# Keep engine runtime classes
-keep class com.peerchat.engine.** { *; }
-dontwarn com.peerchat.engine.**

# Template and RAG modules (already in module proguard files, but keep here for safety)
-keep class com.peerchat.templates.** { *; }
-keep class com.peerchat.rag.** { *; }

# Keep ViewModels
-keep class * extends androidx.lifecycle.ViewModel { *; }
-keep class * extends com.peerchat.app.ui.BaseViewModel { *; }

# Keep data classes used in state management
-keep class com.peerchat.app.ui.**State { *; }
-keep class com.peerchat.app.ui.**Event { *; }

# Keep utility classes
-keep class com.peerchat.app.util.** { *; }

# Prevent optimization of enum values
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Keep annotation default values
-keepattributes AnnotationDefault

# Preserve line numbers for better crash reports
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Optimize but keep generic signatures
-keepattributes Signature
-keepattributes Exceptions

# Don't warn about missing classes
-dontwarn javax.annotation.**
-dontwarn kotlin.**
-dontwarn kotlin.reflect.**
-dontwarn kotlinx.**
-dontwarn org.jetbrains.annotations.**
-dontwarn org.intellij.lang.annotations.**

# Ignore missing optional dependencies
-dontwarn com.gemalto.jp2.**
-dontwarn org.bouncycastle.jsse.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# Keep classes that might be accessed via reflection
-keep class com.tom_roush.pdfbox.** { *; }
-dontwarn com.tom_roush.pdfbox.**

# (Removed okhttp/okio keeps; not used)
