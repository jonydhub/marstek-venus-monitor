package com.doloh.marstekwidget.direct

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarstekUdpClientTest {
    private class FakeTransport(private val responder: (JSONObject, Int) -> List<Datagram>) : UdpTransport {
        val sent = mutableListOf<JSONObject>()
        private val inbox = ArrayDeque<Datagram>()
        override fun send(payload: ByteArray) {
            val request = JSONObject(String(payload))
            sent += request
            inbox.addAll(responder(request, sent.size))
        }
        override fun receive(timeoutMs: Int): Datagram? = inbox.removeFirstOrNull()
        override fun close() = Unit
    }

    private fun answer(id: Int, peer: String = "192.0.2.150") =
        Datagram("""{"id":$id,"src":"VenusE","result":{"soc":50}}""".toByteArray(), peer)

    @Test
    fun matchesResponseByRequestIdSkippingStaleAndForeignPackets() {
        val transport = FakeTransport { request, _ ->
            val id = request.getInt("id")
            listOf(answer(id - 1), answer(id, peer = "192.0.2.99"), answer(id))
        }
        val client = MarstekUdpClient(transport, host = "192.0.2.150", timeoutMs = 100, attempts = 2, retryDelayMs = 0)
        val result = client.query("Bat.GetStatus")
        assertTrue(result is RpcResult.Ok)
        assertEquals(1, transport.sent.size)
    }

    @Test
    fun retriesOnceThenReportsNoResponse() {
        val transport = FakeTransport { _, _ -> emptyList() }
        val client = MarstekUdpClient(transport, host = "192.0.2.150", timeoutMs = 10, attempts = 2, retryDelayMs = 0)
        assertEquals(RpcResult.NoResponse, client.query("ES.GetStatus"))
        assertEquals(2, transport.sent.size)
        assertEquals(transport.sent[0].getInt("id"), transport.sent[1].getInt("id"))
    }

    @Test
    fun secondAttemptCanSucceed() {
        val transport = FakeTransport { request, count -> if (count == 1) emptyList() else listOf(answer(request.getInt("id"))) }
        val client = MarstekUdpClient(transport, host = "192.0.2.150", timeoutMs = 10, attempts = 2, retryDelayMs = 0)
        assertTrue(client.query("ES.GetMode") is RpcResult.Ok)
    }

    @Test
    fun requestIdsIncrease() {
        val transport = FakeTransport { request, _ -> listOf(answer(request.getInt("id"))) }
        val client = MarstekUdpClient(transport, host = "192.0.2.150", timeoutMs = 10, attempts = 1, retryDelayMs = 0)
        client.query("Bat.GetStatus"); client.query("ES.GetStatus")
        assertTrue(transport.sent[1].getInt("id") > transport.sent[0].getInt("id"))
    }

    @Test
    fun pacesRequestsAtLeastMinGapApart() {
        val times = mutableListOf<Long>()
        val transport = object : UdpTransport {
            var pending: Datagram? = null
            override fun send(payload: ByteArray) {
                times += System.currentTimeMillis()
                pending = answer(JSONObject(String(payload)).getInt("id"))
            }
            override fun receive(timeoutMs: Int) = pending.also { pending = null }
            override fun close() = Unit
        }
        val client = MarstekUdpClient(transport, host = "192.0.2.150", timeoutMs = 50, attempts = 1, retryDelayMs = 0, minGapMs = 120)
        client.query("Bat.GetStatus"); client.query("ES.GetStatus")
        assertTrue(times[1] - times[0] >= 115)
    }

    @Test
    fun requestIdsStayInsideUint16AndWrap() {
        val transport = FakeTransport { request, _ -> listOf(answer(request.getInt("id"))) }
        val client = MarstekUdpClient(transport, host = "192.0.2.150", timeoutMs = 10, attempts = 1, retryDelayMs = 0, firstId = 65534)
        repeat(3) { assertTrue(client.query("Bat.GetStatus") is RpcResult.Ok) }
        assertEquals(listOf(65535, 1, 2), transport.sent.map { it.getInt("id") })
    }
}
