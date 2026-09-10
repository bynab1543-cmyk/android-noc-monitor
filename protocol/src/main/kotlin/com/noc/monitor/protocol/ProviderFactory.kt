package com.noc.monitor.protocol

import com.noc.monitor.protocol.routeros.MikroTikProvider
import com.noc.monitor.protocol.ubiquiti.UbiquitiProvider

fun createDeviceProvider(
    config: DeviceConnectionConfig,
    passwordProvider: () -> CharArray,
): DeviceProvider {
    return when (config.vendor) {
        Vendor.MIKROTIK -> MikroTikProvider(config, passwordProvider)
        Vendor.UBIQUITI -> UbiquitiProvider(config, passwordProvider)
        Vendor.MIMOSA -> MikroTikProvider(config, passwordProvider)
    }
}

object SafeLog {
    private val secretKeys = listOf("password", "passwd", "pwd", "secret", "authorization", "cookie")

    fun redact(text: String?): String {
        if (text.isNullOrBlank()) return ""
        var out: String = text
        for (key in secretKeys) {
            val pattern = Regex("($key\\s*[=:]\\s*)([^\\s&,;]+)", RegexOption.IGNORE_CASE)
            out = pattern.replace(out) { match ->
                match.groupValues[1] + "***"
            }
        }
        out = Regex("Basic\\s+[A-Za-z0-9+/=]+").replace(out, "Basic ***")
        return out
    }
}
