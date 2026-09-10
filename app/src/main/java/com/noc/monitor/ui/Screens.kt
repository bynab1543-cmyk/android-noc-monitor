package com.noc.monitor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noc.monitor.protocol.OperatingMode
import com.noc.monitor.protocol.traffic.TrafficFormat
import com.noc.monitor.ui.components.Kv
import com.noc.monitor.ui.components.MetricCard
import com.noc.monitor.ui.components.NocCard
import com.noc.monitor.ui.components.StatusDot
import com.noc.monitor.ui.theme.Accent
import com.noc.monitor.ui.theme.Bg
import com.noc.monitor.ui.theme.Card
import com.noc.monitor.ui.theme.Demo
import com.noc.monitor.ui.theme.Offline
import com.noc.monitor.ui.theme.Online
import com.noc.monitor.ui.theme.Surface
import com.noc.monitor.ui.theme.TextMain
import com.noc.monitor.ui.theme.TextMute
import com.noc.monitor.ui.theme.Warning

enum class Tab { Dashboard, Devices, Traffic, Alerts, Settings }

@Composable
fun NocApp(vm: MonitorViewModel) {
    val lang by vm.language.collectAsState()
    val copy = if (lang == "ar") Ar else En
    val dash by vm.dashboard.collectAsState()
    var tab by remember { mutableStateOf(Tab.Dashboard) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = Bg,
        bottomBar = {
            NavigationBar(containerColor = Surface) {
                val items = listOf(
                    Tab.Dashboard to Icons.Default.Speed,
                    Tab.Devices to Icons.Default.Router,
                    Tab.Traffic to Icons.AutoMirrored.Filled.ShowChart,
                    Tab.Alerts to Icons.Default.Notifications,
                    Tab.Settings to Icons.Default.Settings,
                )
                val labels = listOf(copy.dashboard, copy.devices, copy.traffic, copy.alerts, copy.settings)
                items.forEachIndexed { i, (t, icon) ->
                    NavigationBarItem(
                        selected = tab == t && selectedId == null && !adding,
                        onClick = {
                            tab = t
                            selectedId = null
                            adding = false
                        },
                        icon = { Icon(icon, contentDescription = labels[i]) },
                        label = { Text(labels[i], fontSize = 11.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Accent,
                            selectedTextColor = Accent,
                            indicatorColor = Card,
                            unselectedIconColor = TextMute,
                            unselectedTextColor = TextMute,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Bg),
        ) {
            if (dash.mode == OperatingMode.DEMO) {
                Text(
                    copy.demoBanner,
                    color = Bg,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Demo)
                        .padding(10.dp),
                )
            }
            when {
                adding -> AddDeviceFormScreen(vm, copy) { adding = false }
                selectedId != null -> DeviceDetailScreen(vm, copy, selectedId!!) { selectedId = null }
                tab == Tab.Dashboard -> DashboardScreen(dash, copy)
                tab == Tab.Devices -> DeviceListScreen(vm, copy, onAdd = { adding = true }) { selectedId = it }
                tab == Tab.Traffic -> TrafficOverviewScreen(dash, copy) { selectedId = it }
                tab == Tab.Alerts -> AlertsScreen(vm, copy)
                tab == Tab.Settings -> SettingsScreen(vm, copy)
            }
        }
    }
}

@Composable
fun DashboardScreen(dash: DashboardSnapshot, copy: Copy) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(copy.dashboard, color = TextMain, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            Text(copy.polling, color = TextMute, fontSize = 12.sp)
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricCard(copy.totalDevices, dash.total.toString(), Accent, Modifier.weight(1f))
                MetricCard(copy.online, dash.online.toString(), Online, Modifier.weight(1f))
                MetricCard(copy.offline, dash.offline.toString(), Offline, Modifier.weight(1f))
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricCard(copy.alerts, dash.alerts.toString(), Warning, Modifier.weight(1f))
                MetricCard(
                    copy.totalTraffic,
                    TrafficFormat.bps(dash.totalRxBps + dash.totalTxBps),
                    Accent,
                    Modifier.weight(1f),
                )
            }
        }
        item {
            NocCard {
                Column {
                    Text(copy.highestTraffic, color = TextMute, fontSize = 12.sp)
                    Spacer(Modifier.height(6.dp))
                    if (dash.highest == null) {
                        Text(copy.none, color = TextMain)
                    } else {
                        Text(dash.highestName ?: dash.highest.deviceId, color = TextMain, fontWeight = FontWeight.SemiBold)
                        Text(
                            "${TrafficFormat.bps(dash.highest.peakTotalBps)} · ${copy.peakOn} ${dash.highest.interfaceName}",
                            color = Accent,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
        items(dash.devices, key = { it.id }) { d ->
            NocCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(d.status)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(d.displayName, color = TextMain, fontWeight = FontWeight.SemiBold)
                        Text("${d.host}:${d.port} · ${d.identity ?: d.model ?: d.vendor}", color = TextMute, fontSize = 12.sp)
                    }
                    Text(TrafficFormat.bps(d.lastRxBps + d.lastTxBps), color = Accent, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
            }
        }
        if (dash.devices.isEmpty()) {
            item {
                NocCard {
                    Column {
                        Text(copy.noDevices, color = TextMain, fontWeight = FontWeight.SemiBold)
                        Text(copy.noDevicesHint, color = TextMute, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun DeviceListScreen(vm: MonitorViewModel, copy: Copy, onAdd: () -> Unit, onOpen: (String) -> Unit) {
    val search by vm.search.collectAsState()
    val filter by vm.statusFilter.collectAsState()
    val devices = vm.filteredDevices()
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(copy.devices, color = TextMain, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            TextButton(onClick = onAdd) { Text(copy.addDevice, color = Accent) }
        }
        OutlinedTextField(
            value = search,
            onValueChange = { vm.search.value = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(copy.search) },
            singleLine = true,
            colors = fieldColors(),
        )
        Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("ALL" to copy.all, "ONLINE" to copy.online, "OFFLINE" to copy.offline).forEach { (k, label) ->
                FilterChip(
                    selected = filter == k,
                    onClick = { vm.statusFilter.value = k },
                    label = { Text(label) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Accent,
                        selectedLabelColor = Bg,
                    ),
                )
            }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(devices, key = { it.id }) { d ->
                NocCard(Modifier.clickable { onOpen(d.id) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(d.status)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(d.displayName, color = TextMain, fontWeight = FontWeight.SemiBold)
                            Text("${d.host}:${d.port} · ${d.transport}", color = TextMute, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                            if (!d.lastError.isNullOrBlank() && d.status == "OFFLINE") {
                                Text(d.lastError, color = Offline, fontSize = 11.sp)
                            }
                        }
                        Text(
                            when (d.status) {
                                "ONLINE" -> copy.online
                                "OFFLINE" -> copy.offline
                                else -> copy.unknown
                            },
                            color = if (d.status == "ONLINE") Online else if (d.status == "OFFLINE") Offline else Warning,
                            fontSize = 12.sp,
                        )
                    }
                }
            }
            if (devices.isEmpty()) {
                item {
                    Text(copy.noDevices, color = TextMute, modifier = Modifier.padding(top = 24.dp))
                }
            }
        }
    }
}

@Composable
fun TrafficOverviewScreen(dash: DashboardSnapshot, copy: Copy, onOpen: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text(copy.traffic, color = TextMain, fontSize = 24.sp, fontWeight = FontWeight.SemiBold) }
        if (dash.devices.isEmpty()) {
            item { Text(copy.noTraffic, color = TextMute) }
        }
        items(dash.devices, key = { it.id }) { d ->
            val peak = dash.peaks.firstOrNull { it.deviceId == d.id }
            NocCard(Modifier.clickable { onOpen(d.id) }) {
                Column {
                    Text(d.displayName, color = TextMain, fontWeight = FontWeight.SemiBold)
                    Kv(copy.rx, TrafficFormat.bps(d.lastRxBps))
                    Kv(copy.tx, TrafficFormat.bps(d.lastTxBps))
                    Kv(copy.highestTraffic, peak?.let { TrafficFormat.bps(it.peakTotalBps) + " (${it.interfaceName})" } ?: copy.none)
                }
            }
        }
    }
}

@Composable
fun AlertsScreen(vm: MonitorViewModel, copy: Copy) {
    val dash by vm.dashboard.collectAsState()
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text(copy.alerts, color = TextMain, fontSize = 24.sp, fontWeight = FontWeight.SemiBold) }
        if (dash.alertList.isEmpty()) {
            item { Text(copy.noAlerts, color = TextMute) }
        }
        items(dash.alertList, key = { it.id }) { a ->
            NocCard {
                Column {
                    Text(a.title, color = if (a.severity == "CRITICAL") Offline else Warning, fontWeight = FontWeight.SemiBold)
                    Text(a.detail, color = TextMain, fontSize = 13.sp)
                    val name = dash.devices.firstOrNull { it.id == a.deviceId }?.displayName ?: a.deviceId
                    Text(name, color = TextMute, fontSize = 12.sp)
                    if (!a.acknowledged) {
                        TextButton(onClick = { vm.ack(a.id) }) { Text(copy.ack, color = Accent) }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(vm: MonitorViewModel, copy: Copy) {
    val lang by vm.language.collectAsState()
    val mode by vm.mode.collectAsState()
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(copy.settings, color = TextMain, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
        NocCard {
            Column {
                Text(copy.language, color = TextMute)
                Row {
                    TextButton(onClick = { vm.setLanguage("en") }) {
                        Text(copy.english, color = if (lang == "en") Accent else TextMute)
                    }
                    TextButton(onClick = { vm.setLanguage("ar") }) {
                        Text(copy.arabic, color = if (lang == "ar") Accent else TextMute)
                    }
                }
            }
        }
        NocCard {
            Column {
                Text(copy.operatingMode, color = TextMute)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(copy.realMode, color = if (mode == OperatingMode.REAL) Online else TextMute, modifier = Modifier.weight(1f))
                    Switch(
                        checked = mode == OperatingMode.DEMO,
                        onCheckedChange = { vm.setMode(if (it) OperatingMode.DEMO else OperatingMode.REAL) },
                    )
                    Text(copy.demoMode, color = if (mode == OperatingMode.DEMO) Demo else TextMute)
                }
                Text(if (mode == OperatingMode.DEMO) copy.demoBanner else copy.realHint, color = TextMute, fontSize = 12.sp)
            }
        }
        NocCard {
            Column {
                Text("Security", color = TextMute)
                Text("Passwords are stored in Android EncryptedSharedPreferences (Keystore). They are never logged.", color = TextMain, fontSize = 13.sp)
                Text("Port 9 is rejected. REAL mode never synthesizes traffic counters.", color = TextMute, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

@Composable
fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Accent,
    unfocusedBorderColor = TextMute.copy(alpha = 0.4f),
    focusedTextColor = TextMain,
    unfocusedTextColor = TextMain,
    cursorColor = Accent,
    focusedLabelColor = Accent,
    unfocusedLabelColor = TextMute,
    focusedPlaceholderColor = TextMute,
    unfocusedPlaceholderColor = TextMute,
)
