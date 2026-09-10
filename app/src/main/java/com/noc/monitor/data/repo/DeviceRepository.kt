package com.noc.monitor.data.repo

import com.noc.monitor.data.crypto.CredentialStore
import com.noc.monitor.data.local.AlertEntity
import com.noc.monitor.data.local.DeviceEntity
import com.noc.monitor.data.local.NocDatabase
import com.noc.monitor.data.local.TrafficPeakEntity
import com.noc.monitor.data.local.TrafficSampleEntity
import com.noc.monitor.demo.DEMO_DEVICE_ID
import com.noc.monitor.demo.DEMO_DEVICE_NAME
import com.noc.monitor.demo.DemoMikroTikProvider
import com.noc.monitor.protocol.CommandOutcome
import com.noc.monitor.protocol.DeviceCapabilities
import com.noc.monitor.protocol.DeviceConnectionConfig
import com.noc.monitor.protocol.DeviceProvider
import com.noc.monitor.protocol.NetInterface
import com.noc.monitor.protocol.NocError
import com.noc.monitor.protocol.NocResult
import com.noc.monitor.protocol.OperatingMode
import com.noc.monitor.protocol.PppoeSession
import com.noc.monitor.protocol.PortPolicy
import com.noc.monitor.protocol.ProductFamily
import com.noc.monitor.protocol.SystemInfo
import com.noc.monitor.protocol.TrafficCounters
import com.noc.monitor.protocol.TrafficRate
import com.noc.monitor.protocol.Transport
import com.noc.monitor.protocol.Vendor
import com.noc.monitor.protocol.createDeviceProvider
import com.noc.monitor.protocol.traffic.DevicePeak
import com.noc.monitor.protocol.traffic.StoredSample
import com.noc.monitor.protocol.traffic.TrafficWindow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.UUID

data class NewDeviceRequest(
    val displayName: String,
    val host: String,
    val port: Int,
    val username: String,
    val password: CharArray,
    val vendor: Vendor,
    val productFamily: ProductFamily,
    val transport: Transport,
    val allowInsecureTls: Boolean,
    val timeoutMs: Int = 8_000,
)

