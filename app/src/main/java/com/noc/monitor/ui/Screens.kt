package com.noc.monitor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeviceHub
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noc.monitor.data.local.DeviceEntity
import com.noc.monitor.data.repo.TrafficPoint
import com.noc.monitor.protocol.DeviceCatalog
import com.noc.monitor.protocol.RadioFamily
import com.noc.monitor.protocol.RadioSnapshot
import com.noc.monitor.ui.components.ArcGauge
import com.noc.monitor.ui.components.Sparkline
import com.noc.monitor.ui.theme.CardWhite
import com.noc.monitor.ui.theme.Cyan
import com.noc.monitor.ui.theme.GaugeFill
import com.noc.monitor.ui.theme.Green
import com.noc.monitor.ui.theme.LavenderBg
import com.noc.monitor.ui.theme.NavBar
import com.noc.monitor.ui.theme.Orange
import com.noc.monitor.ui.theme.Purple
import com.noc.monitor.ui.theme.SiteChip
import com.noc.monitor.ui.theme.TextDark
import com.noc.monitor.ui.theme.TextMute

@Composable
fun NocApp(vm: MonitorViewModel) {
    val tab by vm.tab.collectAsState()
    var editing by remember { mutableStateOf<DeviceEntity?>(null) }
    var adding by remember { mutableStateOf(false) }

    if (adding || editing != null) {
        DeviceEditor(vm, onClose = { adding = false; editing = null })
        return
    }

    Scaffold(
        containerColor = LavenderBg,
        floatingActionButton = {
            FloatingActionButton(
                onClick = { vm.openNew(); adding = true },
                containerColor = Purple,
                contentColor = Color.White,
                shape = CircleShape,
                modifier = Modifier.size(62.dp),
            ) { Icon(Icons.Default.Add, contentDescription = "إضافة", modifier = Modifier.size(30.dp)) }
        },
        bottomBar = { BottomNav(tab) { vm.tab.value = it } },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 14.dp),
        ) {
            Spacer(Modifier.height(6.dp))
            SiteHeader(vm)
            Spacer(Modifier.height(10.dp))
            SearchBar(vm)
            Spacer(Modifier.height(10.dp))
            DeviceList(vm) { editing = it; vm.openEdit(it) }
        }
    }
}

@Composable
private fun SiteHeader(vm: MonitorViewModel) {
    val sites by vm.sites.collectAsState()
    val siteId by vm.selectedSiteId.collectAsState()
    val site = sites.firstOrNull { it.id == siteId } ?: sites.firstOrNull()
    var open by remember { mutableStateOf(false) }
    var addingSite by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Row(
            Modifier
                .clip(RoundedCornerShape(22.dp))
                .background(SiteChip)
                .clickable { open = true }
                .padding(horizontal = 20.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, tint = Purple, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Text(site?.name ?: "ابراجي", color = Purple, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Default.CellTower, contentDescription = null, tint = Purple)
        }
        DropdownMenu(open, { open = false }) {
            sites.forEach { s ->
                DropdownMenuItem(text = { Text(s.name) }, onClick = { vm.selectSite(s.id); open = false })
            }
            DropdownMenuItem(text = { Text("إضافة موقع") }, onClick = { open = false; addingSite = true })
        }
    }
    if (addingSite) {
        AlertDialog(
            onDismissRequest = { addingSite = false },
            confirmButton = {
                TextButton(onClick = {
                    vm.addSite(newName)
                    newName = ""
                    addingSite = false
                }) { Text("حفظ") }
            },
            dismissButton = { TextButton(onClick = { addingSite = false }) { Text("إلغاء") } },
            title = { Text("موقع جديد") },
            text = {
                OutlinedTextField(newName, { newName = it }, singleLine = true, label = { Text("اسم الموقع") })
            },
        )
    }
}

@Composable
private fun SearchBar(vm: MonitorViewModel) {
    val q by vm.search.collectAsState()
    OutlinedTextField(
        value = q,
        onValueChange = { vm.search.value = it },
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("بحث عن جهاز او يوزر", color = TextMute) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = TextMute) },
        singleLine = true,
        shape = RoundedCornerShape(28.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = Color.White,
            unfocusedContainerColor = Color.White,
            focusedBorderColor = Color.Transparent,
            unfocusedBorderColor = Color.Transparent,
            focusedTextColor = TextDark,
            unfocusedTextColor = TextDark,
        ),
    )
}

@Composable
private fun DeviceList(vm: MonitorViewModel, onEdit: (DeviceEntity) -> Unit) {
    val devices by vm.devices.collectAsState()
    val q by vm.search.collectAsState()
    val site by vm.selectedSiteId.collectAsState()
    val samples by vm.samples.collectAsState()
    val visible = devices.filter { d ->
        d.siteId == site &&
            (q.isBlank() || d.note.contains(q) || d.host.contains(q) || d.username.contains(q))
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
        items(visible, key = { it.id }) { d ->
            LaunchedEffect(d.id, d.snapshotJson) { vm.refreshSamples(d.id) }
            val snap = vm.snapshot(d)
            val family = DeviceCatalog.byId(d.kindId).family
            DeviceCard(d, snap, samples[d.id].orEmpty(), family) { onEdit(d) }
        }
        if (visible.isEmpty()) {
            item {
                Text("لا توجد أجهزة في هذا الموقع", color = TextMute, modifier = Modifier.padding(24.dp))
            }
        }
        item { Spacer(Modifier.height(88.dp)) }
    }
}

