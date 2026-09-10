package com.noc.monitor.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.noc.monitor.NocApplication
import com.noc.monitor.data.local.AlertEntity
import com.noc.monitor.data.local.DeviceEntity
import com.noc.monitor.data.repo.NewDeviceRequest
import com.noc.monitor.demo.DEMO_DEVICE_ID
import com.noc.monitor.protocol.ArpEntry
import com.noc.monitor.protocol.CommandOutcome
import com.noc.monitor.protocol.DeviceCapabilities
import com.noc.monitor.protocol.DhcpLease
import com.noc.monitor.protocol.IpAddressEntry
import com.noc.monitor.protocol.LogEntry
import com.noc.monitor.protocol.NetInterface
import com.noc.monitor.protocol.NocResult
import com.noc.monitor.protocol.OperatingMode
import com.noc.monitor.protocol.PppoeSession
import com.noc.monitor.protocol.ProductFamily
import com.noc.monitor.protocol.RouteEntry
import com.noc.monitor.protocol.SystemInfo
import com.noc.monitor.protocol.Transport
import com.noc.monitor.protocol.Vendor
import com.noc.monitor.protocol.traffic.DevicePeak
import com.noc.monitor.protocol.traffic.StoredSample
import com.noc.monitor.protocol.traffic.TrafficWindow
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale

data class DashboardSnapshot(
    val total: Int = 0,
    val online: Int = 0,
    val offline: Int = 0,
    val alerts: Int = 0,
    val totalRxBps: Long = 0,
    val totalTxBps: Long = 0,
    val highest: DevicePeak? = null,
    val highestName: String? = null,
    val devices: List<DeviceEntity> = emptyList(),
    val alertList: List<AlertEntity> = emptyList(),
    val peaks: List<DevicePeak> = emptyList(),
    val mode: OperatingMode = OperatingMode.REAL,
)

data class DeviceDetailState(
    val device: DeviceEntity? = null,
    val capabilities: DeviceCapabilities? = null,
    val system: SystemInfo? = null,
    val interfaces: List<NetInterface> = emptyList(),
    val pppoe: List<PppoeSession> = emptyList(),
    val ips: List<IpAddressEntry> = emptyList(),
    val dhcp: List<DhcpLease> = emptyList(),
    val arp: List<ArpEntry> = emptyList(),
    val routes: List<RouteEntry> = emptyList(),
    val logs: List<LogEntry> = emptyList(),
    val samples: List<StoredSample> = emptyList(),
    val alerts: List<AlertEntity> = emptyList(),
    val lastCommand: CommandOutcome? = null,
    val lastError: String? = null,
    val loading: Boolean = false,
    val window: TrafficWindow = TrafficWindow.FIVE_MINUTES,
)

data class AddDeviceForm(
    val displayName: String = "",
    val host: String = "",
    val port: String = "8728",
    val username: String = "admin",
    val password: String = "",
    val vendor: Vendor = Vendor.MIKROTIK,
    val family: ProductFamily = ProductFamily.MIKROTIK_ROUTEROS,
    val transport: Transport = Transport.API,
    val allowInsecureTls: Boolean = true,
    val testing: Boolean = false,
    val testResult: String? = null,
    val testOk: Boolean? = null,
    val saving: Boolean = false,
)

class MonitorViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as NocApplication
    private val repo = app.repository

    val language = MutableStateFlow("en")
    val mode = MutableStateFlow(OperatingMode.REAL)
    val search = MutableStateFlow("")
    val statusFilter = MutableStateFlow("ALL")
    val addForm = MutableStateFlow(AddDeviceForm())
    val detail = MutableStateFlow(DeviceDetailState())
    val peaks = MutableStateFlow<List<DevicePeak>>(emptyList())

    val devices = repo.observeDevices().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val alerts = repo.observeAlerts().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val dashboard: StateFlow<DashboardSnapshot> = combine(devices, alerts, peaks, mode) { devs, al, pk, md ->
        val visible = if (md == OperatingMode.DEMO) devs.filter { it.id == DEMO_DEVICE_ID }
        else devs.filter { it.id != DEMO_DEVICE_ID }
        val online = visible.count { it.status == "ONLINE" }
        val offline = visible.count { it.status == "OFFLINE" }
        val totalRx = visible.sumOf { it.lastRxBps }
        val totalTx = visible.sumOf { it.lastTxBps }
        val highest = pk.filter { p -> visible.any { it.id == p.deviceId } }.maxByOrNull { it.peakTotalBps }
        DashboardSnapshot(
            total = visible.size,
            online = online,
            offline = offline,
            alerts = al.count { !it.acknowledged && visible.any { d -> d.id == it.deviceId } },
            totalRxBps = totalRx,
            totalTxBps = totalTx,
            highest = highest,
            highestName = highest?.let { h -> visible.firstOrNull { it.id == h.deviceId }?.displayName },
            devices = visible,
            alertList = al.filter { visible.any { d -> d.id == it.deviceId } },
            peaks = pk.filter { p -> visible.any { it.id == p.deviceId } },
            mode = md,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardSnapshot())

    private var detailJob: Job? = null

    init {
        refreshPeaks()
    }

    fun setLanguage(tag: String) {
        language.value = tag
    }

    fun setMode(newMode: OperatingMode) {
        mode.value = newMode
        repo.mode = newMode
        viewModelScope.launch {
            if (newMode == OperatingMode.DEMO) repo.ensureDemoDevice() else repo.removeDemoDevice()
        }
    }

    fun filteredDevices(): List<DeviceEntity> {
        val q = search.value.trim().lowercase(Locale.getDefault())
        val status = statusFilter.value
        val base = dashboard.value.devices
        return base.filter { d ->
            val matchesQuery = q.isEmpty() ||
                d.displayName.lowercase(Locale.getDefault()).contains(q) ||
                d.host.lowercase(Locale.getDefault()).contains(q) ||
                (d.identity?.lowercase(Locale.getDefault())?.contains(q) == true)
            val matchesStatus = status == "ALL" || d.status == status
            matchesQuery && matchesStatus
        }
    }

    fun updateForm(transform: (AddDeviceForm) -> AddDeviceForm) {
        addForm.value = transform(addForm.value)
    }

    fun onVendorChanged(vendor: Vendor) {
        val family = if (vendor == Vendor.MIKROTIK) ProductFamily.MIKROTIK_ROUTEROS else ProductFamily.UBIQUITI_UNIFI
        val transport = if (vendor == Vendor.MIKROTIK) Transport.API else Transport.UNIFI
        val port = when (transport) {
            Transport.API -> "8728"
            Transport.API_SSL -> "8729"
            else -> "443"
        }
        addForm.value = addForm.value.copy(vendor = vendor, family = family, transport = transport, port = port)
    }

    fun onFamilyChanged(family: ProductFamily) {
        val transport = when (family) {
            ProductFamily.MIKROTIK_ROUTEROS -> Transport.API
            ProductFamily.UBIQUITI_UNIFI -> Transport.UNIFI
            ProductFamily.UBIQUITI_EDGEOS -> Transport.EDGEOS
            ProductFamily.UBIQUITI_AIROS -> Transport.AIROS
        }
        val port = if (family == ProductFamily.MIKROTIK_ROUTEROS) "8728" else "443"
        addForm.value = addForm.value.copy(family = family, transport = transport, port = port, vendor = if (family == ProductFamily.MIKROTIK_ROUTEROS) Vendor.MIKROTIK else Vendor.UBIQUITI)
    }

    fun onTransportChanged(transport: Transport) {
        val port = when (transport) {
            Transport.API -> "8728"
            Transport.API_SSL -> "8729"
            Transport.REST, Transport.UNIFI, Transport.EDGEOS, Transport.AIROS -> "443"
        }
        addForm.value = addForm.value.copy(transport = transport, port = port)
    }

    fun testNewConnection() {
        val form = addForm.value
        val port = form.port.toIntOrNull()
        if (port == null) {
            addForm.value = form.copy(testOk = false, testResult = "Invalid port")
            return
        }
        if (port == 9) {
            addForm.value = form.copy(testOk = false, testResult = "Port 9 is not allowed")
            return
        }
        viewModelScope.launch {
            addForm.value = addForm.value.copy(testing = true, testResult = null, testOk = null)
            val request = form.toRequest(port)
            val result = repo.testConnection(request)
            addForm.value = addForm.value.copy(
                testing = false,
                testOk = result.isOk,
                testResult = when (result) {
                    is NocResult.Ok -> "Connected: ${result.value.identity} ${result.value.model ?: ""} ${result.value.version ?: ""}".trim()
                    is NocResult.Err -> result.error.userMessage
                },
            )
        }
    }

    fun saveDevice(onDone: (Boolean, String) -> Unit) {
        val form = addForm.value
        val port = form.port.toIntOrNull()
        if (port == null) {
            onDone(false, "Invalid port")
            return
        }
        viewModelScope.launch {
            addForm.value = addForm.value.copy(saving = true)
            val result = repo.addDevice(form.toRequest(port))
            addForm.value = addForm.value.copy(saving = false)
            when (result) {
                is NocResult.Ok -> {
                    addForm.value = AddDeviceForm()
                    onDone(true, "Device saved")
                }
                is NocResult.Err -> onDone(false, result.error.userMessage)
            }
        }
    }

    fun deleteDevice(id: String) {
        viewModelScope.launch { repo.deleteDevice(id) }
    }

    fun openDevice(id: String) {
        detailJob?.cancel()
        detail.value = DeviceDetailState(loading = true)
        detailJob = viewModelScope.launch {
            val device = repo.allDevices().firstOrNull { it.id == id }
            if (device == null) {
                detail.value = DeviceDetailState(lastError = "Device not found")
                return@launch
            }
            refreshDevice(device)
        }
    }

    fun refreshSelected() {
        val device = detail.value.device ?: return
        viewModelScope.launch { refreshDevice(device) }
    }

    fun setWindow(window: TrafficWindow) {
        val current = detail.value
        detail.value = current.copy(window = window)
        val device = current.device ?: return
        viewModelScope.launch {
            val samples = repo.samples(device.id, window)
            detail.value = detail.value.copy(samples = samples)
        }
    }

    fun disconnectPppoe(session: PppoeSession) = runCommand { p -> p.disconnectPppoe(session.id) }
    fun setInterfaceEnabled(iface: NetInterface, enabled: Boolean) =
        runCommand { p -> p.setInterfaceEnabled(iface.id, enabled) }
    fun reboot() = runCommand { p -> p.reboot() }

    fun ack(id: String) {
        viewModelScope.launch { repo.ackAlert(id) }
    }

    fun refreshPeaks() {
        viewModelScope.launch { peaks.value = repo.allPeaks() }
    }

    private fun runCommand(block: suspend (com.noc.monitor.protocol.DeviceProvider) -> NocResult<CommandOutcome>) {
        val device = detail.value.device ?: return
        viewModelScope.launch {
            detail.value = detail.value.copy(loading = true, lastError = null)
            val result = repo.execute(device, block)
            when (result) {
                is NocResult.Ok -> {
                    detail.value = detail.value.copy(lastCommand = result.value, lastError = null)
                    refreshDevice(device)
                }
                is NocResult.Err -> {
                    detail.value = detail.value.copy(
                        loading = false,
                        lastCommand = CommandOutcome(false, "command", result.error.userMessage),
                        lastError = result.error.userMessage,
                    )
                }
            }
        }
    }

    private suspend fun refreshDevice(device: DeviceEntity) {
        detail.value = detail.value.copy(loading = true, device = device, capabilities = repo.capabilitiesOf(device))
        val snapshot = repo.pollDevice(device)
        val provider = repo.providerFor(device)
        val caps = repo.capabilitiesOf(device)
        val pppoe = if (caps.canReadPppoe) provider.readPppoe().getOrNull().orEmpty() else emptyList()
        val ips = if (caps.canReadIp) provider.readIpAddresses().getOrNull().orEmpty() else emptyList()
        val dhcp = if (caps.canReadDhcp) provider.readDhcpLeases().getOrNull().orEmpty() else emptyList()
        val arp = if (caps.canReadArp) provider.readArp().getOrNull().orEmpty() else emptyList()
        val routes = if (caps.canReadRoutes) provider.readRoutes().getOrNull().orEmpty() else emptyList()
        val logs = if (caps.canReadLogs) provider.readLogs().getOrNull().orEmpty() else emptyList()
        val samples = repo.samples(device.id, detail.value.window)
        val latest = repo.allDevices().firstOrNull { it.id == device.id } ?: device
        val pppoeResult = if (caps.canReadPppoe) provider.readPppoe() else null
        val pppoeError = (pppoeResult as? NocResult.Err)?.error?.userMessage
        refreshPeaks()
        detail.value = DeviceDetailState(
            device = latest,
            capabilities = caps,
            system = snapshot.system,
            interfaces = snapshot.interfaces,
            pppoe = pppoe,
            ips = ips,
            dhcp = dhcp,
            arp = arp,
            routes = routes,
            logs = logs,
            samples = samples,
            alerts = alerts.value.filter { it.deviceId == device.id },
            lastCommand = detail.value.lastCommand,
            lastError = snapshot.result.errorOrNull()?.userMessage ?: pppoeError,
            loading = false,
            window = detail.value.window,
        )
    }

    private fun AddDeviceForm.toRequest(port: Int) = NewDeviceRequest(
        displayName = displayName,
        host = host,
        port = port,
        username = username,
        password = password.toCharArray(),
        vendor = vendor,
        productFamily = family,
        transport = transport,
        allowInsecureTls = allowInsecureTls,
    )
}
