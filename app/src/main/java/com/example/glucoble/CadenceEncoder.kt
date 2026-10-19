package com.example.glucoble

/** Virtual crank driven by monotonic time; CSC carries events, not an RPM value. */
class CadenceEncoder {
    private var previousMs: Long? = null
    private var previousRpm = 0
    private var fraction = 0.0
    private var revolutions = 0L
    private var eventTicks = 0L

    fun reset() {
        previousMs = null
        previousRpm = 0
        fraction = 0.0
        revolutions = 0
        eventTicks = 0
    }

    fun sample(rpm: Int, nowMs: Long): ByteArray {
        require(rpm >= 0)
        val before = previousMs
        if (before != null) {
            require(nowMs >= before)
            if (previousRpm > 0) {
                val total = fraction + (nowMs - before) * previousRpm / 60000.0
                val whole = total.toLong()
                fraction = total - whole
                if (whole > 0) {
                    revolutions += whole
                    val lastEventMs = nowMs - fraction * 60000.0 / previousRpm
                    eventTicks = (lastEventMs * 1024.0 / 1000.0).toLong()
                }
            }
        }
        previousMs = nowMs
        previousRpm = rpm
        return byteArrayOf(2, revolutions.toByte(), (revolutions shr 8).toByte(),
            eventTicks.toByte(), (eventTicks shr 8).toByte())
    }
}
