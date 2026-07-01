# Aggressive obfuscation options (VoxLoction-style)
-repackageclasses 'tv.kyshu.xposed'
-allowaccessmodification
-overloadaggressively
-useuniqueclassmembernames
-renamesourcefileattribute SourceFile
-keepattributes Signature,Exceptions,*Annotations*,InnerClasses

# Custom obfuscation dictionary
-obfuscationdictionary dictionary.txt
-classobfuscationdictionary dictionary.txt
-packageobfuscationdictionary dictionary.txt

# Targeted keep rules — replace blanket tv.kyshu.** keep
# Xposed entry point (must survive renaming)
-keep class tv.kyshu.xposed.FakeLocation { *; }

# JNI bridge (native methods must not be renamed)
-keep class tv.kyshu.dobby.Dobby { *; }

# Hook classes used reflectively via XposedHelpers.findClass
# Hook 实现类 —— 仅保留入口和关键 API，允许内部实现混淆
-keep class tv.kyshu.xposed.hooks.LocationServiceHook { *; }
-keep class tv.kyshu.xposed.hooks.LocationManagerHook { *; }
-keep class tv.kyshu.xposed.hooks.BasicLocationHook { *; }
-keep class tv.kyshu.xposed.hooks.fused.AndroidFusedLocationProviderHook { *; }
-keep class tv.kyshu.xposed.hooks.fused.ThirdPartyLocationHook { *; }
-keep class tv.kyshu.xposed.hooks.oplus.OplusLocationHook { *; }
-keep class tv.kyshu.xposed.hooks.gnss.GnssHook { *; }
-keep class tv.kyshu.xposed.hooks.nmea.LocationNMEAHook { *; }
-keep class tv.kyshu.xposed.hooks.sensor.SystemSensorManagerHook { *; }
-keep class tv.kyshu.xposed.hooks.wlan.WlanHook { *; }
-keep class tv.kyshu.xposed.hooks.telephony.TelephonyHook { *; }
-keep class tv.kyshu.xposed.hooks.provider.LocationProviderManagerHook { *; }
-keep class tv.kyshu.xposed.hooks.blindhook.BlindHookLocation { *; }

# Config store (accessed from all modules)
-keep class tv.kyshu.xposed.utils.FakeLoc { *; }

# Security module (used via reflection + JNI)
-keep class tv.kyshu.xposed.security.RemoteCommandSecurity { *; }

# ClassNameStore constants (string keys for reflection)
-keep class tv.kyshu.xposed.utils.ClassNameStore { *; }

# @Keep-annotated members
-keepclassmembers class * {
    @tv.kyshu.xposed.annotations.Keep *;
}

# Universal keep: Xposed framework entry points
-keep class * extends de.robv.android.xposed.IXposedHookLoadPackage { *; }
-keep class * extends de.robv.android.xposed.IXposedHookZygoteInit { *; }
-keep class * extends de.robv.android.xposed.IXposedHookCmdInit { *; }

# Universal keep: native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep Parcelable creators
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}
