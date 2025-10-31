# RAG module ProGuard rules
# Keep RAG service classes and their public APIs

-keep class com.peerchat.rag.** { *; }
-dontwarn com.peerchat.rag.**

# Keep RAG service object methods
-keepclassmembers class com.peerchat.rag.RagService {
    *;
}

-keep class com.peerchat.rag.Retriever { *; }

# Keep data structures used in RAG operations
-keep class * implements com.peerchat.data.db.RagChunk { *; }
-keep class * implements com.peerchat.data.db.Embedding { *; }

# Preserve method signatures for JNI embedding calls
-keepattributes Signature
-keepattributes Exceptions