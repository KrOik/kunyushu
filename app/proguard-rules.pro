# Keep all classes in tv.kyshu package
-keep class tv.kyshu.** { *; }

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
