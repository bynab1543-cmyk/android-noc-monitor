package com.noc.monitor.protocol.traffic

import com.noc.monitor.protocol.TrafficCounters
import com.noc.monitor.protocol.TrafficRate
import java.util.concurrent.ConcurrentHashMap

enum class TrafficWindow(val durationMs: Long, val minIntervalMs: Long) {
    FIVE_MINUTES(5 * 60_000L, 1_000L),
    ONE_HOUR(60 * 60_000L, 15_000L),
    SIX_HOURS(6 * 60 * 60_000L, 60_000L),
    TWENTY_FOUR_HOURS(24 * 60 * 60_000L, 5 * 60_000L),
}

data class StoredSample(
    val deviceId: String,
    val interfaceName: String,
    val timestampMs: Long,
    val rxBytes: Long,
    val txBytes: Long,
    val rxPackets: Long,
    val txPackets: Long,
    val rxBps: Long,
    val txBps: Long,
) {
    val totalBps: Long get() = rxBps + txBps
}

data class DevicePeak(
    val deviceId: String,
    val interfaceName: String,
    val peakRxBps: Long,
    val peakTxBps: Long,
    val peakTotalBps: Long,
    val peakAtMs: Long,
)

interface TrafficRepository {
    fun record(deviceId: String, counters: List<TrafficCounters>): List<TrafficRate>
    fun samples(deviceId: String, window: TrafficWindow, interfaceName: String? = null): List<StoredSample>
    fun peak(deviceId: String): DevicePeak?
    fun allPeaks(): List<DevicePeak>
    fun prune()
    fun clear(deviceId: String)
}

/**
 * In-memory traffic history with windowed retention.
 * Android wraps this with a Room-backed implementation that shares the same contract.
 */