@Composable
private fun DeviceCard(
    device: DeviceEntity,
    snap: RadioSnapshot?,
    samples: List<TrafficPoint>,
    family: RadioFamily,
    onEdit: () -> Unit,
) {
    val online = device.status == "ONLINE"
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(26.dp))
            .background(CardWhite)
            .clickable(onClick = onEdit)
            .padding(14.dp),
    ) {
        Box(Modifier.fillMaxWidth()) {
            Column(Modifier.align(Alignment.TopStart), horizontalAlignment = Alignment.End) {
                Text(snap?.identity ?: device.note, color = TextDark, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text(device.host, color = Purple, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                val mac = snap?.mac
                if (mac != null) Text(mac, color = TextMute, fontSize = 12.sp)
                val ssid = snap?.ssid
                if (ssid != null && family != RadioFamily.MIMOSA && family != RadioFamily.AIRFIBER) {
                    Text(ssid, color = TextMute, fontSize = 12.sp)
                }
            }
            Column(Modifier.align(Alignment.TopEnd), horizontalAlignment = Alignment.Start) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(Purple)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text(device.note, color = Color.White, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MiniIcon(Icons.Default.Tune)
                    MiniIcon(Icons.Default.Check)
                    MiniIcon(Icons.Default.PowerSettingsNew)
                }
            }
            Icon(
                Icons.Default.Router,
                contentDescription = null,
                tint = TextMute,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 4.dp)
                    .size(42.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        if (family == RadioFamily.MIMOSA || family == RadioFamily.AIRFIBER) {
            MimosaStats(snap)
        } else {
            AirMaxStats(snap)
        }
        Spacer(Modifier.height(8.dp))
        Sparkline(samples, Modifier.fillMaxWidth().height(86.dp))
        if (!snap?.lastError.isNullOrBlank() && !online) {
            Text(snap!!.lastError!!, color = Redish, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

private val Redish = Color(0xFFEF4444)

@Composable
private fun MiniIcon(icon: ImageVector) {
    Box(
        Modifier
            .size(26.dp)
            .clip(CircleShape)
            .background(Color(0xFFF3F0FF)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Purple, modifier = Modifier.size(14.dp))
    }
}

@Composable
private fun AirMaxStats(snap: RadioSnapshot?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        GaugeBlock(snap?.ramPercent, "الذاكرة", GaugeFill)
        GaugeBlock(snap?.cpuPercent, "المعالج", GaugeFill)
        GaugeBlock(snap?.ccq, "CCQ", GaugeFill)
    }
    Spacer(Modifier.height(8.dp))
    InfoGrid(
        listOf(
            Stat("الوضع", dash(snap?.mode), Icons.Default.Settings),
            Stat("الحالة", if (snap?.online == true) dash(snap.state ?: "running") else "غير متصل", Icons.Default.PowerSettingsNew, if (snap?.online == true) Green else Redish),
            Stat("قائمة الفحص", dash(snap?.scanList), Icons.AutoMirrored.Filled.List),
            Stat("الإصدار", dash(snap?.firmware), Icons.Default.DeviceHub),
            Stat("التردد", mhz(snap?.frequencyMhz), Icons.Default.Speed),
            Stat("مدة التشغيل", dash(snap?.uptime), Icons.Default.Speed),
            Stat("درجة الحرارة", temp(snap?.temperatureC), Icons.Default.Thermostat),
            Stat("الإيثرنت", dash(snap?.ethernetSpeed), Icons.Default.Wifi, Purple, pill = snap?.ethernetSpeed != null),
            Stat("استقبال", mbps(snap?.rxMbps), Icons.Default.Wifi, Cyan),
            Stat("إرسال", mbps(snap?.txMbps), Icons.Default.Wifi, Cyan),
            Stat("الفولتية", volt(snap?.voltage), Icons.Default.Bolt, Purple),
            Stat("العملاء", snap?.clients?.toString() ?: "—", Icons.Default.People, Purple),
        ),
    )
}

@Composable
private fun MimosaStats(snap: RadioSnapshot?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(if (snap?.online == true) "متصل" else "غير متصل", color = if (snap?.online == true) Green else Redish, fontWeight = FontWeight.Bold)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(dash(snap?.uptime), color = TextDark, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text("مدة تشغيل اللنك", color = TextMute, fontSize = 10.sp)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(dash(snap?.ssid), color = Purple, fontWeight = FontWeight.Bold)
            Text("اسم الشبكة", color = TextMute, fontSize = 10.sp)
        }
    }
    Spacer(Modifier.height(10.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        GaugeBlock(snap?.txCcq ?: snap?.ccq, "Tx CCQ", GaugeFill)
        CapacityBlock(snap)
        SignalBlock(snap)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        GaugeBlock(snap?.rxCcq, "Rx CCQ", GaugeFill)
    }
    Spacer(Modifier.height(6.dp))
    InfoGrid(
        listOf(
            Stat("التردد", mhz(snap?.frequencyMhz), Icons.Default.Speed),
            Stat("عرض الحزمة", snap?.channelWidthMhz?.let { "${it}MHz" } ?: "—", Icons.Default.Wifi),
            Stat("درجة الحرارة", temp(snap?.temperatureC), Icons.Default.Thermostat),
            Stat("الإيثرنت", dash(snap?.ethernetSpeed), Icons.Default.Wifi, Purple, pill = snap?.ethernetSpeed != null),
            Stat("معدل خطأ TX", pct(snap?.txErrorPercent), Icons.Default.Settings),
            Stat("معدل خطأ RX", pct(snap?.rxErrorPercent), Icons.Default.Settings),
            Stat("الطاقة", dbm(snap?.txPowerDbm), Icons.Default.Bolt, Green),
            Stat("SNR", snap?.snr?.toString() ?: "—", Icons.Default.Speed, Green),
            Stat("إرسال", mbps(snap?.txMbps), Icons.Default.Wifi, Cyan),
            Stat("استقبال", mbps(snap?.rxMbps), Icons.Default.Wifi, Cyan),
            Stat("المجموع", mbps(sum(snap?.txMbps, snap?.rxMbps)), Icons.Default.Speed, Cyan),
            Stat("التشويش", dbmNeg(snap?.interferenceDbm), Icons.Default.Wifi, Green),
        ),
    )
}

private data class Stat(
    val label: String,
    val value: String,
    val icon: ImageVector,
    val color: Color = TextDark,
    val pill: Boolean = false,
)

@Composable
private fun GaugeBlock(value: Int?, label: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center) {
            ArcGauge(percent = value, color = color)
            Text(value?.let { "$it%" } ?: "—", color = TextDark, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
        Text(label, color = TextMute, fontSize = 11.sp)
    }
}

@Composable
private fun CapacityBlock(snap: RadioSnapshot?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(86.dp)
                .clip(CircleShape)
                .background(Color(0xFFF7F5FF)),
            contentAlignment = Alignment.Center,
        ) {
            val tx = snap?.capacityTxMbps
            val rx = snap?.capacityRxMbps
            Text(
                if (tx != null && rx != null) "$tx/$rx\nMbps" else "—",
                color = TextDark,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun SignalBlock(snap: RadioSnapshot?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(86.dp)
                .clip(CircleShape)
                .background(Color(0xFFF7F5FF)),
            contentAlignment = Alignment.Center,
        ) {
            val v = snap?.signalDbm
            Text(
                if (v != null) "${trimNum(v)}-\ndBm" else "—",
                color = Orange,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun InfoGrid(pairs: List<Stat>) {
    Column {
        pairs.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                row.forEach { stat ->
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(stat.icon, contentDescription = null, tint = TextMute, modifier = Modifier.size(11.dp))
                            Spacer(Modifier.width(3.dp))
                            Text(stat.label, color = TextMute, fontSize = 10.sp, maxLines = 1)
                        }
                        Spacer(Modifier.height(3.dp))
                        if (stat.pill) {
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Purple)
                                    .padding(horizontal = 8.dp, vertical = 2.dp),
                            ) {
                                Text(stat.value, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1)
                            }
                        } else {
                            Text(stat.value, color = stat.color, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1)
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun BottomNav(selected: Int, onSelect: (Int) -> Unit) {
    val icons = listOf(
        Icons.Default.MoreHoriz,
        Icons.AutoMirrored.Filled.ShowChart,
        Icons.Default.Share,
        Icons.Default.People,
        Icons.Default.Wifi,
    )
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
            .background(NavBar)
            .navigationBarsPadding()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icons.forEachIndexed { i, icon ->
            val on = selected == i
            Box(
                Modifier
                    .clip(CircleShape)
                    .background(if (on) Color.White.copy(alpha = 0.18f) else Color.Transparent)
                    .clickable { onSelect(i) }
                    .padding(10.dp),
            ) {
                Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(26.dp))
            }
        }
    }
}

private fun dash(v: String?) = v?.ifBlank { null } ?: "—"
private fun mhz(v: Int?) = v?.let { "${it}MHz" } ?: "—"
private fun temp(v: Double?) = v?.let { "${it.toInt()}C" } ?: "—"
private fun mbps(v: Double?) = v?.let { String.format("%.1fMbps", it) } ?: "—"
private fun volt(v: Double?) = v?.let { "${trimNum(it)}V" } ?: "—"
private fun dbm(v: Double?) = v?.let { "${trimNum(it)}dBm" } ?: "—"
private fun dbmNeg(v: Double?) = v?.let { "${trimNum(kotlin.math.abs(it))}dBm-" } ?: "—"
private fun pct(v: Double?) = v?.let { "$it%" } ?: "—"
private fun trimNum(v: Double): String = if (v % 1.0 == 0.0) v.toInt().toString() else String.format("%.1f", v)
private fun sum(a: Double?, b: Double?): Double? =
    if (a == null && b == null) null else (a ?: 0.0) + (b ?: 0.0)
