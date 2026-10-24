package com.example.glucoble

import org.junit.Assert.*
import org.junit.Test

class BridgeProtocolTest {
    @Test fun xdripIsAlwaysMgdlEvenForLowReadings() {
        assertEquals(9.3, BridgeProtocol.mmol(167.56926, true), 0.0001)
        assertEquals(30.0 / 18.0182, BridgeProtocol.mmol(30.0, true), 0.0001)
        assertEquals(9.3, BridgeProtocol.mmol(9.3, false), 0.0001)
    }
    @Test fun fixedScalePreservesDecimalAndLowGlucose() {
        assertEquals(84, BridgeProtocol.glucoseValue(8.4))
        assertEquals(93, BridgeProtocol.glucoseValue(9.3))
        assertEquals(84, BridgeProtocol.glucoseValue(8.4))
        assertEquals(17, BridgeProtocol.glucoseValue(1.7))
        assertEquals(0, BridgeProtocol.glucoseValue(0.0))
    }
    @Test fun scanResponseNameRespectsUtf8ByteLimit() {
        assertTrue(BridgeProtocol.canIncludeName("GlucoPower"))
        assertTrue(BridgeProtocol.canIncludeName("x".repeat(29)))
        assertFalse(BridgeProtocol.canIncludeName("x".repeat(30)))
        assertFalse(BridgeProtocol.canIncludeName("Я".repeat(15)))
        assertFalse(BridgeProtocol.canIncludeName(null))
    }
    @Test fun heartAndCadenceHaveSeparateProfiles() {
        assertEquals(0x1816, BridgeProtocol.serviceUuid(false))
        assertEquals(0x180d, BridgeProtocol.serviceUuid(true))
        assertArrayEquals(byteArrayOf(0, 55), BridgeProtocol.heartTestMeasurement())
    }
    @Test fun heartGlucoseUsesUnsignedEightOrSixteenBitMeasurements() {
        assertArrayEquals(byteArrayOf(0, 84), BridgeProtocol.heartMeasurement(BridgeProtocol.glucoseValue(8.4)))
        assertArrayEquals(byteArrayOf(0, -1), BridgeProtocol.heartMeasurement(255))
        assertArrayEquals(byteArrayOf(1, 0, 1), BridgeProtocol.heartMeasurement(256))
        assertArrayEquals(byteArrayOf(1, 44, 1), BridgeProtocol.heartMeasurement(300))
    }
    @Test fun savedModeRestoresAndUnknownModeDefaultsToCadence() {
        assertEquals(BridgeMode.HEART_GLUCOSE, BridgeMode.restore("HEART_GLUCOSE"))
        assertEquals(BridgeMode.CADENCE_TEST, BridgeMode.restore("CADENCE_TEST"))
        assertFalse(BridgeMode.CADENCE_TEST.heartProfile)
        assertTrue(BridgeMode.CADENCE_TEST.test)
        assertEquals(BridgeMode.CADENCE, BridgeMode.restore(null))
        assertEquals(BridgeMode.CADENCE, BridgeMode.restore("unknown"))
    }
    @Test fun rejectsInvalidAndOutOfOrderMeasurements() {
        assertFalse(BridgeProtocol.accepts(Double.NaN, 1000, 0, 1000))
        assertFalse(BridgeProtocol.accepts(Double.POSITIVE_INFINITY, 1000, 0, 1000))
        assertFalse(BridgeProtocol.accepts(0.0, 1000, 0, 1000))
        assertFalse(BridgeProtocol.accepts(8.4, 999, 1000, 2000))
        assertFalse(BridgeProtocol.accepts(8.4, 0, 0, 1000))
        assertFalse(BridgeProtocol.accepts(8.4, 62000, 0, 1000))
        assertTrue(BridgeProtocol.accepts(8.4, 1000, 1000, 2000))
        assertTrue(BridgeProtocol.accepts(8.4, 2000, 1000, 2000))
    }
}
