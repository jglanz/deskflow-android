# Project-specific ProGuard rules.
#
# Minification is currently disabled (isMinifyEnabled = false), so these rules
# are not yet applied. They are kept here so the proguardFiles reference in
# app/build.gradle.kts resolves and the file is ready for when minification is
# enabled. See https://developer.android.com/build/shrink-code for details.

# --- Hilt (Dagger) generated code -------------------------------------------
# Keep Hilt/Dagger generated classes and annotations used by the compiler so
# dependency injection continues to work after shrinking.
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.lifecycle.HiltViewModel { *; }
-keep,allowobfuscation @dagger.hilt.android.lifecycle.HiltViewModel class *
-keep,allowobfuscation @dagger.hilt.* class *
-keep,allowobfuscation @javax.inject.* class *
-keep,allowobfuscation @dagger.* class *

# --- Protocol Buffers -------------------------------------------------------
# Generated message classes rely heavily on reflection, so keep them and the
# runtime intact.
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.protobuf.**

# --- kotlin.logging / SLF4J -------------------------------------------------
-keep class mu.** { *; }
-keep class org.slf4j.** { *; }
-dontwarn org.slf4j.impl.**
