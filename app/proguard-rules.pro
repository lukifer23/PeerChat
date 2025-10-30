-dontwarn org.apache.fontbox.**
-dontwarn org.apache.pdfbox.**

# Kotlin optimizations
-dontwarn kotlin.**
-keep class kotlin.** { *; }
-keep class kotlin.Metadata { *; }

# Keep data classes for serialization/deserialization
-keep class com.peerchat.data.db.** { *; }
-keep class com.peerchat.app.engine.** { *; }

# JNI / Engine keep rules
-keep class com.peerchat.engine.EngineNative { *; }
-keep class com.peerchat.engine.TokenCallback { *; }

# Compose optimizations
-dontwarn androidx.compose.**
-keep class androidx.compose.** { *; }

# Room optimizations
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
-dontwarn com.gemalto.jp2.**

# ML Kit optimizations
-dontwarn com.google.mlkit.**
-keep class com.google.mlkit.** { *; }

# Performance optimizations
-optimizationpasses 5
-allowaccessmodification
-dontpreverify
-repackageclasses ''
-verbose
