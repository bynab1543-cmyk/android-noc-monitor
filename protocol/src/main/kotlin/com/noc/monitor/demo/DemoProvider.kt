package com.noc.monitor.demo

import com.noc.monitor.protocol.ArpEntry
import com.noc.monitor.protocol.CommandOutcome
import com.noc.monitor.protocol.DeviceCapabilities
import com.noc.monitor.protocol.DeviceProvider
import com.noc.monitor.protocol.DhcpLease
import com.noc.monitor.protocol.IpAddressEntry
import com.noc.monitor.protocol.LogEntry
import com.noc.monitor.protocol.NetInterface
import com.noc.monitor.protocol.NocError
import com.noc.monitor.protocol.NocResult
import com.noc.monitor.protocol.PppoeSession
import com.noc.monitor.protocol.ProductFamily
import com.noc.monitor.protocol.RouteEntry
import com.noc.monitor.protocol.SystemInfo
import com.noc.monitor.protocol.TrafficCounters
import java.util.concurrent.atomic.AtomicLong

/**
 * In-app DEMO data source. Must never be mixed with REAL device polls.
 * Every record is tagged so the UI can label it DEMO.
 */
class DemoMikroTikProvider : DeviceProvider {
    override val capabilities: DeviceCapabilities = DeviceCapabilities.mikroTik()
    private val rx = AtomicLong(5_000_000)
    private val tx = AtomicLong(2_000_000)

    override suspend fun testConnection() = NocResult.ok(system())
    override suspend fun readSystem() = NocResult.ok(system())

    override suspend fun readInterfaces(): NocResult<List<NetInterface>> {
        val r = rx.addAndGet(80_000)
        val t = tx.addAndGet(40_000)
        return NocResult.ok(
            listOf(
                iface("*1", "ether1", true, true, r, t),
                iface("*2", "ether2", true, true, r / 2, t / 2),
                iface("*3", "pppoe-out1", true, true, r / 3, t / 4),
                iface("*4", "wlan1", false, true, 0, 0),
            ),
        )
    }

    override suspend fun readTraffic(): NocResult<List<TrafficCounters>> {
        return readInterfaces().map { list ->
            list.map { TrafficCounters(it.name, it.rxBytes, it.txBytes, it.rxPackets, it.txPackets) }
        }
    }

    override suspend fun readPppoe() = NocResult.ok(
        listOf(
            PppoeSession("*A", "demo-user-1", "pppoe-in", "10.10.0.10", "00:11:22:33:44:55", "1h"),
            PppoeSession("*B", "demo-user-2", "pppoe-in", "10.10.0.11", "00:11:22:33:44:56", "12m"),
        ),
    )

    override suspend fun disconnectPppoe(sessionId: String) =
        NocResult.ok(CommandOutcome(true, "DEMO /ppp/active/remove", "DEMO: session $sessionId disconnected locally"))

    override suspend fun setInterfaceEnabled(interfaceId: String, enabled: Boolean) =
        NocResult.ok(CommandOutcome(true, "DEMO /interface/set", "DEMO: interface $interfaceId set enabled=$enabled"))

    override suspend fun reboot(): NocResult<CommandOutcome> =
        NocResult.err(NocError.DemoBlocked("Reboot is disabled in DEMO mode"))

    override suspend fun readIpAddresses() = NocResult.ok(
        listOf(IpAddressEntry("*1", "192.168.88.1/24", "bridge", "192.168.88.0")),
    )

    override suspend fun readDhcpLeases() = NocResult.ok(
        listOf(DhcpLease("*1", "192.168.88.50", "11:22:33:44:55:66", "demo-laptop", "bound")),
    )

    override suspend fun readArp() = NocResult.ok(
        listOf(ArpEntry("*1", "192.168.88.50", "11:22:33:44:55:66", "bridge")),
    )

    override suspend fun readRoutes() = NocResult.ok(
        listOf(RouteEntry("*1", "0.0.0.0/0", "192.168.88.254", distance = 1, active = true, staticRoute = true)),
    )

    override suspend fun readLogs(limit: Int) = NocResult.ok(
        listOf(LogEntry("1", "21:00:00", "system,info", "DEMO log only — not a live device")),
    )

    override fun close() {}

    private fun system() = SystemInfo(
        identity = "DEMO-GW",
        model = "DEMO RB4011",
        version = "DEMO 7.15",
        uptime = "4h",
        cpuLoadPercent = 11,
        memoryTotalBytes = 1_073_741_824,
        memoryFreeBytes = 700_000_000,
        storageTotalBytes = 512_000_000,
        storageFreeBytes = 400_000_000,
        temperatureC = 39.0,
        platform = "DEMO",
        boardName = "DEMO",
    )

    private fun iface(id: String, name: String, running: Boolean, enabled: Boolean, rx: Long, tx: Long) = NetInterface(
        id = id,
        name = name,
        type = "ether",
        running = running,
        enabled = enabled,
        rxBytes = rx,
        txBytes = tx,
        rxPackets = rx / 800,
        txPackets = tx / 800,
    )
}

const val DEMO_DEVICE_ID = "demo-device"
const val DEMO_DEVICE_NAME = "DEMO Gateway"
