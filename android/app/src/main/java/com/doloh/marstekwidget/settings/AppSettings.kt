package com.doloh.marstekwidget.settings

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.doloh.marstekwidget.direct.MarstekRpc
import com.doloh.marstekwidget.outage.OutageConfig
import com.doloh.marstekwidget.outage.OutageRegions
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class ConnectionMode { DIRECT, SERVER }
enum class ServerAuth { NONE, BEARER, CF_ACCESS }

/** Non-secret configuration (SharedPreferences). The server token lives in [SecretStore]. */
data class AppSettings(
    val configured: Boolean = false,
    val mode: ConnectionMode = ConnectionMode.DIRECT,
    val deviceHost: String? = null,
    val devicePort: Int = MarstekRpc.DEFAULT_PORT,
    val deviceModel: String? = null,
    val serverUrl: String? = null,
    val serverAuth: ServerAuth = ServerAuth.NONE,
    val outageRegionId: String? = null,
    val outageQueue: String? = null,
    val backupThresholdW: Double = DEFAULT_BACKUP_THRESHOLD_W,
    val averageLoadW: Double? = null,
    val ratedCapacityWh: Double = DEFAULT_RATED_WH,
    val detectedRatedWh: Double? = null,
    /** BMS discharge floor (% SOC); the reserve forecast counts only energy above it. No Get method exposes it. */
    val dischargeFloorPct: Double = DEFAULT_DISCHARGE_FLOOR_PCT,
) {
    val outageConfig: OutageConfig?
        get() = if (outageRegionId != null && !outageQueue.isNullOrBlank()) OutageConfig(outageRegionId, outageQueue) else null

    companion object {
        const val DEFAULT_BACKUP_THRESHOLD_W = 5.0
        const val DEFAULT_RATED_WH = 5120.0
        const val DEFAULT_DISCHARGE_FLOOR_PCT = 11.0
    }
}

class SettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val secrets = SecretStore(context)

    fun load(): AppSettings = AppSettings(
        configured = prefs.getBoolean("configured", false),
        mode = runCatching { ConnectionMode.valueOf(prefs.getString("mode", null)!!) }.getOrDefault(ConnectionMode.DIRECT),
        deviceHost = prefs.getString("device.host", null),
        devicePort = prefs.getInt("device.port", MarstekRpc.DEFAULT_PORT),
        deviceModel = prefs.getString("device.model", null),
        serverUrl = prefs.getString("server.url", null),
        serverAuth = runCatching { ServerAuth.valueOf(prefs.getString("server.auth", null)!!) }.getOrDefault(ServerAuth.NONE),
        // legacy slugs (e.g. lvivska-oblast) map to the canonical id; unknown values are kept as-is
        outageRegionId = prefs.getString("outage.region", null)?.let { OutageRegions.resolve(it)?.id ?: it },
        outageQueue = prefs.getString("outage.queue", null),
        backupThresholdW = prefs.getFloat("backup.threshold_w", AppSettings.DEFAULT_BACKUP_THRESHOLD_W.toFloat()).toDouble(),
        averageLoadW = prefs.getFloat("load.average_w", -1f).toDouble().takeIf { it > 0 },
        ratedCapacityWh = prefs.getFloat("battery.rated_wh", AppSettings.DEFAULT_RATED_WH.toFloat()).toDouble(),
        detectedRatedWh = prefs.getFloat("battery.detected_rated_wh", -1f).toDouble().takeIf { it > 0 },
        dischargeFloorPct = prefs.getFloat("battery.floor_pct", AppSettings.DEFAULT_DISCHARGE_FLOOR_PCT.toFloat()).toDouble(),
    )

    fun save(s: AppSettings) {
        prefs.edit()
            .putBoolean("configured", s.configured)
            .putString("mode", s.mode.name)
            .putString("device.host", s.deviceHost)
            .putInt("device.port", s.devicePort)
            .putString("device.model", s.deviceModel)
            .putString("server.url", s.serverUrl)
            .putString("server.auth", s.serverAuth.name)
            .putString("outage.region", s.outageRegionId)
            .putString("outage.queue", s.outageQueue)
            .putFloat("backup.threshold_w", s.backupThresholdW.toFloat())
            .putFloat("load.average_w", (s.averageLoadW ?: -1.0).toFloat())
            .putFloat("battery.rated_wh", s.ratedCapacityWh.toFloat())
            .putFloat("battery.detected_rated_wh", (s.detectedRatedWh ?: -1.0).toFloat())
            .putFloat("battery.floor_pct", s.dischargeFloorPct.toFloat())
            .apply()
    }

    fun rememberDetectedRated(wh: Double) {
        if (wh > 0 && wh.isFinite()) prefs.edit().putFloat("battery.detected_rated_wh", wh.toFloat()).apply()
    }

    fun token(): String? = secrets.read()
    fun setToken(value: String?) = if (value.isNullOrBlank()) secrets.clear() else secrets.write(value.trim())

    private companion object { const val PREFS = "marstek_settings" }
}

/** Server token encrypted with an Android Keystore AES-GCM key (never stored in plain text, never logged). */
class SecretStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("marstek_secrets", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build())
        return generator.generateKey()
    }

    fun write(value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val data = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(KEY, Base64.encodeToString(data, Base64.NO_WRAP)).apply()
    }

    fun read(): String? = runCatching {
        val data = Base64.decode(prefs.getString(KEY, null) ?: return null, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 0, 12))
        String(cipher.doFinal(data, 12, data.size - 12), Charsets.UTF_8)
    }.getOrNull()

    fun clear() { prefs.edit().remove(KEY).apply() }

    private companion object {
        const val ALIAS = "marstek_server_token"
        const val KEY = "server_token"
    }
}
