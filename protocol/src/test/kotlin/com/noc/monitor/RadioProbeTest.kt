package com.noc.monitor

import com.noc.monitor.lab.RouterOsLabSimulator
import com.noc.monitor.protocol.DeviceCatalog
import com.noc.monitor.protocol.DeviceConnectionConfig
import com.noc.monitor.protocol.NocResult
import com.noc.monitor.protocol.ProbeKind
import com.noc.monitor.protocol.ProductFamily
import com.noc.monitor.protocol.Transport
import com.noc.monitor.protocol.Vendor
import com.noc.monitor.protocol.radio.AirOsStatusParser
import com.noc.monitor.protocol.radio.MikroTikRadioProbe
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AirOsStatusParserTest {
    @Test
    fun parsesWirelessAndHostWithoutInventingMissingFields() {
        val json = JSONObject(
            """
            {
              "host": {
                "hostname": "Dibektape",
                "fwversion": "6.49.2",
                "uptime": 704712,
                "cpuload": 7,
                "temperature": 40,
                "voltage": 23.2,
                "memtotal": 1000,
                "memfree": 630
              },
              "wireless": {
                "mode": "ap-bridge",
                "essid": "JOKER*DIBECK_A",
                "frequency": 5525,
                "ccq": 95,
                "count": 7,
                "signal": -62,
                "scan_list": "5000-6000"
              },
              "interfaces": [
                {"ifname":"eth0","hwaddr":"08:55:31:02:98:01","status":"1000Mbps-full","stats":{"rx_bytes":100,"tx_bytes":50}}
              ]
            }
            """.trimIndent(),
        )
        val snap = AirOsStatusParser.toSnapshot("192.168.192.10", json)
        assertTrue(snap.online)
        assertEquals("Dibektape", snap.identity)
        assertEquals(7, snap.cpuPercent)
        assertEquals(37, snap.ramPercent)
        assertEquals(95, snap.ccq)
        assertEquals("ap-bridge", snap.mode)
        assertEquals("6.49.2", snap.firmware)
        assertEquals(5525, snap.frequencyMhz)
        assertEquals("1Gbps", snap.ethernetSpeed)
        assertEquals(40.0, snap.temperatureC!!, 0.01)
        assertEquals(23.2, snap.voltage!!, 0.01)
        assertEquals(7, snap.clients)
        assertNull(snap.txErrorPercent)
    }
}

class MikroTikRadioProbeLabTest {
    @Test
    fun pollReadsIdentityCpuRamAndHealthFromSimulator() = runBlocking {
        RouterOsLabSimulator().use { sim ->
            val port = sim.start()
            val probe = MikroTikRadioProbe(
                DeviceConnectionConfig(
                    host = "127.0.0.1",
                    port = port,
                    username = "admin",
                    transport = Transport.API,
                    vendor = Vendor.MIKROTIK,
                    productFamily = ProductFamily.MIKROTIK_ROUTEROS,
                    timeoutMs = 4000,
                    kindId = "mikrotik-link",
                ),
                { "labpass".toCharArray() },
            )
            try {
                val result = probe.poll()
                assertTrue(result is NocResult.Ok)
                val snap = (result as NocResult.Ok).value
                assertTrue(snap.online)
                assertEquals("noc-lab-gw", snap.identity)
                assertEquals(7, snap.cpuPercent)
                assertEquals(41.0, snap.temperatureC!!, 0.01)
                assertEquals("1d2h15m", snap.uptime)
                assertTrue(snap.firmware!!.contains("7.15.3"))
                assertEquals(24.1, snap.voltage!!, 0.01)
                assertTrue((snap.ramPercent ?: 0) > 0)
            } finally {
                probe.close()
            }
        }
    }
}

class TelemetrySanitizerTest {
    @Test
    fun rejectsGarbagePowerAndZeroTemperature() {
        assertNull(com.noc.monitor.protocol.Telemetry.powerDbm(462828.0))
        assertNull(com.noc.monitor.protocol.Telemetry.temperatureC(0.0))
        assertEquals(38.2, com.noc.monitor.protocol.Telemetry.temperatureC(382.0)!!, 0.01)
        assertEquals(4.0, com.noc.monitor.protocol.Telemetry.powerDbm(4.0)!!, 0.01)
        assertEquals(23.0, com.noc.monitor.protocol.Telemetry.powerDbm(230.0)!!, 0.01)
        assertEquals(95, com.noc.monitor.protocol.Telemetry.ccq(9500.0))
        assertEquals(0.94081, com.noc.monitor.protocol.Telemetry.phyKbpsToMbps(94081.0)!!, 0.001)
        assertEquals(270.0, com.noc.monitor.protocol.Telemetry.phyKbpsToMbps(27_000_000.0)!!, 0.01)
    }
}

class CatalogProbeMappingTest {
    @Test
    fun modelsMapToTheRightProbe() {
        assertEquals(ProbeKind.ROUTEROS_API, DeviceCatalog.byId("mikrotik-sector").probe)
        assertTrue(DeviceCatalog.byId("mikrotik-sector").isSector)
        assertTrue(DeviceCatalog.byId("ubnt-airmax-sector").isSector)
        assertTrue(DeviceCatalog.byId("ubnt-ac-sector").isSector)
        assertTrue(DeviceCatalog.all.any { it.label.contains("سكتر MikroTik") })
        assertTrue(DeviceCatalog.all.any { it.label.contains("سكتر Ubiquiti") })
        assertEquals(ProbeKind.AIROS_HTTP, DeviceCatalog.byId("ubnt-airmax-link").probe)
        assertEquals(ProbeKind.SNMP, DeviceCatalog.byId("mimosa-c5c").probe)
        assertEquals(ProbeKind.SNMP, DeviceCatalog.byId("airfiber-x").probe)
        assertTrue(DeviceCatalog.all.all { it.defaultPort != 9 })
    }
}
