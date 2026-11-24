package tv.kyshu.xposed.hooks.nmea

import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Structural checks based on the Android 15 PHY110 framework, without Binder calls. */
internal object NmeaCallbackContract {
    fun callbackMethod(type: Class<*>): Method? = type.methods.firstOrNull {
        it.name == "onNmeaReceived" && !Modifier.isStatic(it.modifiers) && it.returnType == Void.TYPE &&
            it.parameterTypes.contentEquals(arrayOf(Long::class.javaPrimitiveType, String::class.java))
    }

    fun supportsRegistration(method: Method): Boolean =
        method.name == "registerGnssNmeaCallback" && !Modifier.isStatic(method.modifiers) && method.returnType == Void.TYPE &&
            method.parameterTypes.size in 2..4 &&
            callbackMethod(method.parameterTypes[0]) != null &&
            method.parameterTypes.drop(1).all { it == String::class.java }

    enum class Action { PASSTHROUGH, SUPPRESS, REWRITE }

    fun action(enabled: Boolean, internalConsumer: Boolean, nmeaEnabled: Boolean, fused: Boolean): Action = when {
        !enabled -> Action.PASSTHROUGH
        // Oplus concatenates GSV itself, so even single-sentence injection is unsafe here.
        internalConsumer -> Action.SUPPRESS
        fused -> Action.PASSTHROUGH
        !nmeaEnabled -> Action.SUPPRESS
        else -> Action.REWRITE
    }
}
