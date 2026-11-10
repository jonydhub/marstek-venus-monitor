package com.doloh.marstekwidget.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.doloh.marstekwidget.direct.Datagram
import com.doloh.marstekwidget.direct.DeviceInfo
import com.doloh.marstekwidget.direct.MarstekRpc
import com.doloh.marstekwidget.direct.RpcResult
import com.doloh.marstekwidget.direct.ScanPlan
import com.doloh.marstekwidget.direct.SubnetScanPlanner
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.SocketTimeoutException

/** One UDP socket to one battery. */
class DatagramUdpTransport(private val host: String, private val port: Int) : com.doloh.marstekwidget.direct.UdpTransport {
    private val address: InetAddress = InetAddress.getByName(host)
    private val socket = DatagramSocket()
    private val buffer = ByteArray(8192)

    override fun send(payload: ByteArray) {
        socket.send(DatagramPacket(payload, payload.size, address, port))
    }

    override fun receive(timeoutMs: Int): Datagram? = try {
        socket.soTimeout = timeoutMs.coerceAtLeast(1)
        val packet = DatagramPacket(buffer, buffer.size)
        socket.receive(packet)
        // Normalise the peer (IPv4-mapped IPv6 / alternate spellings) so the client's peer check compares like with like.
        val peer = packet.address
        val peerText = if (peer == address || peer.address.toList().takeLast(4) == address.address.toList().takeLast(4)) host else peer.hostAddress ?: ""
        Datagram(packet.data.copyOf(packet.length), peerText)
    } catch (_: SocketTimeoutException) {
        null
    }

    override fun close() = socket.close()
}

data class LocalNetwork(val ip: String, val prefixLength: Int, val onWifi: Boolean)

object NetworkInfo {
    fun current(context: Context): LocalNetwork? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val network = cm.activeNetwork ?: return null
        val caps = cm.getNetworkCapabilities(network)
        val onWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ||
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
        val link = cm.getLinkProperties(network) ?: return null
        val v4 = link.linkAddresses.firstOrNull { it.address is Inet4Address } ?: return null
        return LocalNetwork(v4.address.hostAddress ?: return null, v4.prefixLength, onWifi)
    }

    fun onWifi(context: Context): Boolean = current(context)?.onWifi == true
}

/**
 * «Знайти батарею»: broadcast `Marstek.GetDevice` first, then a paced unicast sweep of the planned /24
 * on a single socket (≈4 ms between packets, so at most one burst of ~250 small datagrams), collecting answers.
 */
object BatteryScanner {
    fun scan(plan: ScanPlan, port: Int, listenAfterMs: Long = 2500, onFound: (DeviceInfo) -> Unit = {}): List<DeviceInfo> {
        val found = linkedMapOf<String, DeviceInfo>()
        DatagramSocket().use { socket ->
            socket.broadcast = true
            val id = 4242
            val payload = MarstekRpc.encodeRequest(id, MarstekRpc.DISCOVERY_METHOD)
            val receiver = Thread {
                val buffer = ByteArray(4096)
                socket.soTimeout = 300
                while (!Thread.currentThread().isInterrupted) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket.receive(packet)
                        val peer = packet.address.hostAddress ?: continue
                        val result = MarstekRpc.decode(packet.data.copyOf(packet.length), id) as? RpcResult.Ok ?: continue
                        val device = DeviceInfo.fromGetDevice(result, peer, port)
                        synchronized(found) {
                            if (!found.containsKey(device.ip)) { found[device.ip] = device; onFound(device) }
                        }
                    } catch (_: SocketTimeoutException) {
                    } catch (_: Exception) {
                        break
                    }
                }
            }
            receiver.start()
            try {
                plan.broadcasts.forEach { runCatching { socket.send(DatagramPacket(payload, payload.size, InetAddress.getByName(it), port)) } }
                Thread.sleep(700)
                // Two sweeps: a battery busy with another client (official app, HA, server) drops a request.
                repeat(2) { sweep ->
                    plan.hosts.forEach { host ->
                        if (synchronized(found) { found.containsKey(host) }) return@forEach
                        runCatching { socket.send(DatagramPacket(payload, payload.size, InetAddress.getByName(host), port)) }
                        Thread.sleep(4)
                    }
                    Thread.sleep(if (sweep == 0) 1500 else listenAfterMs)
                }
            } finally {
                receiver.interrupt()
                receiver.join(1000)
            }
        }
        return synchronized(found) { found.values.toList() }
    }

    fun planFor(context: Context, manualCidr: String?): ScanPlan? {
        manualCidr?.takeIf { it.isNotBlank() }?.let { return SubnetScanPlanner.parseCidr(it) }
        val net = NetworkInfo.current(context) ?: return null
        return SubnetScanPlanner.plan(net.ip, net.prefixLength)
    }
}
