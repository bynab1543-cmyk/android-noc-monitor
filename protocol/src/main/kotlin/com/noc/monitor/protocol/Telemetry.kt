package com.noc.monitor.protocol

import kotlin.math.log10

/**
 * Normalize telemetry so the UI never shows a raw SNMP integer as dBm, °C, or %.
 * Null means "unknown" — the UI must render "—".
 */
object Telemetry {
    fun temperatureC(raw: Double?): Double? {
        if (raw == null) return null
        val scaled = if (raw in 120.0..1200.0) raw / 10.0 else raw
        return scaled.takeIf { it in 1.0..120.0 }
    }

    fun powerDbm(raw: Double?): Double? {
        if (raw == null || raw == -8.0) return null
        val v = if (raw > 36.0 || raw < -36.0) raw / 10.0 else raw
        return v.takeIf { it in -20.0..36.0 }
    }

    fun signalDbm(raw: Double?): Double? {
        if (raw == null) return null
        var v = raw
        if (v in 10.0..120.0) v = -v
        if (kotlin.math.abs(v) > 200) v /= 10.0
        return v.takeIf { it in -120.0..-5.0 }
    }

    fun ccq(raw: Double?): Int? {
        if (raw == null) return null
        val v = when {
            raw > 1000 -> raw / 100.0
            else -> raw
        }
        return v.toInt().takeIf { it in 0..100 }
    }

    fun snr(raw: Double?): Int? {
        if (raw == null) return null
        val v = if (raw in 80.0..800.0) raw / 10.0 else raw
        return v.toInt().takeIf { it in 1..80 }
    }

    fun mhz(raw: Double?): Int? {
        if (raw == null) return null
        val v = when {
            raw > 100_000 -> raw / 1000.0
            else -> raw
        }
        return v.toInt().takeIf { it in 400..80_000 }
    }

    fun channelWidthMhz(raw: Double?): Int? =
        raw?.toInt()?.takeIf { it in 1..320 }

    fun phyKbpsToMbps(raw: Double?): Double? {
        if (raw == null) return null
        val kbps = raw / 100.0
        val mbps = kbps / 1000.0
        return mbps.takeIf { it in 0.0..20_000.0 }
    }

    fun perPercent(raw: Double?): Double? {
        if (raw == null) return null
        val v = raw / 100.0
        return v.takeIf { it in 0.0..100.0 }
    }

    fun voltage(raw: Double?): Double? {
        if (raw == null) return null
        val v = if (raw > 80) raw / 10.0 else raw
        return v.takeIf { it in 5.0..80.0 }
    }

    fun mwToDbm(mw: Double): Double? {
        if (mw <= 0) return null
        return (10.0 * log10(mw)).takeIf { it in -50.0..50.0 }
    }
}