class DeviceRepository(
    private val db: NocDatabase,
    private val credentials: CredentialStore,
) {
    private val providers = LinkedHashMap<String, DeviceProvider>()
    @Volatile var mode: OperatingMode = OperatingMode.REAL

    fun observeDevices(): Flow<List<DeviceEntity>> = db.devices().observe()
    fun observeAlerts(): Flow<List<AlertEntity>> = db.alerts().observe()
    fun observeDeviceAlerts(deviceId: String): Flow<List<AlertEntity>> = db.alerts().observeDevice(deviceId)
    fun observeActiveAlertCount(): Flow<Int> = db.alerts().observeActiveCount()

    suspend fun allDevices(): List<DeviceEntity> = db.devices().all()

    suspend fun testConnection(request: NewDeviceRequest): NocResult<SystemInfo> = withContext(Dispatchers.IO) {
        PortPolicy.requireAllowed(request.port)
        val config = request.toConfig()
        val provider = createDeviceProvider(config) { request.password }
        try {
            provider.testConnection()
        } finally {
            provider.close()
        }
    }

    suspend fun addDevice(request: NewDeviceRequest): NocResult<DeviceEntity> = withContext(Dispatchers.IO) {
        try {
            PortPolicy.requireAllowed(request.port)
        } catch (e: com.noc.monitor.protocol.NocException) {
            return@withContext NocResult.err(e.error)
        }
        val id = UUID.randomUUID().toString()
        val credId = credentials.createId()
        credentials.save(credId, request.password)
        val entity = DeviceEntity(
            id = id,
            displayName = request.displayName.ifBlank { request.host },
            vendor = request.vendor.name,
            productFamily = request.productFamily.name,
            transport = request.transport.name,
            host = request.host.trim(),
            port = request.port,
            username = request.username,
            credentialId = credId,
            allowInsecureTls = request.allowInsecureTls,
            createdAt = System.currentTimeMillis(),
            lastSeenAt = null,
            status = "UNKNOWN",
            lastError = null,
            identity = null,
            model = null,
            version = null,
            lastRxBps = 0,
            lastTxBps = 0,
        )
        db.devices().upsert(entity)
        NocResult.ok(entity)
    }

    suspend fun deleteDevice(id: String) {
        if (id == DEMO_DEVICE_ID) return
        val existing = db.devices().byId(id)
        if (existing != null) {
            credentials.delete(existing.credentialId)
            db.traffic().clearDevice(id)
            db.traffic().clearPeak(id)
            db.alerts().clearDevice(id)
            db.devices().delete(id)
        }
        synchronized(providers) {
            providers.remove(id)?.close()
        }
    }

    suspend fun providerFor(device: DeviceEntity): DeviceProvider {
        if (mode == OperatingMode.DEMO || device.id == DEMO_DEVICE_ID) {
            return DemoMikroTikProvider()
        }
        synchronized(providers) {
            providers[device.id]?.let { return it }
        }
        val password = credentials.get(device.credentialId)
            ?: throw com.noc.monitor.protocol.NocException(NocError.Crypto("Missing stored credentials"))
        val config = DeviceConnectionConfig(
            host = device.host,
            port = device.port,
            username = device.username,
            transport = Transport.valueOf(device.transport),
            vendor = Vendor.valueOf(device.vendor),
            productFamily = ProductFamily.valueOf(device.productFamily),
            allowInsecureTls = device.allowInsecureTls,
            displayName = device.displayName,
        )
        val created = createDeviceProvider(config) { password }
        synchronized(providers) {
            providers[device.id] = created
        }
        return created
    }

    fun capabilitiesOf(device: DeviceEntity): DeviceCapabilities {
        return when (ProductFamily.valueOf(device.productFamily)) {
            ProductFamily.MIKROTIK_ROUTEROS -> DeviceCapabilities.mikroTik()
            ProductFamily.UBIQUITI_UNIFI -> DeviceCapabilities.uniFi()
            ProductFamily.UBIQUITI_EDGEOS -> DeviceCapabilities.edgeOs()
            ProductFamily.UBIQUITI_AIROS -> DeviceCapabilities.airOs()
        }
    }

    suspend fun pollDevice(device: DeviceEntity): PollSnapshot = withContext(Dispatchers.IO) {
        val provider = try {
            providerFor(device)
        } catch (t: Throwable) {
            val err = (t as? com.noc.monitor.protocol.NocException)?.error ?: com.noc.monitor.protocol.mapThrowable(t)
            markOffline(device, err.userMessage)
            return@withContext PollSnapshot(device.id, false, null, emptyList(), emptyList(), NocResult.err(err))
        }
        val sys = provider.readSystem()
        val ifaces = provider.readInterfaces()
        val traffic = provider.readTraffic()
        when (sys) {
            is NocResult.Err -> {
                markOffline(device, sys.error.userMessage)
                raiseAlert(device, "CRITICAL", "Device offline", sys.error.userMessage)
                PollSnapshot(device.id, false, null, ifaces.getOrNull().orEmpty(), emptyList(), sys)
            }
            is NocResult.Ok -> {
                val counters = traffic.getOrNull().orEmpty()
                val rates = recordTraffic(device.id, counters)
                val rx = rates.sumOf { it.rxBps }
                val tx = rates.sumOf { it.txBps }
                db.devices().updateStatus(
                    id = device.id,
                    status = "ONLINE",
                    error = null,
                    seen = System.currentTimeMillis(),
                    identity = sys.value.identity,
                    model = sys.value.model,
                    version = sys.value.version,
                    rx = rx,
                    tx = tx,
                )
                evaluateHealthAlerts(device, sys.value, ifaces.getOrNull().orEmpty())
                PollSnapshot(device.id, true, sys.value, ifaces.getOrNull().orEmpty(), rates, sys)
            }
        }
    }

    suspend fun recordTraffic(deviceId: String, counters: List<TrafficCounters>): List<TrafficRate> {
        val now = System.currentTimeMillis()
        val lastByIface = db.traffic().samplesSince(deviceId, now - 15_000)
            .groupBy { it.interfaceName }
            .mapValues { (_, v) -> v.maxByOrNull { s -> s.timestampMs } }
        val rates = ArrayList<TrafficRate>()
        for (c in counters) {
            val prev = lastByIface[c.interfaceName]
            val dt = if (prev == null) 0.0 else (now - prev.timestampMs).coerceAtLeast(1) / 1000.0
            val rxBps = if (prev == null || dt <= 0) 0 else (((c.rxBytes - prev.rxBytes).coerceAtLeast(0) * 8.0) / dt).toLong()
            val txBps = if (prev == null || dt <= 0) 0 else (((c.txBytes - prev.txBytes).coerceAtLeast(0) * 8.0) / dt).toLong()
            val rxPps = if (prev == null || dt <= 0) 0 else ((c.rxPackets - prev.rxPackets).coerceAtLeast(0) / dt).toLong()
            val txPps = if (prev == null || dt <= 0) 0 else ((c.txPackets - prev.txPackets).coerceAtLeast(0) / dt).toLong()
            db.traffic().insert(
                TrafficSampleEntity(
                    deviceId = deviceId,
                    interfaceName = c.interfaceName,
                    timestampMs = now,
                    rxBytes = c.rxBytes,
                    txBytes = c.txBytes,
                    rxPackets = c.rxPackets,
                    txPackets = c.txPackets,
                    rxBps = rxBps,
                    txBps = txBps,
                ),
            )
            if (prev != null) {
                val peak = db.traffic().peak(deviceId)
                val total = rxBps + txBps
                if (peak == null || total > peak.peakTotalBps) {
                    db.traffic().upsertPeak(
                        TrafficPeakEntity(
                            deviceId = deviceId,
                            interfaceName = c.interfaceName,
                            peakRxBps = rxBps,
                            peakTxBps = txBps,
                            peakTotalBps = total,
                            peakAtMs = now,
                        ),
                    )
                }
            }
            rates += TrafficRate(c.interfaceName, rxBps, txBps, rxPps, txPps, c.rxBytes, c.txBytes, c.rxPackets, c.txPackets, now)
        }
        db.traffic().deleteOlderThan(now - TrafficWindow.TWENTY_FOUR_HOURS.durationMs)
        return rates
    }

    suspend fun samples(deviceId: String, window: TrafficWindow, iface: String? = null): List<StoredSample> {
        val from = System.currentTimeMillis() - window.durationMs
        val rows = if (iface == null) db.traffic().samplesSince(deviceId, from)
        else db.traffic().samplesSinceIface(deviceId, from, iface)
        return rows.map {
            StoredSample(it.deviceId, it.interfaceName, it.timestampMs, it.rxBytes, it.txBytes, it.rxPackets, it.txPackets, it.rxBps, it.txBps)
        }
    }

    suspend fun allPeaks(): List<DevicePeak> = db.traffic().allPeaks().map {
        DevicePeak(it.deviceId, it.interfaceName, it.peakRxBps, it.peakTxBps, it.peakTotalBps, it.peakAtMs)
    }

    suspend fun execute(
        device: DeviceEntity,
        block: suspend (DeviceProvider) -> NocResult<CommandOutcome>,
    ): NocResult<CommandOutcome> = withContext(Dispatchers.IO) {
        try {
            block(providerFor(device))
        } catch (t: Throwable) {
            val err = (t as? com.noc.monitor.protocol.NocException)?.error ?: com.noc.monitor.protocol.mapThrowable(t)
            NocResult.err(err)
        }
    }

    suspend fun ackAlert(id: String) = db.alerts().ack(id)

    suspend fun ensureDemoDevice() {
        if (db.devices().byId(DEMO_DEVICE_ID) != null) return
        db.devices().upsert(
            DeviceEntity(
                id = DEMO_DEVICE_ID,
                displayName = DEMO_DEVICE_NAME,
                vendor = Vendor.MIKROTIK.name,
                productFamily = ProductFamily.MIKROTIK_ROUTEROS.name,
                transport = Transport.API.name,
                host = "demo.local",
                port = PortPolicy.DEFAULT_API,
                username = "demo",
                credentialId = "demo",
                allowInsecureTls = false,
                createdAt = System.currentTimeMillis(),
                lastSeenAt = System.currentTimeMillis(),
                status = "ONLINE",
                lastError = null,
                identity = "DEMO-GW",
                model = "DEMO RB4011",
                version = "DEMO 7.15",
                lastRxBps = 0,
                lastTxBps = 0,
            ),
        )
    }

    suspend fun removeDemoDevice() {
        db.devices().delete(DEMO_DEVICE_ID)
        db.traffic().clearDevice(DEMO_DEVICE_ID)
        db.traffic().clearPeak(DEMO_DEVICE_ID)
        db.alerts().clearDevice(DEMO_DEVICE_ID)
    }

    private suspend fun markOffline(device: DeviceEntity, message: String) {
        db.devices().updateStatus(
            id = device.id,
            status = "OFFLINE",
            error = message,
            seen = device.lastSeenAt,
            identity = device.identity,
            model = device.model,
            version = device.version,
            rx = 0,
            tx = 0,
        )
    }

    private suspend fun raiseAlert(device: DeviceEntity, severity: String, title: String, detail: String) {
        val id = "${device.id}:$title"
        db.alerts().upsert(
            AlertEntity(
                id = id,
                deviceId = device.id,
                severity = severity,
                title = title,
                detail = detail,
                createdAtMs = System.currentTimeMillis(),
                acknowledged = false,
            ),
        )
    }

    private suspend fun evaluateHealthAlerts(device: DeviceEntity, sys: SystemInfo, ifaces: List<NetInterface>) {
        val cpu = sys.cpuLoadPercent
        if (cpu != null && cpu >= 85) {
            raiseAlert(device, "WARNING", "High CPU", "CPU load ${cpu}%")
        }
        val mem = sys.memoryUsedPercent
        if (mem != null && mem >= 90) {
            raiseAlert(device, "WARNING", "High memory", "Memory ${mem}%")
        }
        for (iface in ifaces) {
            if (iface.enabled && !iface.running) {
                raiseAlert(device, "WARNING", "Interface down", "${iface.name} is enabled but not running")
            }
        }
    }

    fun invalidate(deviceId: String) {
        synchronized(providers) {
            providers.remove(deviceId)?.close()
        }
    }
}

data class PollSnapshot(
    val deviceId: String,
    val online: Boolean,
    val system: SystemInfo?,
    val interfaces: List<NetInterface>,
    val rates: List<TrafficRate>,
    val result: NocResult<SystemInfo>,
)

private fun NewDeviceRequest.toConfig() = DeviceConnectionConfig(
    host = host.trim(),
    port = port,
    username = username,
    transport = transport,
    vendor = vendor,
    productFamily = productFamily,
    allowInsecureTls = allowInsecureTls,
    timeoutMs = timeoutMs,
    displayName = displayName,
)
