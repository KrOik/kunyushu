package tv.kyshu.xposed.hooks.oplus

import android.location.LocationListener
import android.os.Bundle
import de.robv.android.xposed.XposedHelpers
import tv.kyshu.xposed.BaseLocationHook
import tv.kyshu.xposed.hooks.blindhook.BlindHookLocation
import tv.kyshu.xposed.hooks.blindhook.BlindHookLocation.invoke
import tv.kyshu.xposed.hooks.fused.ThirdPartyLocationHook
import tv.kyshu.xposed.utils.FakeLoc
import tv.kyshu.xposed.utils.Logger
import tv.kyshu.xposed.utils.hookMethodAfter
import tv.kyshu.xposed.utils.onceHookMethodBefore
import tv.kyshu.xposed.utils.toClass
import java.lang.reflect.Modifier

object OplusLocationHook: BaseLocationHook() {
    operator fun invoke(classLoader: ClassLoader) {
        ThirdPartyLocationHook(classLoader)
    }
}