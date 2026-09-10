package com.noc.monitor.protocol.radio

import com.noc.monitor.protocol.DeviceCatalog
import com.noc.monitor.protocol.DeviceConnectionConfig
import com.noc.monitor.protocol.NocError
import com.noc.monitor.protocol.NocException
import com.noc.monitor.protocol.NocResult
import com.noc.monitor.protocol.ProbeKind
import com.noc.monitor.protocol.RadioFamily
import com.noc.monitor.protocol.RadioProbe
import com.noc.monitor.protocol.RadioSnapshot
import com.noc.monitor.protocol.Transport
import com.noc.monitor.protocol.routeros.RouterOsApiClient
import com.noc.monitor.protocol.snmp.SnmpV2cClient
import com.noc.monitor.protocol.ubiquiti.UbiquitiProvider

object Oids {
    const val SYS_DESCR = "1.3.6.1.2.1.1.1.0"
    const val SYS_UPTIME = "1.3.6.1.2.1.1.3.0"
    const val SYS_NAME = "1.3.6.1.2.1.1.5.0"
    const val IF_DESCR1 = "1.3.6.1.2.1.2.2.1.2.1"
    const val IF_SPEED1 = "1.3.6.1.2.1.2.2.1.5.1"
    const val IF_IN1 = "1.3.6.1.2.1.2.2.1.10.1"
    const val IF_OUT1 = "1.3.6.1.2.1.2.2.1.16.1"
    const val IF_PHYS1 = "1.3.6.1.2.1.2.2.1.6.1"

    const val UBNT_FREQ = "1.3.6.1.4.1.41112.1.4.1.1.4.1"
    const val UBNT_TXPOWER = "1.3.6.1.4.1.41112.1.4.1.1.6.1"
    const val UBNT_CCQ = "1.3.6.1.4.1.41112.1.4.5.1.5.1"
    const val UBNT_SIGNAL = "1.3.6.1.4.1.41112.1.4.5.1.3.1"
    const val UBNT_TXRATE = "1.3.6.1.4.1.41112.1.4.5.1.8.1"
    const val UBNT_RXRATE = "1.3.6.1.4.1.41112.1.4.5.1.9.1"
    const val UBNT_SSID = "1.3.6.1.4.1.41112.1.4.1.1.7.1"

    const val AF_FREQ = "1.3.6.1.4.1.41112.1.3.1.1.1.1"
    const val AF_CAPACITY = "1.3.6.1.4.1.41112.1.3.2.1.5.1"
    const val AF_TXPOWER = "1.3.6.1.4.1.41112.1.3.1.1.9.1"
    const val AF_RXPOWER = "1.3.6.1.4.1.41112.1.3.2.1.6.1"

    const val MIMOSA_FW = "1.3.6.1.4.1.43356.2.1.2.1.4.0"
    const val MIMOSA_TEMP = "1.3.6.1.4.1.43356.2.1.2.2.1.0"
    const val MIMOSA_FREQ = "1.3.6.1.4.1.43356.2.1.2.6.1.0"
    const val MIMOSA_WIDTH = "1.3.6.1.4.1.43356.2.1.2.6.3.0"
    const val MIMOSA_TXPOWER = "1.3.6.1.4.1.43356.2.1.2.7.2.0"
    const val MIMOSA_SNR = "1.3.6.1.4.1.43356.2.1.2.7.5.0"
    const val MIMOSA_TXRATE = "1.3.6.1.4.1.43356.2.1.2.8.1.0"
    const val MIMOSA_RXRATE = "1.3.6.1.4.1.43356.2.1.2.8.2.0"
    const val MIMOSA_TXCCQ = "1.3.6.1.4.1.43356.2.1.2.8.5.0"
    const val MIMOSA_RXCCQ = "1.3.6.1.4.1.43356.2.1.2.8.6.0"
    const val MIMOSA_SSID = "1.3.6.1.4.1.43356.2.1.2.5.1.0"
}

