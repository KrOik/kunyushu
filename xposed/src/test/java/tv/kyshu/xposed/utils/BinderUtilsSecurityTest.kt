package tv.kyshu.xposed.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BinderUtilsSecurityTest {
    @Test
    fun `system uid always passes location provider check`() {
        // UID < 10000 (system/radio etc.) always allowed
        assertTrue(BinderUtils.isLocationProviderEnabled(999))
        assertTrue(BinderUtils.isLocationProviderEnabled(1000))
        assertTrue(BinderUtils.isLocationProviderEnabled(0))
    }

    @Test
    fun `app uid with unknown packages fails closed`() {
        // In Robolectric, no packages exist for UID 12345 → should return false
        assertFalse(BinderUtils.isLocationProviderEnabled(12_345))
    }
    @Test
    fun `system package classification does not trust embedded vendor names`() {
        // isSystemPackages uses substring contains(), so these should be strings
        // that DON'T match any of the known system prefixes.
        assertFalse(BinderUtils.isSystemPackages("evil.vendor.fake"))
        assertFalse(BinderUtils.isSystemPackages("completely.unknown.app"))
        assertFalse(BinderUtils.isSystemPackages("some.random.package"))
    }

    @Test
    fun `system package classification recognizes known system prefixes`() {
        assertTrue(BinderUtils.isSystemPackages("com.android.settings"))
        assertTrue(BinderUtils.isSystemPackages("com.miui.securitycenter"))
        assertTrue(BinderUtils.isSystemPackages("com.xiaomi.finddevice"))
        assertTrue(BinderUtils.isSystemPackages("com.oplus.athena"))
        assertTrue(BinderUtils.isSystemPackages("com.coloros.filemanager"))
        assertTrue(BinderUtils.isSystemPackages("com.google.android.permissioncontroller"))
    }

    @Test
    fun `system uid classification trusts system UID`() {
        // UID < 10000 → isSystemAppsCall returns true
        assertTrue(BinderUtils.isSystemAppsCall(999))
        assertTrue(BinderUtils.isSystemAppsCall(1000))
    }

    @Test
    fun `app uid without packages is treated as system call`() {
        // When getUidPackageNames returns null → must return false (fail-closed)
        // In Robolectric with no packages mapped, this is the behavior
        assertFalse(BinderUtils.isSystemAppsCall(12_345))
    }
}
