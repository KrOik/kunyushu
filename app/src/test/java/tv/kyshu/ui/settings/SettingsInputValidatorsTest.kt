package tv.kyshu.ui.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsInputValidatorsTest {
    @Test
    fun optionalMacAddressAcceptsBlankAndColonSeparatedHex() {
        assertTrue(SettingsInputValidators.isOptionalMacAddress(""))
        assertTrue(SettingsInputValidators.isOptionalMacAddress("  "))
        assertTrue(SettingsInputValidators.isOptionalMacAddress("02:11:22:AA:bb:CC"))
    }

    @Test
    fun optionalMacAddressRejectsMalformedValues() {
        assertFalse(SettingsInputValidators.isOptionalMacAddress("02-11-22-AA-BB-CC"))
        assertFalse(SettingsInputValidators.isOptionalMacAddress("02:11:22:AA:BB"))
        assertFalse(SettingsInputValidators.isOptionalMacAddress("02:11:22:AA:BB:GG"))
    }

    @Test
    fun optionalIpv4AddressAcceptsBlankAndDottedQuads() {
        assertTrue(SettingsInputValidators.isOptionalIpv4Address(""))
        assertTrue(SettingsInputValidators.isOptionalIpv4Address("  "))
        assertTrue(SettingsInputValidators.isOptionalIpv4Address("192.168.1.1"))
        assertTrue(SettingsInputValidators.isOptionalIpv4Address("0.0.0.0"))
        assertTrue(SettingsInputValidators.isOptionalIpv4Address("255.255.255.255"))
    }

    @Test
    fun optionalIpv4AddressRejectsMalformedValues() {
        assertFalse(SettingsInputValidators.isOptionalIpv4Address("192.168.1"))
        assertFalse(SettingsInputValidators.isOptionalIpv4Address("192.168.1.256"))
        assertFalse(SettingsInputValidators.isOptionalIpv4Address("192.168.one.1"))
        assertFalse(SettingsInputValidators.isOptionalIpv4Address("192.168.0011.1"))
    }
}
