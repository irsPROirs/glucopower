package com.example.glucoble

/**
 * CSC crank model: fractional rotation, full revolutions and last completed event,
 * as used by you-gatt-me. Only crank data is published (flag 0x02).
 * A session-relative monotonic clock avoids wall-clock jumps and a bogus first RPM
 * calculated against device uptime. Both transmitted fields wrap at 16 bits.
 */
class CadenceEncoder {
    private var originMs: Long? = null
    private var previousMs: Long? = null
    private var previousRpm = 0
    private var fraction = 0.0
    private var revolutions = 0L
    private var eventTicks = 0L

    fun reset() {
        originMs = null
        previousMs = null
        previousRpm = 0
        fraction = 0.0
        revolutions = 0
        eventTicks = 0
    }

    fun sample(rpm: Int, nowMs: Long): ByteArray {
        require(rpm >= 0)
        if (originMs == null) originMs = nowMs
        val before = previousMs
        if (before != null) {
            require(nowMs >= before)
            if (previousRpm > 0) {
                val total = fraction + (nowMs - before) * previousRpm / 60000.0
                val whole = total.toLong()
                fraction = total - whole
                if (whole > 0) {
                    revolutions = (revolutions + whole) and 0xffff
                    val lastEventMs = nowMs - fraction * 60000.0 / previousRpm
                    eventTicks = ((lastEventMs - originMs!!) * 1024.0 / 1000.0).toLong() and 0xffff
                }
            }
        }
        previousMs = nowMs
        previousRpm = rpm
        return byteArrayOf(2, revolutions.toByte(), (revolutions shr 8).toByte(),
            eventTicks.toByte(), (eventTicks shr 8).toByte())
    }
}