class InMemoryTrafficRepository(
    private val clock: () -> Long = { System.currentTimeMillis() },
) : TrafficRepository {
    private val samples = ConcurrentHashMap<String, MutableList<StoredSample>>()
    private val lastCounters = ConcurrentHashMap<String, TrafficCounters>()
    private val peaks = ConcurrentHashMap<String, DevicePeak>()

    override fun record(deviceId: String, counters: List<TrafficCounters>): List<TrafficRate> {
        val now = clock()
        val rates = ArrayList<TrafficRate>(counters.size)
        val bucket = samples.getOrPut(deviceId) { mutableListOf() }
        synchronized(bucket) {
            for (c in counters) {
                val key = "$deviceId|${c.interfaceName}"
                val prev = lastCounters[key]
                val dt = if (prev == null) 0.0 else ((c.collectedAtMs - prev.collectedAtMs).coerceAtLeast(1)).toDouble() / 1000.0
                val rxBps: Long
                val txBps: Long
                val rxPps: Long
                val txPps: Long
                if (prev == null || dt <= 0) {
                    rxBps = 0; txBps = 0; rxPps = 0; txPps = 0
                } else {
                    val drx = (c.rxBytes - prev.rxBytes).coerceAtLeast(0)
                    val dtx = (c.txBytes - prev.txBytes).coerceAtLeast(0)
                    val drxp = (c.rxPackets - prev.rxPackets).coerceAtLeast(0)
                    val dtxp = (c.txPackets - prev.txPackets).coerceAtLeast(0)
                    rxBps = ((drx * 8.0) / dt).toLong()
                    txBps = ((dtx * 8.0) / dt).toLong()
                    rxPps = (drxp / dt).toLong()
                    txPps = (dtxp / dt).toLong()
                }
                lastCounters[key] = c
                val rate = TrafficRate(
                    interfaceName = c.interfaceName,
                    rxBps = rxBps,
                    txBps = txBps,
                    rxPps = rxPps,
                    txPps = txPps,
                    rxBytes = c.rxBytes,
                    txBytes = c.txBytes,
                    rxPackets = c.rxPackets,
                    txPackets = c.txPackets,
                    collectedAtMs = now,
                )
                rates += rate
                if (prev != null) {
                    val sample = StoredSample(
                        deviceId = deviceId,
                        interfaceName = c.interfaceName,
                        timestampMs = now,
                        rxBytes = c.rxBytes,
                        txBytes = c.txBytes,
                        rxPackets = c.rxPackets,
                        txPackets = c.txPackets,
                        rxBps = rxBps,
                        txBps = txBps,
                    )
                    bucket += sample
                    val total = sample.totalBps
                    val existing = peaks[deviceId]
                    if (existing == null || total > existing.peakTotalBps) {
                        peaks[deviceId] = DevicePeak(
                            deviceId = deviceId,
                            interfaceName = c.interfaceName,
                            peakRxBps = rxBps,
                            peakTxBps = txBps,
                            peakTotalBps = total,
                            peakAtMs = now,
                        )
                    }
                }
            }
        }
        prune()
        return rates
    }

    override fun samples(deviceId: String, window: TrafficWindow, interfaceName: String?): List<StoredSample> {
        val now = clock()
        val cutoff = now - window.durationMs
        val bucket = samples[deviceId] ?: return emptyList()
        synchronized(bucket) {
            val filtered = bucket.filter {
                it.timestampMs >= cutoff && (interfaceName == null || it.interfaceName == interfaceName)
            }
            return downsample(filtered, window.minIntervalMs)
        }
    }

    override fun peak(deviceId: String): DevicePeak? = peaks[deviceId]

    override fun allPeaks(): List<DevicePeak> = peaks.values.toList()

    override fun prune() {
        val now = clock()
        val keepMs = TrafficWindow.TWENTY_FOUR_HOURS.durationMs
        for ((_, bucket) in samples) {
            synchronized(bucket) {
                bucket.removeAll { now - it.timestampMs > keepMs }
                compact(bucket, now)
            }
        }
    }

    override fun clear(deviceId: String) {
        samples.remove(deviceId)
        peaks.remove(deviceId)
        lastCounters.keys.removeAll { it.startsWith("$deviceId|") }
    }

    private fun compact(bucket: MutableList<StoredSample>, now: Long) {
        if (bucket.isEmpty()) return
        val keep = ArrayList<StoredSample>(bucket.size)
        var lastKept = HashMap<String, Long>()
        for (s in bucket.sortedBy { it.timestampMs }) {
            val age = now - s.timestampMs
            val minInterval = when {
                age <= TrafficWindow.FIVE_MINUTES.durationMs -> TrafficWindow.FIVE_MINUTES.minIntervalMs
                age <= TrafficWindow.ONE_HOUR.durationMs -> TrafficWindow.ONE_HOUR.minIntervalMs
                age <= TrafficWindow.SIX_HOURS.durationMs -> TrafficWindow.SIX_HOURS.minIntervalMs
                else -> TrafficWindow.TWENTY_FOUR_HOURS.minIntervalMs
            }
            val key = s.interfaceName
            val last = lastKept[key] ?: 0L
            if (s.timestampMs - last >= minInterval || last == 0L) {
                keep += s
                lastKept[key] = s.timestampMs
            }
        }
        bucket.clear()
        bucket.addAll(keep)
    }

    private fun downsample(list: List<StoredSample>, minIntervalMs: Long): List<StoredSample> {
        if (list.isEmpty()) return emptyList()
        val out = ArrayList<StoredSample>()
        var last = HashMap<String, Long>()
        for (s in list.sortedBy { it.timestampMs }) {
            val prev = last[s.interfaceName] ?: 0L
            if (s.timestampMs - prev >= minIntervalMs || prev == 0L) {
                out += s
                last[s.interfaceName] = s.timestampMs
            }
        }
        return out
    }
}

object TrafficFormat {
    fun bps(bits: Long): String {
        if (bits < 1000) return "$bits bps"
        val kb = bits / 1000.0
        if (kb < 1000) return "%.1f kbps".format(kb)
        val mb = kb / 1000.0
        if (mb < 1000) return "%.2f Mbps".format(mb)
        return "%.2f Gbps".format(mb / 1000.0)
    }

    fun bytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.1f KiB".format(kb)
        val mb = kb / 1024.0
        if (mb < 1024) return "%.2f MiB".format(mb)
        return "%.2f GiB".format(mb / 1024.0)
    }
}