fun createRadioProbe(
    config: DeviceConnectionConfig,
    passwordProvider: () -> CharArray,
    communityProvider: () -> String,
): RadioProbe {
    val spec = DeviceCatalog.byId(config.kindId)
    return when (spec.probe) {
        ProbeKind.ROUTEROS_API -> MikroTikRadioProbe(config, passwordProvider)
        ProbeKind.AIROS_HTTP -> AirOsRadioProbe(config, passwordProvider)
        ProbeKind.SNMP -> when (spec.family) {
            RadioFamily.MIMOSA -> MimosaSnmpProbe(config, communityProvider)
            else -> UbntSnmpProbe(config, communityProvider)
        }
    }
}

class MikroTikRadioProbe(
    private val config: DeviceConnectionConfig,
    private val passwordProvider: () -> CharArray,
) : RadioProbe {
    private var client: RouterOsApiClient? = null

    override suspend fun poll(): NocResult<RadioSnapshot> = NocResult.catch {
        val api = session()
        val identity = api.print("/system/identity/print").firstOrNull()?.get("name")
        val resource = api.print("/system/resource/print").firstOrNull() ?: emptyMap()
        val health = try {
            api.print("/system/health/print").firstOrNull() ?: emptyMap()
        } catch (_: Throwable) {
            emptyMap()
        }
        val ifaces = try {
            api.print("/interface/print")
        } catch (_: Throwable) {
            emptyList()
        }
        val wireless = try {
            api.print("/interface/wireless/print")
        } catch (_: Throwable) {
            emptyList()
        }
        val regs = try {
            api.print("/interface/wireless/registration-table/print")
        } catch (_: Throwable) {
            emptyList()
        }
        val ether = ifaces.firstOrNull { it["type"] == "ether" && it["running"] == "true" }
            ?: ifaces.firstOrNull { it["running"] == "true" }
            ?: ifaces.firstOrNull()
        val wlan = wireless.firstOrNull()
        val cpu = resource["cpu-load"]?.toIntOrNull()
        val memTotal = resource["total-memory"]?.toLongOrNull()
        val memFree = resource["free-memory"]?.toLongOrNull()
        val ram = if (memTotal != null && memFree != null && memTotal > 0) {
            (((memTotal - memFree).toDouble() / memTotal) * 100.0).toInt()
        } else null
        val temp = health["temperature"]?.replace("C", "", true)?.trim()?.toDoubleOrNull()
            ?: health["cpu-temperature"]?.toDoubleOrNull()
        val ccq = regs.mapNotNull { it["tx-ccq"]?.toIntOrNull() }.maxOrNull()
            ?: regs.mapNotNull { it["rx-ccq"]?.toIntOrNull() }.maxOrNull()
        val rxBytes = ether?.get("rx-byte")?.toLongOrNull() ?: wlan?.get("rx-byte")?.toLongOrNull()
        val txBytes = ether?.get("tx-byte")?.toLongOrNull() ?: wlan?.get("tx-byte")?.toLongOrNull()
        RadioSnapshot(
            online = true,
            identity = identity,
            ip = config.host,
            mac = ether?.get("mac-address") ?: wlan?.get("mac-address"),
            ssid = wlan?.get("ssid"),
            mode = wlan?.get("mode"),
            state = if (ether?.get("running") == "true" || wlan?.get("running") == "true") "running" else "offline",
            firmware = resource["version"],
            cpuPercent = cpu,
            ramPercent = ram,
            ccq = ccq,
            txCcq = regs.firstOrNull()?.get("tx-ccq")?.toIntOrNull(),
            rxCcq = regs.firstOrNull()?.get("rx-ccq")?.toIntOrNull(),
            frequencyMhz = wlan?.get("frequency")?.toIntOrNull(),
            channelWidthMhz = wlan?.get("channel-width")?.replace("mhz", "", true)?.trim()?.toIntOrNull(),
            uptime = resource["uptime"],
            temperatureC = temp,
            ethernetSpeed = formatSpeed(ether?.get("speed") ?: ether?.get("actual-mtu")),
            rxBytes = rxBytes,
            txBytes = txBytes,
            voltage = health["voltage"]?.replace("V", "", true)?.trim()?.toDoubleOrNull(),
            clients = regs.size.takeIf { wireless.isNotEmpty() },
            signalDbm = regs.firstOrNull()?.get("signal-strength")?.replace("dBm", "", true)?.trim()?.toDoubleOrNull(),
            txPowerDbm = wlan?.get("tx-power")?.toDoubleOrNull(),
            scanList = wlan?.get("scan-list"),
            lastError = null,
        )
    }

    @Synchronized
    private fun session(): RouterOsApiClient {
        val existing = client
        if (existing != null && existing.isConnected) return existing
        existing?.close()
        val created = RouterOsApiClient(
            config.host,
            config.port,
            useTls = config.transport == Transport.API_SSL,
            allowInsecureTls = config.allowInsecureTls,
            timeoutMs = config.timeoutMs,
        )
        created.connect()
        created.login(config.username, passwordProvider())
        client = created
        return created
    }

    override fun close() {
        client?.close()
        client = null
    }
}

