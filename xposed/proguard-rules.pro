# Keep all classes in tv.kyshu package (used reflectively by Xposed framework)
-keep class tv.kyshu.** { *; }

# Keep Dobby native JNI methods
-keep class tv.kyshu.dobby.Dobby { *; }

# Keep all native method declarations
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep Xposed entry points
-keep class * implements de.robv.android.xposed.IXposedHookLoadPackage { *; }
-keep class * implements de.robv.android.xposed.IXposedHookZygoteInit { *; }
-keep class * implements de.robv.android.xposed.IXposedHookCmdInit { *; }
