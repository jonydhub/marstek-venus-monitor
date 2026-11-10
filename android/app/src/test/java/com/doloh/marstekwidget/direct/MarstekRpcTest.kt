package com.doloh.marstekwidget.direct

import com.doloh.marstekwidget.testutil.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarstekRpcTest {
    @Test
    fun encodesCompactJsonRpcRequestWithDeviceIdParam() {
        assertEquals(
            """{"id":7,"method":"Bat.GetStatus","params":{"id":0}}""",
            String(MarstekRpc.encodeRequest(7, "Bat.GetStatus"), Charsets.UTF_8),
        )
    }

    @Test
    fun discoveryUsesBleMacZeroParam() {
        assertEquals(
            """{"id":1,"method":"Marstek.GetDevice","params":{"ble_mac":"0"}}""",
            String(MarstekRpc.encodeRequest(1, MarstekRpc.DISCOVERY_METHOD), Charsets.UTF_8),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesAnyNonReadMethod() {
        MarstekRpc.encodeRequest(1, "ES.SetMode")
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesMethodsThatOnlyContainGetAsSubstring() {
        MarstekRpc.encodeRequest(1, "ES.SetGetMode")
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesIdsTheDeviceWouldTruncate() {
        MarstekRpc.encodeRequest(515419, "Bat.GetStatus")
    }

    @Test
    fun decodesRealBatteryStatusResponse() {
        val response = MarstekRpc.decode(Fixtures.text("device_bat_status.json").toByteArray(), expectedId = 504)
        assertTrue(response is RpcResult.Ok)
        val ok = response as RpcResult.Ok
        assertEquals(99, ok.data.getInt("soc"))
        assertEquals(5114.0, ok.data.getDouble("bat_capacity"), 0.0)
        assertEquals(5120.0, ok.data.getDouble("rated_capacity"), 0.0)
        assertEquals("VenusE 3.0-aabbccddeeff", ok.src)
    }

    @Test
    fun ignoresResponsesForOtherRequestIds() {
        assertNull(MarstekRpc.decode(Fixtures.text("device_bat_status.json").toByteArray(), expectedId = 505))
    }

    @Test
    fun ignoresGarbage() {
        assertNull(MarstekRpc.decode("not json".toByteArray(), expectedId = 1))
        assertNull(MarstekRpc.decode(ByteArray(0), expectedId = 1))
    }

    @Test
    fun decodesDeviceErrors() {
        val raw = """{"id":3,"src":"VenusE","error":{"code":-32601,"message":"Method not found"}}"""
        val result = MarstekRpc.decode(raw.toByteArray(), expectedId = 3) as RpcResult.Error
        assertEquals("Method not found", result.message)
        assertEquals(-32601, result.code)
    }

    @Test
    fun resultToStatusJsonMatchesServerShape() {
        val ok = MarstekRpc.decode(Fixtures.text("device_es_status.json").toByteArray(), 505)!!
        val json = ok.toStatusJson()
        assertTrue(json.getBoolean("ok"))
        assertEquals(-333, json.getJSONObject("data").getInt("ongrid_power"))
        val timeout = RpcResult.NoResponse.toStatusJson()
        assertEquals(false, timeout.getBoolean("ok"))
    }

    @Test
    fun parsesDiscoveryAnswer() {
        val result = MarstekRpc.decode(Fixtures.text("device_get_device.json").toByteArray(), 501) as RpcResult.Ok
        val device = DeviceInfo.fromGetDevice(result, peerIp = "192.0.2.150", port = 30000)
        assertEquals("VenusE 3.0", device.model)
        assertEquals("192.0.2.150", device.ip)
        assertEquals("aabbccddeeff", device.bleMac)
        assertEquals(148, device.firmware)
        assertEquals(30000, device.port)
    }

    @Test
    fun discoveryPrefersPeerAddressWhenReportedIpMissing() {
        val raw = """{"id":9,"src":"VenusE 3.0-x","result":{"device":"VenusE 3.0"}}"""
        val result = MarstekRpc.decode(raw.toByteArray(), 9) as RpcResult.Ok
        assertEquals("192.0.2.7", DeviceInfo.fromGetDevice(result, "192.0.2.7", 30000).ip)
    }
}
