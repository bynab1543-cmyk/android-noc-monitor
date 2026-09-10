package com.noc.monitor.data.repo

import com.noc.monitor.data.crypto.CredentialStore
import com.noc.monitor.data.local.DeviceEntity
import com.noc.monitor.data.local.NocDatabase
import com.noc.monitor.data.local.SiteEntity
import com.noc.monitor.data.local.TrafficSampleEntity
import com.noc.monitor.protocol.DeviceCatalog
import com.noc.monitor.protocol.DeviceConnectionConfig
import com.noc.monitor.protocol.NocError
import com.noc.monitor.protocol.NocException
import com.noc.monitor.protocol.NocResult
import com.noc.monitor.protocol.PortPolicy
import com.noc.monitor.protocol.ProductFamily
import com.noc.monitor.protocol.RadioFamily
import com.noc.monitor.protocol.RadioSnapshot
import com.noc.monitor.protocol.Transport
import com.noc.monitor.protocol.Vendor
import com.noc.monitor.protocol.radio.createRadioProbe
import com.noc.monitor.protocol.traffic.TrafficWindow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.UUID

const val DEFAULT_SITE_ID = "site-default"
const val DEFAULT_SITE_NAME = "ابراجي"

data class SaveDeviceRequest(
    val id: String? = null,
    val siteId: String,
    val note: String,
    val host: String,
    val kindId: String,
    val username: String,
    val password: CharArray,
    val snmpCommunity: String,
)

data class TrafficPoint(
    val timestampMs: Long,
    val rxBps: Long,
    val txBps: Long,
)

