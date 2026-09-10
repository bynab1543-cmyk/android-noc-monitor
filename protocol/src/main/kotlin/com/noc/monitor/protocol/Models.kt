package com.noc.monitor.protocol

data class SystemInfo(
    val identity: String,
    val model: String? = null,
    val version: String? = null,
    val uptime: String? = null,
    val cpuLoadPercent: Int? = null,
    val cpuCount: Int? = null,
    val architecture: String? = null,
    val boardName: String? = null,
    val memoryTotalBytes: Long? = null,
    val memoryFreeBytes: Long? = null,
    val storageTotalBytes: Long? = null,
    val storageFreeBytes: Long? = null,
    val temperatureC: Double? = null,
    val platform: String? = null,
) {
    val memoryUsedPercent: Int?
        get() {
            val total = memoryTotalBytes ?: return null
            val free = memoryFreeBytes ?: return null
            if (total <= 0L) return null
            return (((total - free).toDouble() / total) * 100.0).toInt().coerceIn(0, 100)
        }
}

data class NetInterface(
    val id: String,
    val name: String,
    val type: String? = null,
    val running: Boolean = false,
    val enabled: Boolean = true,
    val mac: String? = null,
    val comment: String? = null,
    val rxBytes: Long = 0,
    val txBytes: Long = 0,
    val rxPackets: Long = 0,
    val txPackets: Long = 0,
    val mtu: Int? = null,
)

data class TrafficCounters(
    val interfaceName: String,
    val rxBytes: Long,
    val txBytes: Long,
    val rxPackets: Long,
    val txPackets: Long,
    val collectedAtMs: Long = System.currentTimeMillis(),
)

data class TrafficRate(
    val interfaceName: String,
    val rxBps: Long,
    val txBps: Long,
    val rxPps: Long,
    val txPps: Long,
    val rxBytes: Long,
    val txBytes: Long,
    val rxPackets: Long,
    val txPackets: Long,
    val collectedAtMs: Long,
) {
    val totalBps: Long get() = rxBps + txBps
}

data class PppoeSession(
    val id: String,
    val name: String,
    val service: String? = null,
    val address: String? = null,
    val callerId: String? = null,
    val uptime: String? = null,
    val encoding: String? = null,
    val caller: String? = null,
)

data class IpAddressEntry(
    val id: String,
    val address: String,
    val interfaceName: String? = null,
    val network: String? = null,
    val comment: String? = null,
    val disabled: Boolean = false,
)

data class DhcpLease(
    val id: String,
    val address: String,
    val mac: String? = null,
    val hostName: String? = null,
    val status: String? = null,
    val server: String? = null,
    val lastSeen: String? = null,
    val expiresAfter: String? = null,
)

data class ArpEntry(
    val id: String,
    val address: String,
    val mac: String? = null,
    val interfaceName: String? = null,
    val complete: Boolean = true,
)

data class RouteEntry(
    val id: String,
    val dstAddress: String,
    val gateway: String? = null,
    val interfaceName: String? = null,
    val distance: Int? = null,
    val active: Boolean = false,
    val staticRoute: Boolean = false,
)

data class LogEntry(
    val id: String,
    val time: String? = null,
    val topics: String? = null,
    val message: String,
)

enum class AlertSeverity { INFO, WARNING, CRITICAL }

data class DeviceAlert(
    val id: String,
    val deviceId: String,
    val severity: AlertSeverity,
    val title: String,
    val detail: String,
    val createdAtMs: Long,
    val acknowledged: Boolean = false,
)

data class DeviceCapabilities(
    val family: ProductFamily,
    val canReadSystem: Boolean = false,
    val canReadInterfaces: Boolean = false,
    val canReadTraffic: Boolean = false,
    val canReadPppoe: Boolean = false,
    val canDisconnectPppoe: Boolean = false,
    val canToggleInterface: Boolean = false,
    val canReboot: Boolean = false,
    val canReadIp: Boolean = false,
    val canReadDhcp: Boolean = false,
    val canReadArp: Boolean = false,
    val canReadRoutes: Boolean = false,
    val canReadLogs: Boolean = false,
) {
    companion object {
        fun mikroTik(): DeviceCapabilities = DeviceCapabilities(
            family = ProductFamily.MIKROTIK_ROUTEROS,
            canReadSystem = true,
            canReadInterfaces = true,
            canReadTraffic = true,
            canReadPppoe = true,
            canDisconnectPppoe = true,
            canToggleInterface = true,
            canReboot = true,
            canReadIp = true,
            canReadDhcp = true,
            canReadArp = true,
            canReadRoutes = true,
            canReadLogs = true,
        )

        fun uniFi(): DeviceCapabilities = DeviceCapabilities(
            family = ProductFamily.UBIQUITI_UNIFI,
            canReadSystem = true,
            canReadInterfaces = true,
            canReadTraffic = true,
            canReadPppoe = false,
            canDisconnectPppoe = false,
            canToggleInterface = false,
            canReboot = true,
            canReadIp = true,
            canReadDhcp = false,
            canReadArp = false,
            canReadRoutes = false,
            canReadLogs = false,
        )

        fun edgeOs(): DeviceCapabilities = DeviceCapabilities(
            family = ProductFamily.UBIQUITI_EDGEOS,
            canReadSystem = true,
            canReadInterfaces = true,
            canReadTraffic = true,
            canReadPppoe = true,
            canDisconnectPppoe = false,
            canToggleInterface = false,
            canReboot = true,
            canReadIp = true,
            canReadDhcp = true,
            canReadArp = true,
            canReadRoutes = true,
            canReadLogs = false,
        )

        fun airOs(): DeviceCapabilities = DeviceCapabilities(
            family = ProductFamily.UBIQUITI_AIROS,
            canReadSystem = true,
            canReadInterfaces = true,
            canReadTraffic = true,
            canReadPppoe = false,
            canDisconnectPppoe = false,
            canToggleInterface = false,
            canReboot = true,
            canReadIp = true,
            canReadDhcp = false,
            canReadArp = false,
            canReadRoutes = false,
            canReadLogs = false,
        )
    }
}

interface DeviceProvider {
    val capabilities: DeviceCapabilities

    suspend fun testConnection(): NocResult<SystemInfo>
    suspend fun readSystem(): NocResult<SystemInfo>
    suspend fun readInterfaces(): NocResult<List<NetInterface>>
    suspend fun readTraffic(): NocResult<List<TrafficCounters>>
    suspend fun readPppoe(): NocResult<List<PppoeSession>>
    suspend fun disconnectPppoe(sessionId: String): NocResult<CommandOutcome>
    suspend fun setInterfaceEnabled(interfaceId: String, enabled: Boolean): NocResult<CommandOutcome>
    suspend fun reboot(): NocResult<CommandOutcome>
    suspend fun readIpAddresses(): NocResult<List<IpAddressEntry>>
    suspend fun readDhcpLeases(): NocResult<List<DhcpLease>>
    suspend fun readArp(): NocResult<List<ArpEntry>>
    suspend fun readRoutes(): NocResult<List<RouteEntry>>
    suspend fun readLogs(limit: Int = 50): NocResult<List<LogEntry>>
    fun close()
}

fun unsupported(op: String, family: ProductFamily): NocResult<Nothing> =
    NocResult.err(NocError.Unsupported(op, family.name))
