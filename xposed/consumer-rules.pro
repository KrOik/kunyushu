# tv.kyshu Portal — Xposed module consumer proguard rules
# These rules are merged into the app module's R8 processing,
# preventing Xposed hook classes from being stripped as dead code.

# Xposed entry point (reflection-based loading)
-keep class tv.kyshu.xposed.FakeLocation { *; }

# Hook implementation classes (invoked reflectively by Xposed framework)
-keep class tv.kyshu.xposed.hooks.** { *; }

# Config and utility classes (referenced from app module via reflection/IPC)
-keep class tv.kyshu.xposed.config.** { *; }
-keep class tv.kyshu.xposed.utils.** { *; }

# Security module (used via reflection + JNI)
-keep class tv.kyshu.xposed.security.RemoteCommandSecurity { *; }

# Global config facade (accessed from all modules)
-keep class tv.kyshu.xposed.utils.FakeLoc { *; }

# Dobby JNI bridge
-keep class tv.kyshu.dobby.Dobby { *; }

# JNI native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# @Keep-annotated members
-keepclassmembers class * {
    @tv.kyshu.xposed.annotations.Keep *;
}

# Parcelable creators
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}