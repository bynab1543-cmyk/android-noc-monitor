package com.noc.monitor.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noc.monitor.protocol.NetInterface
import com.noc.monitor.protocol.PppoeSession
import com.noc.monitor.protocol.ProductFamily
import com.noc.monitor.protocol.Transport
import com.noc.monitor.protocol.Vendor
import com.noc.monitor.protocol.traffic.TrafficFormat
import com.noc.monitor.protocol.traffic.TrafficWindow
import com.noc.monitor.ui.components.Kv
import com.noc.monitor.ui.components.NocCard
import com.noc.monitor.ui.components.StatusDot
import com.noc.monitor.ui.components.TrafficGraph
import com.noc.monitor.ui.theme.Accent
import com.noc.monitor.ui.theme.Bg
import com.noc.monitor.ui.theme.Offline
import com.noc.monitor.ui.theme.Online
import com.noc.monitor.ui.theme.TextMain
import com.noc.monitor.ui.theme.TextMute
import com.noc.monitor.ui.theme.Warning

private enum class DetailTab { Overview, Interfaces, Traffic, PPPoE, IP, DHCP, ARP, Routes, Alerts, Logs, Actions }

@Composable
fun AddDeviceFormScreen(vm: MonitorViewModel, copy: Copy, onClose: () -> Unit) {
    val form by vm.addForm.collectAsState()
    val scroll = rememberScrollState()
    Column(
        Modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(copy.addDevice, color = TextMain, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            TextButton(onClick = onClose) { Text(copy.cancel, color = TextMute) }
        }
        Field(copy.displayName, form.displayName) { vm.updateForm { f -> f.copy(displayName = it) } }
        Field(copy.host, form.host) { vm.updateForm { f -> f.copy(host = it) } }
        Field(copy.port, form.port) { vm.updateForm { f -> f.copy(port = it.filter { ch -> ch.isDigit() }) } }
        Field(copy.username, form.username) { vm.updateForm { f -> f.copy(username = it) } }
        OutlinedTextField(
            form.password,
            { vm.updateForm { f -> f.copy(password = it) } },
            label = { Text(copy.password) },
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation(),
            colors = fieldColors(),
            singleLine = true,
        )
        Text(copy.vendor, color = TextMute, fontSize = 12.sp)
        ChipRow(
            listOf(Vendor.MIKROTIK.name to "MikroTik", Vendor.UBIQUITI.name to "Ubiquiti"),
            form.vendor.name,
        ) { vm.onVendorChanged(Vendor.valueOf(it)) }
        if (form.vendor == Vendor.UBIQUITI) {
            Text(copy.family, color = TextMute, fontSize = 12.sp)
            ChipRow(
                listOf(
                    ProductFamily.UBIQUITI_UNIFI.name to "UniFi",
                    ProductFamily.UBIQUITI_EDGEOS.name to "EdgeOS",
                    ProductFamily.UBIQUITI_AIROS.name to "airOS",
                ),
                form.family.name,
            ) { vm.onFamilyChanged(ProductFamily.valueOf(it)) }
        }
        if (form.vendor == Vendor.MIKROTIK) {
            Text(copy.transport, color = TextMute, fontSize = 12.sp)
            ChipRow(
                listOf(
                    Transport.API.name to "API 8728",
                    Transport.API_SSL.name to "API-SSL 8729",
                    Transport.REST.name to "REST HTTPS",
                ),
                form.transport.name,
            ) { vm.onTransportChanged(Transport.valueOf(it)) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(form.allowInsecureTls, { vm.updateForm { f -> f.copy(allowInsecureTls = it) } })
            Text(copy.allowInsecure, color = TextMain, fontSize = 13.sp)
        }
        Button(
            onClick = { vm.testNewConnection() },
            enabled = !form.testing && form.host.isNotBlank() && form.username.isNotBlank(),
            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Bg),
        ) {
            if (form.testing) CircularProgressIndicator(Modifier.size(18.dp), color = Bg, strokeWidth = 2.dp)
            else Text(copy.testConnection)
        }
        form.testResult?.let {
            Text(it, color = if (form.testOk == true) Online else Offline, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        }
        Button(
            onClick = {
                vm.saveDevice { ok, msg ->
                    if (ok) onClose()
                    else vm.updateForm { f -> f.copy(testResult = msg, testOk = false) }
                }
            },
            enabled = !form.saving && form.host.isNotBlank(),
            colors = ButtonDefaults.buttonColors(containerColor = Online, contentColor = Bg),
        ) { Text(copy.save) }
    }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, label = { Text(label) }, modifier = Modifier.fillMaxWidth(), singleLine = true, colors = fieldColors())
}

