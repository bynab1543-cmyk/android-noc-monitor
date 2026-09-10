package com.noc.monitor

import com.noc.monitor.lab.RouterOsLabSimulator
import com.noc.monitor.protocol.DeviceConnectionConfig
import com.noc.monitor.protocol.NocError
import com.noc.monitor.protocol.NocResult
import com.noc.monitor.protocol.ProductFamily
import com.noc.monitor.protocol.Transport
import com.noc.monitor.protocol.Vendor
import com.noc.monitor.protocol.routeros.MikroTikProvider
import com.noc.monitor.protocol.routeros.RouterOsApiClient
import com.noc.monitor.protocol.routeros.RouterOsRestClient
import com.noc.monitor.protocol.traffic.InMemoryTrafficRepository
import com.noc.monitor.protocol.traffic.TrafficWindow
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.test.assertIs

class MikroTikFunctionalTest {
    @Test
    fun addDeviceTestConnectionReadSystemInterfacesTrafficPppoeAndSafeOperation() = runBlocking {
        RouterOsLabSimulator().use { sim ->
            val port = sim.start()
            val provider = MikroTikProvider(
                config = DeviceConnectionConfig(
                    host = "127.0.0.1",
                    port = port,
                    username = "admin",
                    transport = Transport.API,
                    vendor = Vendor.MIKROTIK,
                    productFamily = ProductFamily.MIKROTIK_ROUTEROS,
                    timeoutMs = 4000,
                    displayName = "lab-gw",
                ),
                passwordProvider = { "labpass".toCharArray() },
            )
            try {
                val test = provider.testConnection()
                assertIs<NocResult.Ok<*>>(test)
                val sys = (test as NocResult.Ok).value
                assertEquals("noc-lab-gw", sys.identity)
                assertEquals("RB5009UG+S+", sys.model)
                assertTrue(sys.version!!.contains("7.15.3"))
                assertEquals(7, sys.cpuLoadPercent)
                assertEquals(41.0, sys.temperatureC!!, 0.01)
                assertTrue(sys.memoryTotalBytes!! > 0)
                assertTrue(sys.storageTotalBytes!! > 0)

                val ifaces = (provider.readInterfaces() as NocResult.Ok).value
                assertTrue(ifaces.any { it.name == "ether1" && it.running && it.enabled })
                val disabled = ifaces.first { it.name == "sfp-sfpplus1" }
                assertFalse(disabled.enabled)

                val traffic1 = (provider.readTraffic() as NocResult.Ok).value
                Thread.sleep(50)
                val traffic2 = (provider.readTraffic() as NocResult.Ok).value
                val ether1a = traffic1.first { it.interfaceName == "ether1" }
                val ether1b = traffic2.first { it.interfaceName == "ether1" }
                assertTrue("counters must come from the device and increase", ether1b.rxBytes > ether1a.rxBytes)
                assertTrue(ether1b.txBytes > ether1a.txBytes)

                val store = InMemoryTrafficRepository()
                store.record("dev1", traffic1)
                Thread.sleep(20)
                val rates = store.record("dev1", traffic2)
                assertTrue(rates.first { it.interfaceName == "ether1" }.rxBps > 0)
                assertTrue(store.samples("dev1", TrafficWindow.FIVE_MINUTES).isNotEmpty())
                assertTrue(store.peak("dev1")!!.peakTotalBps > 0)

                val sessions = (provider.readPppoe() as NocResult.Ok).value
                assertEquals(2, sessions.size)
                assertTrue(sessions.any { it.name == "user-ahmed" })

                val ether2 = ifaces.first { it.name == "ether2" }
                val outcome = (provider.setInterfaceEnabled(ether2.id, false) as NocResult.Ok).value
                assertTrue(outcome.success)
                val after = (provider.readInterfaces() as NocResult.Ok).value.first { it.name == "ether2" }
                assertFalse(after.enabled)

                val ips = (provider.readIpAddresses() as NocResult.Ok).value
                assertTrue(ips.any { it.address.contains("192.168.88.1") })
            } finally {
                provider.close()
            }
        }
    }

    @Test
    fun disconnectPppoeRemovesSession() = runBlocking {
        RouterOsLabSimulator().use { sim ->
            val port = sim.start()
            val provider = provider(port)
            try {
                val sessions = (provider.readPppoe() as NocResult.Ok).value
                val id = sessions.first { it.name == "user-sara" }.id
                val result = (provider.disconnectPppoe(id) as NocResult.Ok).value
                assertTrue(result.success)
                val remaining = (provider.readPppoe() as NocResult.Ok).value
                assertTrue(remaining.none { it.name == "user-sara" })
            } finally {
                provider.close()
            }
        }
    }

    private fun provider(port: Int) = MikroTikProvider(
        DeviceConnectionConfig(
            host = "127.0.0.1",
            port = port,
            username = "admin",
            transport = Transport.API,
            vendor = Vendor.MIKROTIK,
            productFamily = ProductFamily.MIKROTIK_ROUTEROS,
            timeoutMs = 4000,
        ),
        { "labpass".toCharArray() },
    )
}

