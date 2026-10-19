package com.example.glucoble

import org.junit.Assert.*
import org.junit.Test

class CadenceEncoderTest {
    private fun u16(b: ByteArray, offset: Int) = (b[offset].toInt() and 255) or
        ((b[offset + 1].toInt() and 255) shl 8)

    @Test fun ninetyThreeRpmUsesCrankEventsAndRealElapsedTime() {
        val encoder = CadenceEncoder()
        assertArrayEquals(byteArrayOf(2, 0, 0, 0, 0), encoder.sample(93, 0))
        val first = encoder.sample(93, 1000)
        assertEquals(1, u16(first, 1))
        assertEquals(660, u16(first, 3))
        val delayed = encoder.sample(93, 10000)
        assertEquals(15, u16(delayed, 1))
        val rpm = 60.0 * 1024 * (u16(delayed, 1) - u16(first, 1)) /
            (u16(delayed, 3) - u16(first, 3))
        assertEquals(93.0, rpm, 0.02)
    }

    @Test fun countersWrapAndStopDoesNotInventEvents() {
        val encoder = CadenceEncoder()
        encoder.sample(60, 0)
        val wrapped = encoder.sample(0, 65537000)
        assertEquals(1, u16(wrapped, 1))
        assertEquals(1024, u16(wrapped, 3))
        assertArrayEquals(wrapped, encoder.sample(0, 65539000))
        encoder.reset()
        assertArrayEquals(byteArrayOf(2, 0, 0, 0, 0), encoder.sample(93, 70000000))
    }

    @Test fun rateChangeUsesPreviousRateUntilChangeTime() {
        val encoder = CadenceEncoder()
        encoder.sample(60, 0)
        assertEquals(1, u16(encoder.sample(120, 1000), 1))
        assertEquals(3, u16(encoder.sample(120, 2000), 1))
        assertEquals(BridgeMode.CADENCE, BridgeMode.restore("POWER"))
        assertEquals(BridgeMode.CADENCE_TEST, BridgeMode.restore("POWER_TEST"))
    }
}
