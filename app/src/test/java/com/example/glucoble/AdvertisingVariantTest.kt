package com.example.glucoble

import android.os.ParcelUuid
import android.bluetooth.le.AdvertiseSettings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class AdvertisingVariantTest {
    @Test fun layoutsIsolateUuidNameAndServiceData() {
        val uuid = GlucoBleBridgeService.SERVICE_CYCLING_POWER
        for (variant in AdvertisingVariant.entries.filter { it != AdvertisingVariant.EXISTING }) {
            val (adv, scan) = variant.packets(uuid, "GlucoPower")
            assertEquals(variant != AdvertisingVariant.UUID_SCAN, adv.serviceUuids?.contains(ParcelUuid(uuid)) == true)
            assertEquals(variant == AdvertisingVariant.UUID_SCAN, scan?.serviceUuids?.contains(ParcelUuid(uuid)) == true)
            assertEquals(variant == AdvertisingVariant.NAME_PRIMARY, adv.includeDeviceName)
            assertEquals(variant !in listOf(AdvertisingVariant.UUID_ONLY, AdvertisingVariant.NAME_PRIMARY), scan?.includeDeviceName == true)
            assertEquals(variant == AdvertisingVariant.SERVICE_DATA, adv.serviceData.containsKey(ParcelUuid(uuid)))
            assertFalse(adv.includeTxPowerLevel)
            assertTrue(variant.settings().isConnectable)
            assertEquals(0, variant.settings().timeout)
            assertEquals(if (variant == AdvertisingVariant.SLOW) AdvertiseSettings.ADVERTISE_MODE_BALANCED else AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY, variant.settings().mode)
        }
    }
    @Test fun oversizedPrimaryNameIsRejectedInsteadOfChangingExperiment() {
        assertThrows(IllegalArgumentException::class.java) { AdvertisingVariant.NAME_PRIMARY.packets(GlucoBleBridgeService.SERVICE_CYCLING_POWER, "Я".repeat(12)) }
        AdvertisingVariant.UUID_ONLY.packets(GlucoBleBridgeService.SERVICE_CYCLING_POWER, "Я".repeat(50))
        assertEquals(AdvertisingVariant.EXISTING, AdvertisingVariant.restore("unknown"))
    }
}