class DeviceRepository(
    private val db: NocDatabase,
    private val credentials: CredentialStore,
) {
    private val probes = LinkedHashMap<String, com.noc.monitor.protocol.RadioProbe>()

    fun observeDevices(): Flow<List<DeviceEntity>> = db.devices().observe()
    fun observeSites(): Flow<List<SiteEntity>> = db.sites().observe()

    suspend fun allDevices(): List<DeviceEntity> = db.devices().all()
    suspend fun allSites(): List<SiteEntity> = db.sites().all()

    suspend fun ensureDefaultSite() {
        if (db.sites().all().isEmpty()) {
            db.sites().upsert(SiteEntity(DEFAULT_SITE_ID, DEFAULT_SITE_NAME))
        }
    }

    suspend fun addSite(name: String): SiteEntity {
        val site = SiteEntity(UUID.randomUUID().toString(), name.trim().ifBlank { DEFAULT_SITE_NAME })
        db.sites().upsert(site)
        return site
    }

    suspend fun saveDevice(request: SaveDeviceRequest): NocResult<DeviceEntity> = withContext(Dispatchers.IO) {
        val spec = DeviceCatalog.byId(request.kindId)
        val port = spec.defaultPort
        try {
            PortPolicy.requireAllowed(port)
        } catch (e: NocException) {
            return@withContext NocResult.err(e.error)
        }
        val existing = request.id?.let { db.devices().byId(it) }
        val id = existing?.id ?: UUID.randomUUID().toString()
        val credId = existing?.credentialId ?: credentials.createId()
        if (request.password.isNotEmpty()) credentials.save(credId, request.password)
        val snmpId = existing?.snmpCredentialId ?: credentials.createId()
        if (request.snmpCommunity.isNotBlank()) credentials.save(snmpId, request.snmpCommunity.toCharArray())
        val entity = DeviceEntity(
            id = id,
            siteId = request.siteId.ifBlank { DEFAULT_SITE_ID },
            note = request.note.ifBlank { request.host },
            host = request.host.trim(),
            port = port,
            kindId = spec.id,
            username = request.username,
            credentialId = credId,
            snmpCredentialId = snmpId,
            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
            lastSeenAt = existing?.lastSeenAt,
            status = existing?.status ?: "UNKNOWN",
            lastError = existing?.lastError,
            snapshotJson = existing?.snapshotJson,
            lastRxBps = existing?.lastRxBps ?: 0,
            lastTxBps = existing?.lastTxBps ?: 0,
        )
        db.devices().upsert(entity)
        probes.remove(id)?.close()
        NocResult.ok(entity)
    }

    suspend fun deleteDevice(id: String) {
        val existing = db.devices().byId(id)
        if (existing != null) {
            credentials.delete(existing.credentialId)
            existing.snmpCredentialId?.let { credentials.delete(it) }
            db.traffic().clearDevice(id)
            db.devices().delete(id)
        }
        probes.remove(id)?.close()
    }

    suspend fun moveDevice(id: String, siteId: String) {
        val d = db.devices().byId(id) ?: return
        db.devices().upsert(d.copy(siteId = siteId))
    }

    suspend fun pollDevice(device: DeviceEntity): RadioSnapshot = withContext(Dispatchers.IO) {
        val spec = DeviceCatalog.byId(device.kindId)
        val password = credentials.get(device.credentialId) ?: charArrayOf()
        val community = device.snmpCredentialId?.let { credentials.get(it)?.concatToString() } ?: "public"
        val config = DeviceConnectionConfig(
            host = device.host,
            port = device.port,
            username = device.username.ifBlank { "admin" },
            transport = when (spec.probe) {
                com.noc.monitor.protocol.ProbeKind.ROUTEROS_API -> Transport.API
                com.noc.monitor.protocol.ProbeKind.AIROS_HTTP -> Transport.AIROS
                com.noc.monitor.protocol.ProbeKind.SNMP -> Transport.SNMP
            },
            vendor = when (spec.family) {
                RadioFamily.MIKROTIK -> Vendor.MIKROTIK
                RadioFamily.MIMOSA -> Vendor.MIMOSA
                else -> Vendor.UBIQUITI
            },
            productFamily = when (spec.family) {
                RadioFamily.MIKROTIK -> ProductFamily.MIKROTIK_ROUTEROS
                RadioFamily.MIMOSA -> ProductFamily.MIMOSA
                RadioFamily.AIRFIBER -> ProductFamily.UBIQUITI_AIRFIBER
                else -> ProductFamily.UBIQUITI_AIROS
            },
            allowInsecureTls = true,
            displayName = device.note,
            snmpCommunity = community,
            kindId = device.kindId,
        )
        val probe = synchronized(probes) {
            probes[device.id] ?: createRadioProbe(config, { password }, { community }).also { probes[device.id] = it }
        }
        when (val result = probe.poll()) {
            is NocResult.Ok -> {
                val snap = recordTraffic(device.id, result.value)
                db.devices().updateLive(
                    id = device.id,
                    status = "ONLINE",
                    error = null,
                    seen = System.currentTimeMillis(),
                    snapshot = snap.toJson(),
                    rx = ((snap.rxMbps ?: 0.0) * 1_000_000).toLong(),
                    tx = ((snap.txMbps ?: 0.0) * 1_000_000).toLong(),
                )
                snap
            }
            is NocResult.Err -> {
                val err = result.error.userMessage
                val snap = RadioSnapshot(online = false, ip = device.host, lastError = err)
                db.devices().updateLive(device.id, "OFFLINE", err, device.lastSeenAt, snap.toJson(), 0, 0)
                snap
            }
        }
    }

    suspend fun samples(deviceId: String): List<TrafficPoint> {
        val from = System.currentTimeMillis() - TrafficWindow.FIVE_MINUTES.durationMs
        return db.traffic().samplesSince(deviceId, from).map {
            TrafficPoint(it.timestampMs, it.rxBps, it.txBps)
        }
    }

    suspend fun peekSecret(id: String?): String? {
        if (id.isNullOrBlank()) return null
        return credentials.get(id)?.concatToString()
    }

    private suspend fun recordTraffic(deviceId: String, snap: RadioSnapshot): RadioSnapshot {
        val now = System.currentTimeMillis()
        val last = db.traffic().latest(deviceId)
        var rxBps = snap.rxMbps?.let { (it * 1_000_000.0).toLong() }
        var txBps = snap.txMbps?.let { (it * 1_000_000.0).toLong() }
        val rxBytes = snap.rxBytes
        val txBytes = snap.txBytes
        if (rxBytes != null && txBytes != null && last != null && last.rxBytes > 0 && rxBytes >= last.rxBytes) {
            val dtSec = ((now - last.timestampMs).coerceAtLeast(1)).toDouble() / 1000.0
            rxBps = (((rxBytes - last.rxBytes) * 8.0) / dtSec).toLong().coerceAtLeast(0)
            txBps = (((txBytes - last.txBytes) * 8.0) / dtSec).toLong().coerceAtLeast(0)
        }
        if (rxBps == null && txBps == null && rxBytes == null && txBytes == null) {
            return snap
        }
        db.traffic().insert(
            TrafficSampleEntity(
                deviceId = deviceId,
                interfaceName = "link",
                timestampMs = now,
                rxBytes = rxBytes ?: 0,
                txBytes = txBytes ?: 0,
                rxPackets = 0,
                txPackets = 0,
                rxBps = rxBps ?: 0,
                txBps = txBps ?: 0,
            ),
        )
        db.traffic().deleteOlderThan(now - TrafficWindow.TWENTY_FOUR_HOURS.durationMs)
        return snap.copy(
            rxMbps = rxBps?.let { it / 1_000_000.0 } ?: snap.rxMbps,
            txMbps = txBps?.let { it / 1_000_000.0 } ?: snap.txMbps,
        )
    }
}
