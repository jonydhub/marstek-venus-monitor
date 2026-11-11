package com.doloh.marstekwidget.domain

import org.json.JSONObject

/**
 * Venus E fw 148 sometimes reports small negative AC powers as unsigned int16 (e.g. -14 W -> 65522).
 * Values in 32768..65535 are folded back to signed; a real 32 kW reading is impossible on this device.
 * Mirrors server.py normalize_power_fields().
 */
object PowerFields {
    val KEYS = listOf("ongrid_power", "offgrid_power", "pv_power", "bat_power")

    fun normalize(value: Double): Double = if (value in 32768.0..65535.0) value - 65536.0 else value

    fun normalize(value: Double?): Double? = value?.let(::normalize)

    /** Reads [key] as a finite double with uint16 wrap folded; null when absent/NaN. */
    fun read(json: JSONObject?, key: String): Double? {
        if (json == null || !json.has(key) || json.isNull(key)) return null
        return json.optDouble(key, Double.NaN).takeIf(Double::isFinite)?.let(::normalize)
    }

    /** Returns a copy of an ES.GetStatus `data` object with all power fields folded. */
    fun normalize(data: JSONObject): JSONObject {
        val copy = JSONObject(data.toString())
        KEYS.forEach { key -> read(data, key)?.let { v -> if (v != data.optDouble(key)) copy.put(key, v) } }
        return copy
    }
}
