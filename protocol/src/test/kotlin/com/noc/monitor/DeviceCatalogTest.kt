package com.noc.monitor

import com.noc.monitor.protocol.DeviceCatalog
import com.noc.monitor.protocol.PortPolicy
import com.noc.monitor.protocol.snmp.Ber
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.test.assertFailsWith

class DeviceCatalogTest {
    @Test
    fun catalogContainsRequestedVendors() {
        val labels = DeviceCatalog.all.map { it.label }
        assertTrue(labels.any { it.contains("MikroTik") })
        assertTrue(labels.any { it.contains("AirMax") })
        assertTrue(labels.any { it.contains("AirFiber") })
        assertTrue(labels.any { it.contains("Mimosa") })
    }
}

class SnmpBerTest {
    @Test
    fun getRequestRoundTripBindings() {
        val encoded = Ber.encodeMessage("public", 7, listOf("1.3.6.1.2.1.1.5.0"))
        assertTrue(encoded[0] == 0x30.toByte())
        assertTrue(encoded.size > 10)
    }
}

class PortPolicyStillRejectsNine {
    @Test
    fun portNineForbidden() {
        assertTrue(PortPolicy.isForbidden(9))
        assertFalse(PortPolicy.isForbidden(161))
        assertFailsWith<com.noc.monitor.protocol.NocException> { PortPolicy.requireAllowed(9) }
    }
}
