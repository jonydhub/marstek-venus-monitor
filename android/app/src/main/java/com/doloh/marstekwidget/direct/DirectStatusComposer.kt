package com.doloh.marstekwidget.direct

import org.json.JSONObject

/**
 * Builds the same JSON the server's `/api/status` returns, from answers gathered directly over UDP, so the
 * existing parser, widgets, summary and alert engine work unchanged in both modes.
 */
object DirectStatusComposer {
    val POLL_METHODS = listOf("Bat.GetStatus", "ES.GetStatus", "ES.GetMode", "EM.GetStatus")
    val FAST_METHODS = listOf("Bat.GetStatus", "ES.GetStatus")

    fun compose(
        results: Map<String, RpcResult>,
        flow: BatteryFlow,
        powerOutage: JSONObject?,
        insights: JSONObject?,
        collectedAt: String,
        model: String?,
        fallbackRatedWh: Double? = null,
    ): JSONObject {
        val methods = JSONObject()
        results.forEach { (method, result) ->
            val json = result.toStatusJson()
            if (method == "Bat.GetStatus" && result is RpcResult.Ok && fallbackRatedWh != null) {
                val data = JSONObject(result.data.toString())
                if (!data.has("rated_capacity") || data.isNull("rated_capacity")) data.put("rated_capacity", fallbackRatedWh)
                json.put("data", data)
            }
            methods.put(method, json)
        }
        return JSONObject()
            .put("device", JSONObject().put("model", model ?: JSONObject.NULL).put("transport", "udp"))
            .put("collected_at", collectedAt)
            .put("methods", methods)
            .put("battery_flow", flow.toJson())
            .putOpt("power_outage", powerOutage)
            .putOpt("insights", insights)
    }

    fun hasBattery(root: JSONObject): Boolean =
        root.optJSONObject("methods")?.optJSONObject("Bat.GetStatus")?.optBoolean("ok", false) == true
}
