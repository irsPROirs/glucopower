package com.example.glucoble

import android.content.SharedPreferences
import java.util.UUID

/** Only CCCD writes accepted by our server belong here, never system bond/LINK events. */
internal class BondedSubscriptions(private val preferences: SharedPreferences) {
    private fun key(address: String, service: UUID, characteristic: UUID) =
        "$address|$service|$characteristic"

    fun enabled(address: String, service: UUID, characteristic: UUID): Boolean =
        preferences.getBoolean(key(address, service, characteristic), false)

    fun record(address: String, service: UUID, characteristic: UUID, enabled: Boolean) {
        val entry = key(address, service, characteristic)
        preferences.edit().apply {
            if (enabled) putBoolean(entry, true) else remove(entry)
        }.apply()
    }

    fun forget(address: String) {
        val editor = preferences.edit()
        preferences.all.keys.filter { it.startsWith("$address|") }.forEach(editor::remove)
        editor.apply()
    }

    fun prune(bondedAddresses: Set<String>) {
        val editor = preferences.edit()
        preferences.all.keys.filter { it.substringBefore('|') !in bondedAddresses }
            .forEach(editor::remove)
        editor.apply()
    }
}
