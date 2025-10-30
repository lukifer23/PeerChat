-dontwarn org.apache.fontbox.**
-dontwarn org.apache.pdfbox.**

# Kotlin optimizations
-dontwarn kotlin.**
-keep class kotlin.** { *; }
-keep class kotlin.Metadata { *; }

# Keep data classes for serialization/deserialization
-keep class com.peerchat.data.db.** { *; }
-keep class com.peerchat.app.engine.** { *; }

# Compose optimizations
-dontwarn androidx.compose.**
-keep class androidx.compose.** { *; }

# Room optimizations
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# ML Kit optimizations
-dontwarn com.google.mlkit.**
-keep class com.google.mlkit.** { *; }

# Performance optimizations
-optimizationpasses 5
-allowaccessmodification
-dontpreverify
-repackageclasses ''
-verbose

