package com.noc.monitor.protocol

import org.json.JSONObject

/**
 * Live radio/router telemetry. Null means the device did not return that field.
 * The UI must show "—" / "غير متوفر" — never invent a number.
 */
data class RadioSnapshot(
    val online: Boolean,
    val identity: String? = null,
    val ip: String,
    val mac: String? = null,
    val ssid: String? = null,
    val mode: String? = null,
    val state: String? = null,
    val firmware: String? = null,
    val cpuPercent: Int? = null,
    val ramPercent: Int? = null,
    val ccq: Int? = null,
    val txCcq: Int? = null,
    val rxCcq: Int? = null,
    val frequencyMhz: Int? = null,
    val channelWidthMhz: Int? = null,
    val uptime: String? = null,
    val temperatureC: Double? = null,
    val ethernetSpeed: String? = null,
    val rxMbps: Double? = null,
    val txMbps: Double? = null,
    val rxBytes: Long? = null,
    val txBytes: Long? = null,
    val voltage: Double? = null,
    val clients: Int? = null,
    val signalDbm: Double? = null,
    val txPowerDbm: Double? = null,
    val snr: Int? = null,
    val txErrorPercent: Double? = null,
    val rxErrorPercent: Double? = null,
    val interferenceDbm: Double? = null,
    val capacityTxMbps: Int? = null,
    val capacityRxMbps: Int? = null,
    val scanList: String? = null,
    val lastError: String? = null,
    val collectedAtMs: Long = System.currentTimeMillis(),
) {
    fun toJson(): String {
        val o = JSONObject()
        o.put("online", online)
        o.put("ip", ip)
        fun put(k: String, v: Any?) { if (v != null) o.put(k, v) }
        put("identity", identity)
        put("mac", mac)
        put("ssid", ssid)
        put("mode", mode)
        put("state", state)
        put("firmware", firmware)
        put("cpuPercent", cpuPercent)
        put("ramPercent", ramPercent)
        put("ccq", ccq)
        put("txCcq", txCcq)
        put("rxCcq", rxCcq)
        put("frequencyMhz", frequencyMhz)
        put("channelWidthMhz", channelWidthMhz)
        put("uptime", uptime)
        put("temperatureC", temperatureC)
        put("ethernetSpeed", ethernetSpeed)
        put("rxMbps", rxMbps)
        put("txMbps", txMbps)
        put("rxBytes", rxBytes)
        put("txBytes", txBytes)
        put("voltage", voltage)
        put("clients", clients)
        put("signalDbm", signalDbm)
        put("txPowerDbm", txPowerDbm)
        put("snr", snr)
        put("txErrorPercent", txErrorPercent)
        put("rxErrorPercent", rxErrorPercent)
        put("interferenceDbm", interferenceDbm)
        put("capacityTxMbps", capacityTxMbps)
        put("capacityRxMbps", capacityRxMbps)
        put("scanList", scanList)
        put("lastError", lastError)
        o.put("collectedAtMs", collectedAtMs)
        return o.toString()
    }

    companion object {
        fun fromJson(raw: String?): RadioSnapshot? {
            if (raw.isNullOrBlank()) return null
            return try {
                val o = JSONObject(raw)
                fun s(k: String) = o.optString(k).takeIf { o.has(k) && it.isNotBlank() && it != "null" }
                fun i(k: String) = if (o.has(k) && !o.isNull(k)) o.optInt(k) else null
                fun d(k: String) = if (o.has(k) && !o.isNull(k)) o.optDouble(k) else null
                fun l(k: String) = if (o.has(k) && !o.isNull(k)) o.optLong(k) else null
                RadioSnapshot(
                    online = o.optBoolean("online"),
                    identity = s("identity"),
                    ip = o.optString("ip"),
                    mac = s("mac"),
                    ssid = s("ssid"),
                    mode = s("mode"),
                    state = s("state"),
                    firmware = s("firmware"),
                    cpuPercent = i("cpuPercent"),
                    ramPercent = i("ramPercent"),
                    ccq = i("ccq"),
                    txCcq = i("txCcq"),
                    rxCcq = i("rxCcq"),
                    frequencyMhz = i("frequencyMhz"),
                    channelWidthMhz = i("channelWidthMhz"),
                    uptime = s("uptime"),
                    temperatureC = d("temperatureC"),
                    ethernetSpeed = s("ethernetSpeed"),
                    rxMbps = d("rxMbps"),
                    txMbps = d("txMbps"),
                    rxBytes = l("rxBytes"),
                    txBytes = l("txBytes"),
                    voltage = d("voltage"),
                    clients = i("clients"),
                    signalDbm = d("signalDbm"),
                    txPowerDbm = d("txPowerDbm"),
                    snr = i("snr"),
                    txErrorPercent = d("txErrorPercent"),
                    rxErrorPercent = d("rxErrorPercent"),
                    interferenceDbm = d("interferenceDbm"),
                    capacityTxMbps = i("capacityTxMbps"),
                    capacityRxMbps = i("capacityRxMbps"),
                    scanList = s("scanList"),
                    lastError = s("lastError"),
                    collectedAtMs = o.optLong("collectedAtMs"),
                )
            } catch (_: Throwable) {
                null
            }
        }
    }
}

interface RadioProbe {
    suspend fun poll(): NocResult<RadioSnapshot>
    fun close()
}
