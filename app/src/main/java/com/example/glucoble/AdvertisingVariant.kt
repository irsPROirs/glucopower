package com.example.glucoble

import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.os.ParcelUuid
import java.util.UUID

/** Diagnostic layouts; Flags are supplied by Android, not injected as fake service data. */
enum class AdvertisingVariant(val label: String) {
    EXISTING("0 · Прежние настройки (контроль)"),
    UUID_ONLY("A · Только UUID, без имени и ответа"),
    NAME_SCAN("B · UUID в рекламе, имя в ответе"),
    NAME_PRIMARY("C · UUID и имя в рекламе"),
    UUID_SCAN("D · UUID и имя только в ответе"),
    SERVICE_DATA("E · Как B + Service Data"),
    SLOW("F · Как B, интервал около 250 мс");

    companion object {
        fun restore(value: String?): AdvertisingVariant = entries.firstOrNull { it.name == value } ?: EXISTING
    }

    fun packets(uuid: UUID, name: String?): Pair<AdvertiseData, AdvertiseData?> {
        require(this != EXISTING)
        val adv = AdvertiseData.Builder()
        val scan = AdvertiseData.Builder()
        val parcel = ParcelUuid(uuid)
        if (this != UUID_SCAN) adv.addServiceUuid(parcel)
        if (this == UUID_SCAN) scan.addServiceUuid(parcel)
        if (this == NAME_PRIMARY) {
            require(BridgeProtocol.canIncludeName(name, primary = true)) { "Для C задайте имя Bluetooth до 22 байт UTF-8" }
            adv.setIncludeDeviceName(true)
        } else if (this != UUID_ONLY) {
            require(BridgeProtocol.canIncludeName(name, uuidInScanResponse = this == UUID_SCAN)) { "Имя Bluetooth слишком длинное для выбранного ответа" }
            scan.setIncludeDeviceName(true)
        }
        // Experimental UUID presence in AD 0x16, NOT a CPS measurement or Appearance.
        if (this == SERVICE_DATA) adv.addServiceData(parcel, byteArrayOf())
        return adv.build() to if (this == UUID_ONLY || this == NAME_PRIMARY) null else scan.build()
    }

    fun settings(): AdvertiseSettings = AdvertiseSettings.Builder()
        .setConnectable(true).setTimeout(0)
        .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
        .setAdvertiseMode(if (this == SLOW) AdvertiseSettings.ADVERTISE_MODE_BALANCED else AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
        .build()
}