class ConnectionFailureTest {
    @Test
    fun authenticationFailure() = runBlocking {
        RouterOsLabSimulator().use { sim ->
            val port = sim.start()
            val provider = MikroTikProvider(
                DeviceConnectionConfig(
                    host = "127.0.0.1",
                    port = port,
                    username = "admin",
                    transport = Transport.API,
                    vendor = Vendor.MIKROTIK,
                    productFamily = ProductFamily.MIKROTIK_ROUTEROS,
                    timeoutMs = 3000,
                ),
                { "wrong-password".toCharArray() },
            )
            val result = provider.testConnection()
            assertIs<NocResult.Err>(result)
            assertIs<NocError.AuthenticationFailed>((result as NocResult.Err).error)
            provider.close()
        }
    }

    @Test
    fun connectionRefused() = runBlocking {
        val port = freePort()
        val provider = MikroTikProvider(
            DeviceConnectionConfig(
                host = "127.0.0.1",
                port = port,
                username = "admin",
                transport = Transport.API,
                vendor = Vendor.MIKROTIK,
                productFamily = ProductFamily.MIKROTIK_ROUTEROS,
                timeoutMs = 1000,
            ),
            { "labpass".toCharArray() },
        )
        val result = provider.testConnection()
        assertIs<NocResult.Err>(result)
        val err = (result as NocResult.Err).error
        assertTrue(err is NocError.ConnectionRefused || err is NocError.Timeout || err is NocError.Unreachable)
        provider.close()
    }

    @Test
    fun timeout() = runBlocking {
        val blocker = ServerSocket()
        blocker.bind(InetSocketAddress("127.0.0.1", 0))
        try {
            val provider = MikroTikProvider(
                DeviceConnectionConfig(
                    host = "127.0.0.1",
                    port = blocker.localPort,
                    username = "admin",
                    transport = Transport.API,
                    vendor = Vendor.MIKROTIK,
                    productFamily = ProductFamily.MIKROTIK_ROUTEROS,
                    timeoutMs = 400,
                ),
                { "labpass".toCharArray() },
            )
            val result = provider.testConnection()
            assertIs<NocResult.Err>(result)
            provider.close()
        } finally {
            blocker.close()
        }
    }

    @Test
    fun restAuthenticationFailure() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"message":"unauthorized"}"""))
            val client = RouterOsRestClient(
                host = server.hostName,
                port = server.port,
                username = "admin",
                password = "bad".toCharArray(),
                useHttps = false,
                allowInsecureTls = true,
                timeoutMs = 2000,
            )
            try {
                val thrown = runCatching { client.getObject("/system/identity") }.exceptionOrNull()
                assertTrue(thrown is com.noc.monitor.protocol.NocException)
                assertIs<NocError.AuthenticationFailed>((thrown as com.noc.monitor.protocol.NocException).error)
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun restReadsSystemResource() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody("""{"name":"core-gw"}""").addHeader("Content-Type", "application/json"),
            )
            val client = RouterOsRestClient(
                host = server.hostName,
                port = server.port,
                username = "admin",
                password = "secret".toCharArray(),
                useHttps = false,
                timeoutMs = 2000,
            )
            try {
                val obj = client.getObject("/system/identity")
                assertEquals("core-gw", obj.getString("name"))
            } finally {
                client.close()
            }
        }
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }
}

class TrafficRepositoryTest {
    @Test
    fun computesRatesFromDeviceCountersAndTracksPeak() {
        val repo = InMemoryTrafficRepository()
        val t0 = System.currentTimeMillis()
        repo.record(
            "r1",
            listOf(
                com.noc.monitor.protocol.TrafficCounters("ether1", 1000, 2000, 10, 20, t0),
            ),
        )
        val rates = repo.record(
            "r1",
            listOf(
                com.noc.monitor.protocol.TrafficCounters("ether1", 1000 + 125000, 2000 + 62500, 90, 60, t0 + 1000),
            ),
        )
        assertEquals(1, rates.size)
        assertTrue(rates[0].rxBps > 0)
        assertEquals(rates[0].rxBps + rates[0].txBps, repo.peak("r1")!!.peakTotalBps)
        assertTrue(repo.samples("r1", TrafficWindow.FIVE_MINUTES).isNotEmpty())
    }
}

class DirectApiLoginTest {
    @Test
    fun loginAndPrintIdentity() {
        RouterOsLabSimulator().use { sim ->
            val port = sim.start()
            RouterOsApiClient("127.0.0.1", port, useTls = false, allowInsecureTls = false, timeoutMs = 3000).use { client ->
                client.connect()
                client.login("admin", "labpass".toCharArray())
                val rows = client.print("/system/identity/print")
                assertEquals("noc-lab-gw", rows.first()["name"])
            }
        }
    }

    @Test
    fun socketConnectsToSimulator() {
        RouterOsLabSimulator().use { sim ->
            val port = sim.start()
            Socket().use { s ->
                s.connect(InetSocketAddress("127.0.0.1", port), 2000)
                assertTrue(s.isConnected)
            }
        }
    }
}
