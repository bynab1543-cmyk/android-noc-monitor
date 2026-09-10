package com.noc.monitor.protocol.radio

import com.noc.monitor.protocol.RadioSnapshot
import org.json.JSONArray
import org.json.JSONObject

/**
 * Parses Ubiquiti airOS /status.cgi JSON into a radio snapshot.
 * Missing keys stay null — the UI shows "—" instead of inventing values.
 */
object AirOsStatusParser {
    fun toSnapshot(hostIp: String, status: JSONObject): RadioSnapshot {
        val host = status.optJSONObject("host") ?: status
        val wireless = status.optJSONObject("wireless") ?: status.optJSONObject("sta") ?: JSONObject()
        val lan = status.optJSONObject("lan") ?: JSONObject()
        val ifaces = collectInterfaces(status)
        val primary = ifaces.maxByOrNull { it.rx + it.tx } ?: ifaces.firstOrNull()
        val cpuRaw = number(host, "cpuload", "cpu")
        val cpu = cpuRaw?.let { if (it <= 1.0) (it * 100.0).toInt() else it.toInt() }
        val memTotal = long(host, "memtotal", "totalram", "ram")
        val memFree = long(host, "memfree", "freeram")
        val ram = if (memTotal != null && memFree != null && memTotal > 0) {
            (((memTotal - memFree).toDouble() / memTotal) * 100.0).toInt().coerceIn(0, 100)
        } else percent(host, "memusage", "ram")
        val ccqRaw = number(wireless, "ccq", "txccq")
        val ccq = ccqRaw?.let { v ->
            when {
                v > 1000 -> (v / 100.0).toInt()
                v > 100 -> 100
                else -> v.toInt()
            }
        }
        val freq = number(wireless, "frequency", "freq")?.toInt()
            ?: number(status, "frequency")?.toInt()
        val width = number(wireless, "chanbw", "channelwidth", "chwidth")?.toInt()
        val signal = number(wireless, "signal", "signalstrength", "rssi")
        val noise = number(wireless, "noisef", "noise", "interference")
        val clients = int(wireless, "count", "sta_count", "stations")
        val txRate = number(wireless, "txrate", "tx_rate")
        val rxRate = number(wireless, "rxrate", "rx_rate")
        val mode = text(wireless, "mode", "opmode") ?: text(host, "netmode", "netrole")
        val ssid = text(wireless, "essid", "ssid", "apmac")
        val eth = ethernetSpeed(lan, primary, ifaces)
        val uptime = formatUptime(host.opt("uptime"))
        val temp = number(host, "temperature", "temp")
        val voltage = number(host, "voltage", "vin")
        return RadioSnapshot(
            online = true,
            identity = text(host, "hostname", "hostname_custom") ?: text(status, "hostname"),
            ip = hostIp,
            mac = primary?.mac ?: text(wireless, "apmac", "mac"),
            ssid = ssid,
            mode = mode,
            state = if (primary?.up == false) "offline" else "running",
            firmware = text(host, "fwversion", "fw_version", "version"),
            cpuPercent = cpu,
            ramPercent = ram,
            ccq = ccq,
            txCcq = ccq,
            rxCcq = number(wireless, "rxccq")?.toInt() ?: ccq,
            frequencyMhz = freq,
            channelWidthMhz = width,
            uptime = uptime,
            temperatureC = temp,
            ethernetSpeed = eth,
            rxBytes = primary?.rx,
            txBytes = primary?.tx,
            voltage = voltage,
            clients = clients,
            signalDbm = signal,
            txPowerDbm = number(wireless, "txpower", "tx_power"),
            snr = number(wireless, "snr")?.toInt(),
            interferenceDbm = noise,
            capacityTxMbps = txRate?.toInt(),
            capacityRxMbps = rxRate?.toInt(),
            scanList = text(wireless, "scan_list", "scanlist"),
        )
    }

    private data class Iface(val name: String, val mac: String?, val up: Boolean, val rx: Long, val tx: Long, val speed: String?)

    private fun collectInterfaces(status: JSONObject): List<Iface> {
        val out = ArrayList<Iface>()
        val obj = status.optJSONObject("ifaces") ?: status.optJSONObject("interfaces")
        if (obj != null && obj !is JSONArray) {
            val keys = obj.keys()
            while (keys.hasNext()) {
                val name = keys.next()
                val row = obj.optJSONObject(name) ?: continue
                out += ifaceFrom(name, row)
            }
        }
        val arr = status.optJSONArray("interfaces") ?: status.optJSONArray("ifaces")
        if (arr is JSONArray) {
            for (i in 0 until arr.length()) {
                val row = arr.optJSONObject(i) ?: continue
                out += ifaceFrom(row.optString("ifname").ifBlank { "eth$i" }, row)
            }
        }
        return out
    }

    private fun ifaceFrom(name: String, row: JSONObject): Iface {
        val stats = row.optJSONObject("stats") ?: JSONObject()
        val statusText = text(row, "status", "speed")
        return Iface(
            name = name,
            mac = text(row, "mac", "hwaddr", "hwaddr0"),
            up = row.optBoolean("status", true) || statusText?.contains("Mbps", true) == true,
            rx = stats.optLong("rx_bytes", row.optLong("rx_bytes")),
            tx = stats.optLong("tx_bytes", row.optLong("tx_bytes")),
            speed = ethernetFromStatus(statusText),
        )
    }

    private fun ethernetSpeed(lan: JSONObject, primary: Iface?, ifaces: List<Iface>): String? {
        return ethernetFromStatus(text(lan, "status", "speed"))
            ?: primary?.speed
            ?: ifaces.firstNotNullOfOrNull { it.speed }
    }

    private fun ethernetFromStatus(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val m = Regex("(\\d+)\\s*[GMK]?bps", RegexOption.IGNORE_CASE).find(raw)
        val n = m?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return raw
        return when {
            raw.contains("G", true) && n < 100 -> "${n * 1000}Mbps"
            else -> "${n}Mbps"
        }
    }

    private fun formatUptime(raw: Any?): String? {
        if (raw == null || raw == JSONObject.NULL) return null
        val seconds = when (raw) {
            is Number -> raw.toLong()
            else -> raw.toString().toLongOrNull() ?: return raw.toString().ifBlank { null }
        }
        val d = seconds / 86400
        val h = (seconds % 86400) / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return buildString {
            if (d > 0) append("${d}d")
            if (h > 0 || d > 0) append("${h}h")
            append("${m}m")
            if (d == 0L && h == 0L) append("${s}s")
        }
    }

    private fun text(o: JSONObject, vararg keys: String): String? {
        for (k in keys) {
            if (!o.has(k) || o.isNull(k)) continue
            val v = o.opt(k)?.toString()?.trim().orEmpty()
            if (v.isNotEmpty() && v != "null") return v
        }
        return null
    }

    private fun number(o: JSONObject, vararg keys: String): Double? {
        for (k in keys) {
            if (!o.has(k) || o.isNull(k)) continue
            val v = o.opt(k)
            when (v) {
                is Number -> return v.toDouble()
                else -> v?.toString()?.replace(Regex("[^0-9.\\-]"), "")?.toDoubleOrNull()?.let { return it }
            }
        }
        return null
    }

    private fun long(o: JSONObject, vararg keys: String): Long? = number(o, *keys)?.toLong()
    private fun int(o: JSONObject, vararg keys: String): Int? = number(o, *keys)?.toInt()
    private fun percent(o: JSONObject, vararg keys: String): Int? = number(o, *keys)?.toInt()
}
