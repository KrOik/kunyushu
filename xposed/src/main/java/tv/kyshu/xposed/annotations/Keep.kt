package tv.kyshu.xposed.annotations

/**
 * Marker annotation for classes, methods, or fields that must
 * survive ProGuard/R8 obfuscation. Used in conjunction with the
 * proguard-rules.pro keep rule:
 *
 *   -keepclassmembers class * {
 *       @tv.kyshu.xposed.annotations.Keep *;
 *   }
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.FIELD)
@Retention(AnnotationRetention.BINARY)
annotation class Keep
