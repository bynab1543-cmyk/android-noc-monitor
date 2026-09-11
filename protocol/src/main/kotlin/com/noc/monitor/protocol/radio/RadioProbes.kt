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
import com.noc.monitor.protocol.Telemetry
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
    const val IF_SPEED2 = "1.3.6.1.2.1.2.2.1.5.2"
    const val IF_IN2 = "1.3.6.1.2.1.2.2.1.10.2"
    const val IF_OUT2 = "1.3.6.1.2.1.2.2.1.16.2"
    const val IF_PHYS2 = "1.3.6.1.2.1.2.2.1.6.2"

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

    const val MIMOSA_NAME = "1.3.6.1.4.1.43356.2.1.2.1.1.0"
    const val MIMOSA_FW = "1.3.6.1.4.1.43356.2.1.2.1.3.0"
    const val MIMOSA_TEMP = "1.3.6.1.4.1.43356.2.1.2.1.8.0"
    const val MIMOSA_WAN_MAC = "1.3.6.1.4.1.43356.2.1.2.3.2.0"
    const val MIMOSA_WAN_UPTIME = "1.3.6.1.4.1.43356.2.1.2.3.4.0"
    const val MIMOSA_MODE = "1.3.6.1.4.1.43356.2.1.2.4.1.0"
    const val MIMOSA_CHAIN_TX = "1.3.6.1.4.1.43356.2.1.2.6.1.1.2.1"
    const val MIMOSA_CHAIN_RX = "1.3.6.1.4.1.43356.2.1.2.6.1.1.3.1"
    const val MIMOSA_CHAIN_NOISE = "1.3.6.1.4.1.43356.2.1.2.6.1.1.4.1"
    const val MIMOSA_CHAIN_SNR = "1.3.6.1.4.1.43356.2.1.2.6.1.1.5.1"
    const val MIMOSA_CHAIN_FREQ = "1.3.6.1.4.1.43356.2.1.2.6.1.1.6.1"
    const val MIMOSA_CHAN_WIDTH = "1.3.6.1.4.1.43356.2.1.2.6.3.1.3.1"
    const val MIMOSA_CHAN_TX = "1.3.6.1.4.1.43356.2.1.2.6.3.1.4.1"
    const val MIMOSA_CHAN_FREQ = "1.3.6.1.4.1.43356.2.1.2.6.3.1.5.1"
    const val MIMOSA_C5_FREQ = "1.3.6.1.4.1.43356.2.1.2.6.1.1.1"
    const val MIMOSA_C5_TX = "1.3.6.1.4.1.43356.2.1.2.6.1.5.1"
    const val MIMOSA_C5_RX = "1.3.6.1.4.1.43356.2.1.2.6.1.6.1"
    const val MIMOSA_TOTAL_TX = "1.3.6.1.4.1.43356.2.1.2.6.5.0"
    const val MIMOSA_TOTAL_RX = "1.3.6.1.4.1.43356.2.1.2.6.6.0"
    const val MIMOSA_PHY_TX = "1.3.6.1.4.1.43356.2.1.2.7.1.0"
    const val MIMOSA_PHY_RX = "1.3.6.1.4.1.43356.2.1.2.7.2.0"
    const val MIMOSA_PER_TX = "1.3.6.1.4.1.43356.2.1.2.7.3.0"
    const val MIMOSA_PER_RX = "1.3.6.1.4.1.43356.2.1.2.7.4.0"
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
        val healthRows = try {
            api.print("/system/health/print")
        } catch (_: Throwable) {
            emptyList()
        }
        val health = flattenHealth(healthRows)
        val ifaces = try {
            api.print("/interface/print")
        } catch (_: Throwable) {
            emptyList()
        }
        val wireless = firstWorking(api, "/interface/wireless/print", "/interface/wifi/print", "/interface/wifiwave2/print")
        val regs = firstWorking(
            api,
            "/interface/wireless/registration-table/print",
            "/interface/wifi/registration-table/print",
            "/caps-man/registration-table/print",
        )
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
        val temp = Telemetry.temperatureC(
            health["temperature"]?.replace("C", "", true)?.trim()?.toDoubleOrNull()
                ?: health["cpu-temperature"]?.toDoubleOrNull(),
        )
        val ccq = Telemetry.ccq(
            regs.mapNotNull { it["tx-ccq"]?.toDoubleOrNull() }.maxOrNull()
                ?: regs.mapNotNull { it["rx-ccq"]?.toDoubleOrNull() }.maxOrNull(),
        )
        val rxBytes = ether?.get("rx-byte")?.toLongOrNull() ?: wlan?.get("rx-byte")?.toLongOrNull()
        val txBytes = ether?.get("tx-byte")?.toLongOrNull() ?: wlan?.get("tx-byte")?.toLongOrNull()
        val running = ether?.get("running") == "true" || wlan?.get("running") == "true"
        RadioSnapshot(
            online = true,
            identity = identity,
            ip = config.host,
            mac = ether?.get("mac-address") ?: wlan?.get("mac-address"),
            ssid = wlan?.get("ssid") ?: wlan?.get("configuration.ssid"),
            mode = arabicMode(wlan?.get("mode") ?: wlan?.get("configuration.mode")),
            state = if (running) "يعمل" else "متوقف",
            firmware = resource["version"],
            cpuPercent = cpu,
            ramPercent = ram,
            ccq = ccq,
            txCcq = Telemetry.ccq(regs.firstOrNull()?.get("tx-ccq")?.toDoubleOrNull()),
            rxCcq = Telemetry.ccq(regs.firstOrNull()?.get("rx-ccq")?.toDoubleOrNull()),
            frequencyMhz = Telemetry.mhz(
                (wlan?.get("frequency") ?: wlan?.get("channel.frequency"))?.toDoubleOrNull(),
            ),
            channelWidthMhz = Telemetry.channelWidthMhz(
                wlan?.get("channel-width")?.replace("mhz", "", true)?.trim()?.toDoubleOrNull(),
            ),
            uptime = resource["uptime"],
            temperatureC = temp,
            ethernetSpeed = formatSpeed(ether?.get("speed")),
            rxBytes = rxBytes,
            txBytes = txBytes,
            voltage = Telemetry.voltage(health["voltage"]?.replace("V", "", true)?.trim()?.toDoubleOrNull()),
            clients = regs.size.takeIf { wireless.isNotEmpty() },
            signalDbm = Telemetry.signalDbm(
                regs.firstOrNull()?.get("signal-strength")?.replace("dBm", "", true)?.trim()?.toDoubleOrNull()
                    ?: regs.firstOrNull()?.get("signal")?.toDoubleOrNull(),
            ),
            txPowerDbm = Telemetry.powerDbm(wlan?.get("tx-power")?.toDoubleOrNull()),
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
                is NocResult.Ok -> sanitizeAirOs(AirOsStatusParser.toSnapshot(config.host, status.value))
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
        val txMbps = Telemetry.phyKbpsToMbps(map.double(Oids.MIMOSA_PHY_TX))
        val rxMbps = Telemetry.phyKbpsToMbps(map.double(Oids.MIMOSA_PHY_RX))
        RadioSnapshot(
            online = true,
            identity = map[Oids.MIMOSA_NAME] ?: map[Oids.SYS_NAME],
            ip = config.host,
            mac = map.mac(Oids.MIMOSA_WAN_MAC) ?: map.mac(Oids.IF_PHYS1) ?: map.mac(Oids.IF_PHYS2),
            firmware = map[Oids.MIMOSA_FW] ?: map[Oids.SYS_DESCR],
            uptime = ticksToUptime(map[Oids.MIMOSA_WAN_UPTIME] ?: map[Oids.SYS_UPTIME]),
            temperatureC = Telemetry.temperatureC(map.double(Oids.MIMOSA_TEMP)),
            frequencyMhz = Telemetry.mhz(
                map.double(Oids.MIMOSA_CHAN_FREQ) ?: map.double(Oids.MIMOSA_CHAIN_FREQ) ?: map.double(Oids.MIMOSA_C5_FREQ),
            ),
            channelWidthMhz = Telemetry.channelWidthMhz(map.double(Oids.MIMOSA_CHAN_WIDTH)),
            txPowerDbm = Telemetry.powerDbm(
                map.double(Oids.MIMOSA_TOTAL_TX) ?: map.double(Oids.MIMOSA_CHAIN_TX)
                    ?: map.double(Oids.MIMOSA_CHAN_TX) ?: map.double(Oids.MIMOSA_C5_TX),
            ),
            signalDbm = Telemetry.signalDbm(
                map.double(Oids.MIMOSA_TOTAL_RX) ?: map.double(Oids.MIMOSA_CHAIN_RX) ?: map.double(Oids.MIMOSA_C5_RX),
            ),
            snr = Telemetry.snr(map.double(Oids.MIMOSA_CHAIN_SNR)),
            interferenceDbm = Telemetry.signalDbm(map.double(Oids.MIMOSA_CHAIN_NOISE)),
            txErrorPercent = Telemetry.perPercent(map.double(Oids.MIMOSA_PER_TX)),
            rxErrorPercent = Telemetry.perPercent(map.double(Oids.MIMOSA_PER_RX)),
            txMbps = txMbps,
            rxMbps = rxMbps,
            ethernetSpeed = map.speed(Oids.IF_SPEED2) ?: map.speed(Oids.IF_SPEED1),
            mode = mimosaMode(map.int(Oids.MIMOSA_MODE)),
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
        val ccq = Telemetry.ccq(map.double(Oids.UBNT_CCQ))
        val txRate = airmaxRateMbps(map.double(Oids.UBNT_TXRATE))
        val rxRate = airmaxRateMbps(map.double(Oids.UBNT_RXRATE))
        RadioSnapshot(
            online = true,
            identity = map[Oids.SYS_NAME],
            ip = config.host,
            mac = map.mac(Oids.IF_PHYS1) ?: map.mac(Oids.IF_PHYS2),
            ssid = map[Oids.UBNT_SSID],
            firmware = map[Oids.SYS_DESCR],
            uptime = ticksToUptime(map[Oids.SYS_UPTIME]),
            frequencyMhz = Telemetry.mhz(map.double(Oids.UBNT_FREQ) ?: map.double(Oids.AF_FREQ)),
            ccq = ccq,
            txCcq = ccq,
            signalDbm = Telemetry.signalDbm(map.double(Oids.UBNT_SIGNAL) ?: map.double(Oids.AF_RXPOWER)),
            txPowerDbm = Telemetry.powerDbm(map.double(Oids.UBNT_TXPOWER) ?: map.double(Oids.AF_TXPOWER)),
            capacityTxMbps = txRate,
            capacityRxMbps = rxRate,
            ethernetSpeed = map.speed(Oids.IF_SPEED2) ?: map.speed(Oids.IF_SPEED1),
            rxBytes = map.long(Oids.IF_IN2) ?: map.long(Oids.IF_IN1),
            txBytes = map.long(Oids.IF_OUT2) ?: map.long(Oids.IF_OUT1),
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
        Oids.IF_SPEED2, Oids.IF_IN2, Oids.IF_OUT2, Oids.IF_PHYS2,
        Oids.UBNT_FREQ, Oids.UBNT_TXPOWER, Oids.UBNT_CCQ, Oids.UBNT_SIGNAL, Oids.UBNT_TXRATE, Oids.UBNT_RXRATE, Oids.UBNT_SSID,
        Oids.AF_FREQ, Oids.AF_CAPACITY, Oids.AF_TXPOWER, Oids.AF_RXPOWER,
        Oids.MIMOSA_NAME, Oids.MIMOSA_FW, Oids.MIMOSA_TEMP, Oids.MIMOSA_WAN_MAC, Oids.MIMOSA_WAN_UPTIME, Oids.MIMOSA_MODE,
        Oids.MIMOSA_CHAIN_TX, Oids.MIMOSA_CHAIN_RX, Oids.MIMOSA_CHAIN_NOISE, Oids.MIMOSA_CHAIN_SNR, Oids.MIMOSA_CHAIN_FREQ,
        Oids.MIMOSA_CHAN_WIDTH, Oids.MIMOSA_CHAN_TX, Oids.MIMOSA_CHAN_FREQ,
        Oids.MIMOSA_C5_FREQ, Oids.MIMOSA_C5_TX, Oids.MIMOSA_C5_RX,
        Oids.MIMOSA_TOTAL_TX, Oids.MIMOSA_TOTAL_RX, Oids.MIMOSA_PHY_TX, Oids.MIMOSA_PHY_RX, Oids.MIMOSA_PER_TX, Oids.MIMOSA_PER_RX,
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

private fun sanitizeAirOs(snap: RadioSnapshot): RadioSnapshot = snap.copy(
    cpuPercent = snap.cpuPercent?.takeIf { it in 0..100 },
    ramPercent = snap.ramPercent?.takeIf { it in 0..100 },
    ccq = Telemetry.ccq(snap.ccq?.toDouble()),
    txCcq = Telemetry.ccq(snap.txCcq?.toDouble()),
    rxCcq = Telemetry.ccq(snap.rxCcq?.toDouble()),
    temperatureC = Telemetry.temperatureC(snap.temperatureC),
    txPowerDbm = Telemetry.powerDbm(snap.txPowerDbm),
    signalDbm = Telemetry.signalDbm(snap.signalDbm),
    voltage = Telemetry.voltage(snap.voltage),
    ethernetSpeed = formatSpeed(snap.ethernetSpeed),
    state = when (snap.state) {
        "running" -> "يعمل"
        "offline" -> "غير متصل"
        else -> snap.state
    },
    mode = arabicMode(snap.mode),
)

private fun flattenHealth(rows: List<Map<String, String>>): Map<String, String> {
    if (rows.isEmpty()) return emptyMap()
    if (rows.size == 1 && (rows[0].containsKey("temperature") || rows[0].containsKey("voltage"))) {
        return rows[0]
    }
    val out = LinkedHashMap<String, String>()
    for (row in rows) {
        val name = row["name"] ?: row["type"] ?: continue
        val value = row["value"] ?: continue
        out[name] = value
    }
    if (out.isEmpty()) return rows.first()
    return out
}

private fun firstWorking(api: RouterOsApiClient, vararg cmds: String): List<Map<String, String>> {
    for (cmd in cmds) {
        try {
            return api.print(cmd)
        } catch (_: Throwable) {
        }
    }
    return emptyList()
}

private fun Map<String, String>.int(oid: String): Int? = double(oid)?.toInt()
private fun Map<String, String>.long(oid: String): Long? = this[oid]?.replace(Regex("[^0-9\\-]"), "")?.toLongOrNull()
private fun Map<String, String>.double(oid: String): Double? = this[oid]?.replace(Regex("[^0-9.\\-]"), "")?.toDoubleOrNull()

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
        bps >= 1_000_000_000 -> "1Gbps"
        bps >= 100_000_000 -> "100Mbps"
        bps >= 10_000_000 -> "10Mbps"
        else -> null
    }
}

internal fun formatSpeed(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    val lower = raw.lowercase()
    return when {
        "gbps" in lower || lower.contains("1000") || lower == "1g" -> "1Gbps"
        "100mb" in lower -> "100Mbps"
        else -> raw.replace("-full", "", ignoreCase = true).trim().ifBlank { null }
    }
}

private fun airmaxRateMbps(raw: Double?): Int? {
    if (raw == null) return null
    val mbps = if (raw > 2000) raw / 1000.0 else raw
    return mbps.toInt().takeIf { it in 1..10_000 }
}

private fun arabicMode(mode: String?): String? = when (mode?.lowercase()) {
    null, "" -> null
    "ap-bridge", "ap", "access-point" -> "سكتر AP"
    "station-bridge", "station", "sta" -> "محطة"
    "bridge" -> "جسر"
    "wds" -> "WDS"
    else -> mode
}

private fun mimosaMode(code: Int?): String? = when (code) {
    1 -> "سكتر AP"
    2 -> "محطة"
    else -> null
}

private fun ticksToUptime(ticks: String?): String? {
    val t = ticks?.toLongOrNull() ?: return ticks
    val sec = t / 100
    val d = sec / 86400
    val h = (sec % 86400) / 3600
    val m = (sec % 3600) / 60
    return if (d > 0) "${d}ي ${h}س ${m}د" else "${h}س ${m}د"
}
