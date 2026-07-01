# Targeted keep rules for app module
# IPC client — must preserve method names
-keep class tv.kyshu.service.MockServiceHelper { *; }

# Launcher activity
-keep class tv.kyshu.MainActivity { *; }

# Baidu Map integration
-keep class tv.kyshu.bdmap.** { *; }

# UI fragments and adapters (referenced from XML layouts)
-keep class tv.kyshu.ui.** { *; }

# Application class
-keep class tv.kyshu.PortalApplication { *; }

# Native library loader
-keep class tv.kyshu.ext.** { *; }

# ViewModel classes
-keep class tv.kyshu.ui.viewmodel.** { *; }

# Keep Baidu map SDK classes (used reflectively)
-keep class com.baidu.** {*;}
-keep class vi.com.** {*;}
-keep class com.baidu.vi.** {*;}
-dontwarn com.baidu.**

# Keep custom view classes referenced from layouts
-keepclassmembers class com.dd.StrokeGradientDrawable {
    public void setStrokeColor(int);
}

# Keep serialization classes
-keep class kotlinx.serialization.** { *; }

# Keep JSON parsing
-keep class org.json.** { *; }
