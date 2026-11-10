package com.doloh.marstekwidget.direct

data class ScanPlan(val hosts: List<String>, val broadcasts: List<String>)

/** Which addresses «Знайти батарею» probes: the phone's own /24 at most (bounded, predictable). */
object SubnetScanPlanner {
    const val MAX_PREFIX_WIDTH = 24

    fun plan(ownIp: String, prefixLength: Int): ScanPlan? {
        val own = toInt(ownIp) ?: return null
        if (!isPrivateIpv4(ownIp) || prefixLength !in 8..30) return null
        return build(own, maxOf(prefixLength, MAX_PREFIX_WIDTH), exclude = own)
    }

    /** Manual «192.168.0.0/24»-style input (for VPN/emulator setups); also capped to /24. */
    fun parseCidr(text: String): ScanPlan? {
        val parts = text.trim().split('/')
        if (parts.size != 2) return null
        val ip = toInt(parts[0]) ?: return null
        val prefix = parts[1].toIntOrNull() ?: return null
        if (prefix !in 8..30 || !isPrivateIpv4(parts[0])) return null
        return build(ip, maxOf(prefix, MAX_PREFIX_WIDTH), exclude = null)
    }

    private fun build(ip: Int, prefix: Int, exclude: Int?): ScanPlan {
        val mask = if (prefix == 0) 0 else (-1 shl (32 - prefix))
        val network = ip and mask
        val broadcast = network or mask.inv()
        val hosts = ((network + 1) until broadcast).filter { it != exclude }.map(::toText)
        return ScanPlan(hosts, listOf("255.255.255.255", toText(broadcast)))
    }

    fun isValidIpv4(text: String): Boolean = toInt(text) != null

    fun isValidPort(port: Int): Boolean = port in 1..65535

    fun isPrivateIpv4(text: String): Boolean {
        val p = octets(text) ?: return false
        return p[0] == 10 || (p[0] == 172 && p[1] in 16..31) || (p[0] == 192 && p[1] == 168)
    }

    private fun octets(text: String): List<Int>? {
        val parts = text.trim().split('.')
        if (parts.size != 4) return null
        val values = parts.map { part -> if (part.isEmpty() || part.length > 3 || !part.all(Char::isDigit)) return null else part.toInt() }
        return values.takeIf { v -> v.all { it in 0..255 } }
    }

    private fun toInt(text: String): Int? = octets(text)?.fold(0) { acc, o -> (acc shl 8) or o }

    private fun toText(value: Int) = "${(value ushr 24) and 255}.${(value ushr 16) and 255}.${(value ushr 8) and 255}.${value and 255}"
}
