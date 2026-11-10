package com.doloh.marstekwidget.direct

data class Datagram(val payload: ByteArray, val peerHost: String)

interface UdpTransport : AutoCloseable {
    fun send(payload: ByteArray)
    /** Blocks up to [timeoutMs]; null on timeout. */
    fun receive(timeoutMs: Int): Datagram?
    override fun close()
}

/**
 * Request/response over one UDP source socket, as server.py does: the same request id is re-sent once on
 * timeout and only an answer with that id from the battery's address is accepted.
 */
class MarstekUdpClient(
    private val transport: UdpTransport,
    private val host: String,
    private val timeoutMs: Int = 2000,
    private val attempts: Int = 2,
    private val retryDelayMs: Long = 250,
    /** VenusE answers only one request at a time and drops anything sent < ~1 s after the previous exchange. */
    private val minGapMs: Long = 0,
    firstId: Int = ((System.nanoTime() / 1000L) % 50_000L).toInt().let { if (it < 0) -it else it } + 100,
) {
    private var seq = firstId
    private var lastActivity = 0L

    private fun pace() {
        if (minGapMs <= 0) return
        val wait = lastActivity + minGapMs - System.currentTimeMillis()
        if (wait > 0) Thread.sleep(wait)
    }

    fun query(method: String): RpcResult {
        // VenusE echoes the id as uint16 (515419 comes back as 56667): keep ids in 1..65535.
        seq = if (seq >= MarstekRpc.MAX_ID) 1 else seq + 1
        val id = seq
        val payload = MarstekRpc.encodeRequest(id, method)
        repeat(attempts) { attempt ->
            pace()
            transport.send(payload)
            lastActivity = System.currentTimeMillis()
            val deadline = System.currentTimeMillis() + timeoutMs
            while (true) {
                val remaining = (deadline - System.currentTimeMillis()).toInt()
                if (remaining <= 0) break
                val packet = transport.receive(remaining) ?: break
                if (packet.peerHost != host) continue
                val decoded = MarstekRpc.decode(packet.payload, id) ?: continue
                lastActivity = System.currentTimeMillis()
                return decoded
            }
            if (attempt < attempts - 1 && retryDelayMs > 0) Thread.sleep(retryDelayMs)
        }
        return RpcResult.NoResponse
    }
}
