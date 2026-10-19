package com.example.glucoble

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class BondedSubscriptionsTest {
    private val power = GlucoBleBridgeService.SERVICE_CYCLING_CADENCE
    private val measurement = GlucoBleBridgeService.CHAR_CSC_MEASUREMENT
    private val address = "02:00:00:00:00:01"
    private val other = "02:00:00:00:00:02"
    private val preferences get() = RuntimeEnvironment.getApplication()
        .getSharedPreferences("test_subscriptions", Context.MODE_PRIVATE)

    @Before fun clear() { preferences.edit().clear().commit() }

    @Test fun storedSubscriptionSurvivesStoreRecreationButDoesNotApplyToOtherPeerOrProfile() {
        BondedSubscriptions(preferences).record(address, power, measurement, true)
        val restored = BondedSubscriptions(preferences)
        assertTrue(restored.enabled(address, power, measurement))
        assertFalse(restored.enabled(other, power, measurement))
        assertFalse(restored.enabled(address, GlucoBleBridgeService.SERVICE_HEART_RATE,
            GlucoBleBridgeService.CHAR_HEART_MEASUREMENT))
    }

    @Test fun pruneRemovesOnlyPeersWhoseBondNoLongerExists() {
        val store = BondedSubscriptions(preferences)
        store.record(address, power, measurement, true)
        store.record(other, power, measurement, true)
        store.prune(setOf(address))
        assertTrue(store.enabled(address, power, measurement))
        assertFalse(store.enabled(other, power, measurement))
    }

    @Test fun forgetClearsAllProfilesOfOnlyTheSpecifiedPeer() {
        val store = BondedSubscriptions(preferences)
        store.record(address, power, measurement, true)
        store.record(address, GlucoBleBridgeService.SERVICE_HEART_RATE,
            GlucoBleBridgeService.CHAR_HEART_MEASUREMENT, true)
        store.record(other, power, measurement, true)
        store.forget(address)
        assertFalse(store.enabled(address, power, measurement))
        assertFalse(store.enabled(address, GlucoBleBridgeService.SERVICE_HEART_RATE,
            GlucoBleBridgeService.CHAR_HEART_MEASUREMENT))
        assertTrue(store.enabled(other, power, measurement))
    }
}
