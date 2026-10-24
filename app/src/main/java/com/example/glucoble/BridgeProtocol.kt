package com.example.glucoble

import kotlin.math.roundToInt

enum class BridgeMode { CADENCE, CADENCE_TEST, HEART_GLUCOSE, HEART_TEST;
    val heartProfile: Boolean get() = this == HEART_GLUCOSE || this == HEART_TEST
    val profileLabel: String get() = if (heartProfile) "ЧСС" else "Каденс"
    val testValue: Int get() = if (heartProfile) 55 else 65
    val test: Boolean get() = this == CADENCE_TEST || this == HEART_TEST
    companion object {
        fun restore(value: String?): BridgeMode = entries.firstOrNull { it.name == value } ?: CADENCE
    }
}

object BridgeProtocol {
    fun mmol(raw: Double, mgdl: Boolean): Double = if (mgdl) raw / 18.0182 else raw
    fun glucoseValue(mmol: Double): Int = (mmol * 10.0).roundToInt().coerceIn(0, 32767)
    // Scan response: name header 2 bytes; legacy payload limit 31 bytes.
    fun canIncludeName(name: String?): Boolean =
        !name.isNullOrEmpty() && name.toByteArray(Charsets.UTF_8).size <= 29
    fun serviceUuid(heartProfile: Boolean): Int = if (heartProfile) 0x180d else 0x1816
    fun serviceUuid(mode: BridgeMode): Int = serviceUuid(mode.heartProfile)
    fun heartMeasurement(value: Int): ByteArray {
        require(value in 0..65535)
        return if (value <= 255) byteArrayOf(0, value.toByte())
            else byteArrayOf(1, value.toByte(), (value ushr 8).toByte())
    }
    fun heartTestMeasurement(): ByteArray = heartMeasurement(BridgeMode.HEART_TEST.testValue)
    fun accepts(value: Double, timestamp: Long, previous: Long, now: Long): Boolean =
        value.isFinite() && value > 0 && timestamp > 0 && timestamp >= previous && timestamp <= now + 60000
}

/** One outstanding Android GATT notification; queued samples are coalesced per client. */
class BleNotificationQueue<T : Any> {
    private val waiting = linkedMapOf<T, ByteArray>()
    var pending: T? = null
        private set
    fun offer(client: T, bytes: ByteArray) { waiting[client] = bytes.copyOf() }
    fun next(): Pair<T, ByteArray>? {
        if (pending != null || waiting.isEmpty()) return null
        val first = waiting.entries.first()
        val item = first.key to first.value
        waiting.remove(first.key)
        pending = item.first
        return item
    }
    fun complete(client: T): Boolean {
        if (pending != client) return false
        pending = null
        return true
    }
    fun remove(client: T, disconnected: Boolean = false) {
        waiting.remove(client)
        if (disconnected && pending == client) pending = null
    }
    fun clear() { waiting.clear(); pending = null }
}

