# Template module ProGuard rules
# Keep all template classes and their public APIs

-keep class com.peerchat.templates.** { *; }
-dontwarn com.peerchat.templates.**

# Keep template catalog for reflection-based template resolution
-keepclassmembers class com.peerchat.templates.TemplateCatalog {
    *;
}

# Keep template implementations
-keep class com.peerchat.templates.TemplateImplementations { *; }
-keep class com.peerchat.templates.ChatTemplate { *; }
-keep class com.peerchat.templates.ChatPrompt { *; }
-keep class com.peerchat.templates.ChatMessage { *; }
-keep class com.peerchat.templates.ChatRole { *; }

# Preserve template metadata for autodetection
-keepattributes Signature
-keepattributes *Annotation*