class AirOsRadioProbe(
    private val config: DeviceConnectionConfig,
    private val passwordProvider: () -> CharArray,
) : RadioProbe {
    override suspend fun poll(): NocResult<RadioSnapshot> = NocResult.catchSuspend {
        val ub = UbiquitiProvider(config, passwordProvider)
        try {
            when (val status = ub.readAirOsStatus()) {
                is NocResult.Ok -> AirOsStatusParser.toSnapshot(config.host, status.value)
                is NocResult.Err -> throw NocException(status.error)
            }
        } finally {
            ub.close()
        }
    }

    override fun close() {}
}

class MimosaSnmpProbe(
    private val config: DeviceConnectionConfig,
    private val communityProvider: () -> String,
) : RadioProbe {
    override suspend fun poll(): NocResult<RadioSnapshot> = snmpPoll(config, communityProvider()) { map ->
        val freq = map.int(Oids.MIMOSA_FREQ) ?: map.int(Oids.UBNT_FREQ)
        val txCcq = map.ccq(Oids.MIMOSA_TXCCQ)
        val rxCcq = map.ccq(Oids.MIMOSA_RXCCQ)
        RadioSnapshot(
            online = true,
            identity = map[Oids.SYS_NAME],
            ip = config.host,
            mac = map.mac(Oids.IF_PHYS1),
            ssid = map[Oids.MIMOSA_SSID] ?: map[Oids.UBNT_SSID],
            firmware = map[Oids.MIMOSA_FW] ?: map[Oids.SYS_DESCR],
            uptime = ticksToUptime(map[Oids.SYS_UPTIME]),
            temperatureC = map.double(Oids.MIMOSA_TEMP),
            frequencyMhz = freq,
            channelWidthMhz = map.int(Oids.MIMOSA_WIDTH),
            txCcq = txCcq,
            rxCcq = rxCcq,
            ccq = txCcq ?: rxCcq,
            txPowerDbm = map.double(Oids.MIMOSA_TXPOWER),
            snr = map.int(Oids.MIMOSA_SNR),
            capacityTxMbps = map.int(Oids.MIMOSA_TXRATE),
            capacityRxMbps = map.int(Oids.MIMOSA_RXRATE),
            ethernetSpeed = map.speed(Oids.IF_SPEED1),
            rxBytes = map.long(Oids.IF_IN1),
            txBytes = map.long(Oids.IF_OUT1),
            state = "متصل",
        )
    }

    override fun close() {}
}

class UbntSnmpProbe(
    private val config: DeviceConnectionConfig,
    private val communityProvider: () -> String,
) : RadioProbe {
    override suspend fun poll(): NocResult<RadioSnapshot> = snmpPoll(config, communityProvider()) { map ->
        val ccq = map.ccq(Oids.UBNT_CCQ)
        RadioSnapshot(
            online = true,
            identity = map[Oids.SYS_NAME],
            ip = config.host,
            mac = map.mac(Oids.IF_PHYS1),
            ssid = map[Oids.UBNT_SSID],
            firmware = map[Oids.SYS_DESCR],
            uptime = ticksToUptime(map[Oids.SYS_UPTIME]),
            frequencyMhz = map.int(Oids.UBNT_FREQ) ?: map.int(Oids.AF_FREQ),
            ccq = ccq,
            txCcq = ccq,
            signalDbm = map.double(Oids.UBNT_SIGNAL) ?: map.double(Oids.AF_RXPOWER),
            txPowerDbm = map.double(Oids.UBNT_TXPOWER) ?: map.double(Oids.AF_TXPOWER),
            capacityTxMbps = map.int(Oids.UBNT_TXRATE) ?: map.int(Oids.AF_CAPACITY),
            capacityRxMbps = map.int(Oids.UBNT_RXRATE) ?: map.int(Oids.AF_CAPACITY),
            ethernetSpeed = map.speed(Oids.IF_SPEED1),
            rxBytes = map.long(Oids.IF_IN1),
            txBytes = map.long(Oids.IF_OUT1),
            state = "متصل",
        )
    }

    override fun close() {}
}

