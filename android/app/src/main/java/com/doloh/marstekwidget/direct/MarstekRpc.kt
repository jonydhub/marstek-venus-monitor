package com.doloh.marstekwidget.direct

import org.json.JSONObject

/** One decoded JSON-RPC answer from the battery's Open API (UDP). */
sealed interface RpcResult {
    data class Ok(val data: JSONObject, val src: String?) : RpcResult
    data class Error(val message: String, val code: Int?) : RpcResult
    data object NoResponse : RpcResult

    fun toStatusJson(): JSONObject = when (this) {
        is Ok -> JSONObject().put("ok", true).put("data", data).put("src", src ?: JSONObject.NULL)
        is Error -> JSONObject().put("ok", false).put("error", message).put("code", code ?: JSONObject.NULL)
        NoResponse -> JSONObject().put("ok", false).put("error", "No response after retry")
    }
}

/**
 * Marstek Open API codec. READ-ONLY by construction: [encodeRequest] refuses every method that is not a
 * `*.Get*` query, so no Set/control command can ever leave the app.
 */
object MarstekRpc {
    const val DEFAULT_PORT = 30000
    const val DISCOVERY_METHOD = "Marstek.GetDevice"
    /** The device stores request ids as uint16. */
    const val MAX_ID = 65535
    private val READ_METHOD = Regex("^[A-Za-z]+\\.Get[A-Za-z]+$")

    fun isReadOnly(method: String): Boolean = READ_METHOD.matches(method)

    fun encodeRequest(id: Int, method: String): ByteArray {
        require(isReadOnly(method)) { "Only read-only Get methods are allowed: $method" }
        require(id in 0..MAX_ID) { "Request id must fit uint16: $id" }
        val params = if (method == DISCOVERY_METHOD) "{\"ble_mac\":\"0\"}" else "{\"id\":0}"
        return "{\"id\":$id,\"method\":\"$method\",\"params\":$params}".toByteArray(Charsets.UTF_8)
    }

    /** Returns null for packets that are not the answer to [expectedId] (stale retries, other clients, garbage). */
    fun decode(raw: ByteArray, expectedId: Int?): RpcResult? {
        if (raw.isEmpty()) return null
        val json = runCatching { JSONObject(String(raw, Charsets.UTF_8)) }.getOrNull() ?: return null
        if (expectedId != null && (!json.has("id") || json.optInt("id", Int.MIN_VALUE) != expectedId)) return null
        val src = json.optString("src").takeIf { it.isNotBlank() }
        json.optJSONObject("error")?.let { error ->
            return RpcResult.Error(
                error.optString("message").ifBlank { "Device error" },
                if (error.has("code")) error.optInt("code") else null,
            )
        }
        val result = json.optJSONObject("result") ?: return null
        return RpcResult.Ok(result, src)
    }
}

/** A battery that answered discovery. */
data class DeviceInfo(
    val model: String,
    val ip: String,
    val port: Int,
    val bleMac: String?,
    val firmware: Int?,
) {
    companion object {
        fun fromGetDevice(result: RpcResult.Ok, peerIp: String, port: Int): DeviceInfo {
            val data = result.data
            return DeviceInfo(
                model = data.optString("device").ifBlank { result.src?.substringBefore('-') ?: "Marstek" },
                ip = data.optString("ip").takeIf { it.isNotBlank() } ?: peerIp,
                port = port,
                bleMac = data.optString("ble_mac").takeIf { it.isNotBlank() },
                firmware = if (data.has("ver")) data.optInt("ver") else null,
            )
        }
    }
}