@Composable
private fun ChipRow(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (key, label) ->
            FilterChip(
                selected = selected == key,
                onClick = { onSelect(key) },
                label = { Text(label) },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Accent, selectedLabelColor = Bg),
            )
        }
    }
}

@Composable
fun DeviceDetailScreen(vm: MonitorViewModel, copy: Copy, deviceId: String, onBack: () -> Unit) {
    val state by vm.detail.collectAsState()
    var tab by remember { mutableStateOf(DetailTab.Overview) }
    var pendingToggle by remember { mutableStateOf<Pair<NetInterface, Boolean>?>(null) }
    var pendingPppoe by remember { mutableStateOf<PppoeSession?>(null) }
    var rebootStep by remember { mutableStateOf(0) }

    LaunchedEffect(deviceId) { vm.openDevice(deviceId) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("←", color = Accent) }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusDot(state.device?.status ?: "UNKNOWN")
                    Text(state.device?.displayName ?: "", color = TextMain, fontWeight = FontWeight.SemiBold)
                }
                Text("${state.device?.host}:${state.device?.port}", color = TextMute, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            }
            TextButton(onClick = { vm.refreshSelected() }) { Text(copy.refresh, color = Accent) }
        }
        state.lastError?.let { Text(it, color = Offline, fontSize = 12.sp, modifier = Modifier.padding(vertical = 4.dp)) }
        state.lastCommand?.let {
            Text("${copy.lastResult}: ${it.display}", color = if (it.success) Online else Offline, fontSize = 12.sp)
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DetailTab.entries.forEach { t ->
                val label = when (t) {
                    DetailTab.Overview -> copy.overview
                    DetailTab.Interfaces -> copy.interfaces
                    DetailTab.Traffic -> copy.traffic
                    DetailTab.PPPoE -> copy.pppoe
                    DetailTab.IP -> copy.ip
                    DetailTab.DHCP -> copy.dhcp
                    DetailTab.ARP -> copy.arp
                    DetailTab.Routes -> copy.routes
                    DetailTab.Alerts -> copy.alerts
                    DetailTab.Logs -> copy.logs
                    DetailTab.Actions -> copy.actions
                }
                FilterChip(
                    selected = tab == t,
                    onClick = { tab = t },
                    label = { Text(label, fontSize = 12.sp) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Accent, selectedLabelColor = Bg),
                )
            }
        }
        val caps = state.capabilities
        val scroll = rememberScrollState()
        Column(Modifier.weight(1f).verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.loading) {
                CircularProgressIndicator(color = Accent, modifier = Modifier.padding(16.dp))
            }
            when (tab) {
                DetailTab.Overview -> {
                    val s = state.system
                    NocCard {
                        Column {
                            Kv(copy.identity, s?.identity)
                            Kv(copy.model, s?.model ?: s?.boardName)
                            Kv(copy.version, s?.version)
                            Kv(copy.uptime, s?.uptime)
                            Kv(copy.cpu, s?.cpuLoadPercent?.let { "$it%" })
                            Kv(copy.ram, s?.memoryUsedPercent?.let { "$it% (${TrafficFormat.bytes(s.memoryFreeBytes ?: 0)} free)" })
                            Kv(copy.storage, s?.storageFreeBytes?.let { TrafficFormat.bytes(it) + " free" })
                            Kv(copy.temperature, s?.temperatureC?.let { "$it °C" })
                        }
                    }
                }
                DetailTab.Interfaces -> {
                    if (caps?.canReadInterfaces != true) Text(copy.unsupported, color = Warning)
                    else state.interfaces.forEach { iface ->
                        NocCard {
                            Column {
                                Text(iface.name, color = TextMain, fontWeight = FontWeight.SemiBold)
                                Kv("Type", iface.type)
                                Kv(copy.running, if (iface.running) copy.running else copy.offline)
                                Kv(copy.enabled, if (iface.enabled) copy.enabled else copy.disabled)
                                Kv(copy.rx, "${TrafficFormat.bytes(iface.rxBytes)} / ${iface.rxPackets} ${copy.packets}")
                                Kv(copy.tx, "${TrafficFormat.bytes(iface.txBytes)} / ${iface.txPackets} ${copy.packets}")
                            }
                        }
                    }
                }
                DetailTab.Traffic -> {
                    if (caps?.canReadTraffic != true) Text(copy.unsupported, color = Warning)
                    else {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(
                                TrafficWindow.FIVE_MINUTES to copy.fiveMin,
                                TrafficWindow.ONE_HOUR to copy.oneHour,
                                TrafficWindow.SIX_HOURS to copy.sixHours,
                                TrafficWindow.TWENTY_FOUR_HOURS to copy.twentyFour,
                            ).forEach { (w, label) ->
                                FilterChip(
                                    selected = state.window == w,
                                    onClick = { vm.setWindow(w) },
                                    label = { Text(label) },
                                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Accent, selectedLabelColor = Bg),
                                )
                            }
                        }
                        NocCard {
                            if (state.samples.isEmpty()) Text(copy.noTraffic, color = TextMute)
                            else TrafficGraph(state.samples)
                        }
                    }
                }
                DetailTab.PPPoE -> {
                    if (caps?.canReadPppoe != true) Text(copy.unsupported, color = Warning)
                    else if (state.pppoe.isEmpty()) Text(copy.none, color = TextMute)
                    else state.pppoe.forEach { s ->
                        NocCard {
                            Column {
                                Text(s.name, color = TextMain, fontWeight = FontWeight.SemiBold)
                                Kv("IP", s.address)
                                Kv("Caller", s.callerId)
                                Kv(copy.uptime, s.uptime)
                                if (caps.canDisconnectPppoe) {
                                    OutlinedButton(onClick = { pendingPppoe = s }) { Text(copy.disconnect, color = Offline) }
                                } else Text(copy.unsupported, color = Warning)
                            }
                        }
                    }
                }
                DetailTab.IP -> {
                    if (caps?.canReadIp != true) Text(copy.unsupported, color = Warning)
                    else state.ips.forEach { NocCard { Column { Text(it.address, color = TextMain, fontFamily = FontFamily.Monospace); Kv("IF", it.interfaceName) } } }
                }
                DetailTab.DHCP -> {
                    if (caps?.canReadDhcp != true) Text(copy.unsupported, color = Warning)
                    else state.dhcp.forEach { NocCard { Column { Text(it.address, color = TextMain, fontFamily = FontFamily.Monospace); Kv("MAC", it.mac); Kv("Host", it.hostName) } } }
                }
                DetailTab.ARP -> {
                    if (caps?.canReadArp != true) Text(copy.unsupported, color = Warning)
                    else state.arp.forEach { NocCard { Column { Text(it.address, color = TextMain, fontFamily = FontFamily.Monospace); Kv("MAC", it.mac); Kv("IF", it.interfaceName) } } }
                }
                DetailTab.Routes -> {
                    if (caps?.canReadRoutes != true) Text(copy.unsupported, color = Warning)
                    else state.routes.forEach { NocCard { Column { Text(it.dstAddress, color = TextMain, fontFamily = FontFamily.Monospace); Kv("GW", it.gateway) } } }
                }
                DetailTab.Alerts -> {
                    if (state.alerts.isEmpty()) Text(copy.noAlerts, color = TextMute)
                    state.alerts.forEach { a ->
                        NocCard {
                            Column {
                                Text(a.title, color = Warning, fontWeight = FontWeight.SemiBold)
                                Text(a.detail, color = TextMain, fontSize = 13.sp)
                            }
                        }
                    }
                }
                DetailTab.Logs -> {
                    if (caps?.canReadLogs != true) Text(copy.unsupported, color = Warning)
                    else state.logs.forEach {
                        NocCard { Text("${it.time ?: ""} ${it.message}", color = TextMain, fontSize = 12.sp, fontFamily = FontFamily.Monospace) }
                    }
                }
                DetailTab.Actions -> {
                    state.interfaces.forEach { iface ->
                        NocCard {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Column {
                                    Text(iface.name, color = TextMain)
                                    Text(if (iface.enabled) copy.enabled else copy.disabled, color = TextMute, fontSize = 12.sp)
                                }
                                if (caps?.canToggleInterface == true) {
                                    OutlinedButton(onClick = { pendingToggle = iface to !iface.enabled }) {
                                        Text(if (iface.enabled) copy.disable else copy.enable, color = Accent)
                                    }
                                } else Text(copy.unsupported, color = Warning)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    if (caps?.canReboot == true) {
                        Button(
                            onClick = { rebootStep = 1 },
                            colors = ButtonDefaults.buttonColors(containerColor = Offline),
                        ) { Text(copy.reboot) }
                    } else Text(copy.unsupported, color = Warning)
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = { state.device?.id?.let { vm.deleteDevice(it); onBack() } }) {
                        Text(copy.deleteDevice, color = Offline)
                    }
                }
            }
        }
    }

    pendingPppoe?.let { session ->
        AlertDialog(
            onDismissRequest = { pendingPppoe = null },
            title = { Text(copy.disconnect) },
            text = { Text(copy.confirmDisconnect + "\n${session.name}") },
            confirmButton = {
                TextButton(onClick = { vm.disconnectPppoe(session); pendingPppoe = null }) { Text(copy.confirm) }
            },
            dismissButton = { TextButton(onClick = { pendingPppoe = null }) { Text(copy.cancel) } },
        )
    }
    pendingToggle?.let { (iface, enabled) ->
        AlertDialog(
            onDismissRequest = { pendingToggle = null },
            title = { Text(if (enabled) copy.enable else copy.disable) },
            text = { Text("${copy.confirmToggle}\n${iface.name}") },
            confirmButton = {
                TextButton(onClick = { vm.setInterfaceEnabled(iface, enabled); pendingToggle = null }) { Text(copy.confirm) }
            },
            dismissButton = { TextButton(onClick = { pendingToggle = null }) { Text(copy.cancel) } },
        )
    }
    if (rebootStep == 1) {
        AlertDialog(
            onDismissRequest = { rebootStep = 0 },
            title = { Text(copy.reboot) },
            text = { Text(copy.confirmReboot1) },
            confirmButton = { TextButton(onClick = { rebootStep = 2 }) { Text(copy.confirm) } },
            dismissButton = { TextButton(onClick = { rebootStep = 0 }) { Text(copy.cancel) } },
        )
    }
    if (rebootStep == 2) {
        AlertDialog(
            onDismissRequest = { rebootStep = 0 },
            title = { Text(copy.reboot) },
            text = { Text(copy.confirmReboot2) },
            confirmButton = { TextButton(onClick = { vm.reboot(); rebootStep = 0 }) { Text(copy.confirm) } },
            dismissButton = { TextButton(onClick = { rebootStep = 0 }) { Text(copy.cancel) } },
        )
    }
}
