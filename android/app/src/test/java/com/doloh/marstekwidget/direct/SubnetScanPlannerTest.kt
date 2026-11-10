package com.doloh.marstekwidget.direct

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubnetScanPlannerTest {
    @Test
    fun slash24ListsAllHostsExceptNetworkBroadcastAndSelf() {
        val plan = SubnetScanPlanner.plan("10.20.30.40", 24)!!
        assertEquals(253, plan.hosts.size)
        assertFalse("10.20.30.0" in plan.hosts)
        assertFalse("10.20.30.255" in plan.hosts)
        assertFalse("10.20.30.40" in plan.hosts)
        assertTrue("10.20.30.1" in plan.hosts)
        assertTrue("10.20.30.254" in plan.hosts)
        assertEquals(listOf("255.255.255.255", "10.20.30.255"), plan.broadcasts)
    }

    @Test
    fun widerNetworksAreCappedToTheOwnSlash24() {
        val plan = SubnetScanPlanner.plan("172.16.5.9", 16)!!
        assertEquals(253, plan.hosts.size)
        assertTrue(plan.hosts.all { it.startsWith("172.16.5.") })
    }

    @Test
    fun smallerNetworksStayInsidePrefix() {
        val plan = SubnetScanPlanner.plan("10.0.2.130", 26)!!
        assertEquals(61, plan.hosts.size) // .129-.190 minus self
        assertEquals("10.0.2.129", plan.hosts.first())
        assertEquals("10.0.2.190", plan.hosts.last())
        assertTrue("10.0.2.191" in plan.broadcasts)
    }

    @Test
    fun parsesCidrText() {
        val plan = SubnetScanPlanner.parseCidr("10.9.8.0/24")!!
        assertEquals(254, plan.hosts.size)
        assertEquals(null, SubnetScanPlanner.parseCidr("nonsense"))
        assertEquals(null, SubnetScanPlanner.parseCidr("300.1.1.1/24"))
    }

    @Test
    fun rejectsPublicAndInvalidAddresses() {
        assertEquals(null, SubnetScanPlanner.plan("8.8.8.8", 24))
        assertEquals(null, SubnetScanPlanner.plan("not-an-ip", 24))
        assertEquals(null, SubnetScanPlanner.plan("10.0.0.1", 31))
    }

    @Test
    fun privateRanges() {
        assertTrue(SubnetScanPlanner.isPrivateIpv4("10.1.2.3"))
        assertTrue(SubnetScanPlanner.isPrivateIpv4("172.31.0.1"))
        assertFalse(SubnetScanPlanner.isPrivateIpv4("172.32.0.1"))
        assertTrue(SubnetScanPlanner.isPrivateIpv4("192.168.0.1"))
        assertFalse(SubnetScanPlanner.isPrivateIpv4("1.1.1.1"))
    }

    @Test
    fun validatesManualHostAndPort() {
        assertTrue(SubnetScanPlanner.isValidIpv4("192.0.2.10"))
        assertFalse(SubnetScanPlanner.isValidIpv4("192.0.2"))
        assertFalse(SubnetScanPlanner.isValidIpv4("192.0.2.256"))
        assertTrue(SubnetScanPlanner.isValidPort(30000))
        assertFalse(SubnetScanPlanner.isValidPort(0))
        assertFalse(SubnetScanPlanner.isValidPort(70000))
    }
}