private fun snmpPoll(
    config: DeviceConnectionConfig,
    community: String,
    map: (Map<String, String>) -> RadioSnapshot,
): NocResult<RadioSnapshot> = NocResult.catch {
    val client = SnmpV2cClient(config.host, config.port, community.ifBlank { "public" }, config.timeoutMs)
    val core = listOf(Oids.SYS_DESCR, Oids.SYS_UPTIME, Oids.SYS_NAME, Oids.IF_DESCR1, Oids.IF_SPEED1, Oids.IF_IN1, Oids.IF_OUT1, Oids.IF_PHYS1)
    val extra = listOf(
        Oids.UBNT_FREQ, Oids.UBNT_TXPOWER, Oids.UBNT_CCQ, Oids.UBNT_SIGNAL, Oids.UBNT_TXRATE, Oids.UBNT_RXRATE, Oids.UBNT_SSID,
        Oids.AF_FREQ, Oids.AF_CAPACITY, Oids.AF_TXPOWER, Oids.AF_RXPOWER,
        Oids.MIMOSA_FW, Oids.MIMOSA_TEMP, Oids.MIMOSA_FREQ, Oids.MIMOSA_WIDTH, Oids.MIMOSA_TXPOWER,
        Oids.MIMOSA_SNR, Oids.MIMOSA_TXRATE, Oids.MIMOSA_RXRATE, Oids.MIMOSA_TXCCQ, Oids.MIMOSA_RXCCQ, Oids.MIMOSA_SSID,
    )
    val values = LinkedHashMap<String, String>()
    values.putAll(client.get(core))
    if (values.isEmpty()) throw NocException(NocError.Unreachable(config.host, "لا رد SNMP"))
    extra.chunked(6).forEach { chunk ->
        try {
            values.putAll(client.get(chunk))
        } catch (_: Throwable) {
        }
    }
    map(values)
}

private fun Map<String, String>.int(oid: String): Int? = double(oid)?.toInt()
private fun Map<String, String>.long(oid: String): Long? = this[oid]?.replace(Regex("[^0-9\\-]"), "")?.toLongOrNull()
private fun Map<String, String>.double(oid: String): Double? = this[oid]?.replace(Regex("[^0-9.\\-]"), "")?.toDoubleOrNull()
private fun Map<String, String>.ccq(oid: String): Int? {
    val v = double(oid) ?: return null
    return when {
        v > 1000 -> (v / 100.0).toInt()
        v > 100 -> 100
        else -> v.toInt()
    }
}

private fun Map<String, String>.mac(oid: String): String? {
    val raw = this[oid] ?: return null
    if (raw.contains(":") || raw.contains("-")) return raw
    if (raw.length == 6) {
        return raw.map { "%02X".format(it.code and 0xFF) }.joinToString(":")
    }
    val hex = raw.filter { it.isLetterOrDigit() }
    if (hex.length == 12) {
        return hex.chunked(2).joinToString(":") { it.uppercase() }
    }
    return null
}

private fun Map<String, String>.speed(oid: String): String? {
    val bps = this[oid]?.toLongOrNull() ?: return null
    return when {
        bps >= 1_000_000_000 -> "1000Mbps"
        bps >= 100_000_000 -> "100Mbps"
        bps >= 10_000_000 -> "10Mbps"
        else -> "${bps}bps"
    }
}

internal fun formatSpeed(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    val lower = raw.lowercase()
    return when {
        "gbps" in lower || lower == "1g" -> "1000Mbps"
        else -> raw
    }
}

private fun ticksToUptime(ticks: String?): String? {
    val t = ticks?.toLongOrNull() ?: return ticks
    val sec = t / 100
    val d = sec / 86400
    val h = (sec % 86400) / 3600
    val m = (sec % 3600) / 60
    return if (d > 0) "${d}d${h}h${m}m" else "${h}h${m}m"
}
