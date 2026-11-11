package com.doloh.marstekwidget.data

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerOutagePolicyTest {
    @Test
    fun serverScheduleWinsWhenEnabled() {
        val root = JSONObject().put("power_outage", JSONObject().put("available", true).put("source", "loe"))
        assertTrue(ServerOutagePolicy.serverHasSchedule(root))
    }

    @Test
    fun appFetchesOnlyWithoutAServerSchedule() {
        assertFalse(ServerOutagePolicy.serverHasSchedule(JSONObject()))
        assertFalse(ServerOutagePolicy.serverHasSchedule(JSONObject().put("power_outage", JSONObject().put("available", false))))
    }
}
