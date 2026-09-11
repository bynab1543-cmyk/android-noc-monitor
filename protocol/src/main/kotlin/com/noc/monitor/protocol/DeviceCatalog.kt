package com.noc.monitor.protocol

/**
 * Catalog of supported radio/router models. Adding a model later is a data
 * entry here — the UI reads this list instead of hard-coding screens.
 */
enum class ProbeKind {
    ROUTEROS_API,
    AIROS_HTTP,
    SNMP,
}

enum class RadioFamily {
    MIKROTIK,
    AIRMAX,
    AIRFIBER,
    UBNT_AC,
    MIMOSA,
}

enum class CardLayout {
    SECTOR,
    PTP,
}

data class DeviceModelSpec(
    val id: String,
    val label: String,
    val family: RadioFamily,
    val probe: ProbeKind,
    val usesUsernamePassword: Boolean,
    val usesSnmpCommunity: Boolean,
    val defaultPort: Int,
    val layout: CardLayout,
    val isSector: Boolean,
)

object DeviceCatalog {
    val all: List<DeviceModelSpec> = listOf(
        DeviceModelSpec("mikrotik-sector", "سكتر MikroTik", RadioFamily.MIKROTIK, ProbeKind.ROUTEROS_API, true, false, PortPolicy.DEFAULT_API, CardLayout.SECTOR, true),
        DeviceModelSpec("ubnt-airmax-sector", "سكتر Ubiquiti AirMax", RadioFamily.AIRMAX, ProbeKind.AIROS_HTTP, true, false, PortPolicy.DEFAULT_AIROS, CardLayout.SECTOR, true),
        DeviceModelSpec("ubnt-ac-sector", "سكتر Ubiquiti AC", RadioFamily.UBNT_AC, ProbeKind.AIROS_HTTP, true, false, PortPolicy.DEFAULT_AIROS, CardLayout.SECTOR, true),
        DeviceModelSpec("mikrotik-link", "MikroTik Link", RadioFamily.MIKROTIK, ProbeKind.ROUTEROS_API, true, false, PortPolicy.DEFAULT_API, CardLayout.SECTOR, false),
        DeviceModelSpec("lhg60g-link", "LHG60G Link", RadioFamily.MIKROTIK, ProbeKind.ROUTEROS_API, true, false, PortPolicy.DEFAULT_API, CardLayout.PTP, false),
        DeviceModelSpec("airfiber-x", "AirFiber(2,4X,5XHD,11X)", RadioFamily.AIRFIBER, ProbeKind.SNMP, false, true, PortPolicy.DEFAULT_SNMP, CardLayout.PTP, false),
        DeviceModelSpec("airfiber-60", "AirFiber(60-Lr,60)", RadioFamily.AIRFIBER, ProbeKind.SNMP, false, true, PortPolicy.DEFAULT_SNMP, CardLayout.PTP, false),
        DeviceModelSpec("ubnt-ac-link", "Ubiquiti AC Link", RadioFamily.UBNT_AC, ProbeKind.AIROS_HTTP, true, false, PortPolicy.DEFAULT_AIROS, CardLayout.PTP, false),
        DeviceModelSpec("mimosa-c5c", "Mimosa C5c", RadioFamily.MIMOSA, ProbeKind.SNMP, false, true, PortPolicy.DEFAULT_SNMP, CardLayout.PTP, false),
        DeviceModelSpec("ubnt-airmax-link", "Ubiquiti AirMax Link", RadioFamily.AIRMAX, ProbeKind.AIROS_HTTP, true, false, PortPolicy.DEFAULT_AIROS, CardLayout.PTP, false),
        DeviceModelSpec("mimosa-c5x", "Mimosa C5x", RadioFamily.MIMOSA, ProbeKind.SNMP, false, true, PortPolicy.DEFAULT_SNMP, CardLayout.PTP, false),
        DeviceModelSpec("mimosa-b11", "Mimosa B11", RadioFamily.MIMOSA, ProbeKind.SNMP, false, true, PortPolicy.DEFAULT_SNMP, CardLayout.PTP, false),
        DeviceModelSpec("mimosa-b24", "Mimosa B24", RadioFamily.MIMOSA, ProbeKind.SNMP, false, true, PortPolicy.DEFAULT_SNMP, CardLayout.PTP, false),
        DeviceModelSpec("mimosa-b5x", "Mimosa B5X", RadioFamily.MIMOSA, ProbeKind.SNMP, false, true, PortPolicy.DEFAULT_SNMP, CardLayout.PTP, false),
        DeviceModelSpec("mimosa-b5c", "Mimosa B5C", RadioFamily.MIMOSA, ProbeKind.SNMP, false, true, PortPolicy.DEFAULT_SNMP, CardLayout.PTP, false),
        DeviceModelSpec("mimosa-b5", "Mimosa B5", RadioFamily.MIMOSA, ProbeKind.SNMP, false, true, PortPolicy.DEFAULT_SNMP, CardLayout.PTP, false),
    )

    fun byId(id: String): DeviceModelSpec =
        all.firstOrNull { it.id == id } ?: all.first()
}
