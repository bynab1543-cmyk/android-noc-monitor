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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.noc.monitor.protocol.DeviceCatalog
import com.noc.monitor.ui.theme.BlueBtn
import com.noc.monitor.ui.theme.LavenderBg
import com.noc.monitor.ui.theme.Orange
import com.noc.monitor.ui.theme.Purple
import com.noc.monitor.ui.theme.RedBtn
import com.noc.monitor.ui.theme.TextDark
import com.noc.monitor.ui.theme.TextMute

@Composable
fun DeviceEditor(vm: MonitorViewModel, onClose: () -> Unit) {
    val form by vm.form.collectAsState()
    val spec = DeviceCatalog.byId(form.kindId)
    val sites by vm.sites.collectAsState()
    var typeOpen by remember { mutableStateOf(false) }
    var moveOpen by remember { mutableStateOf(false) }
    var newSite by remember { mutableStateOf("") }
    val colors = OutlinedTextFieldDefaults.colors(
        focusedContainerColor = Color.White,
        unfocusedContainerColor = Color.White,
        focusedBorderColor = Color.Transparent,
        unfocusedBorderColor = Color.Transparent,
        focusedTextColor = TextDark,
        unfocusedTextColor = TextDark,
        focusedLabelColor = TextMute,
        unfocusedLabelColor = TextMute,
        cursorColor = Purple,
    )
    Column(
        Modifier
            .fillMaxSize()
            .background(LavenderBg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "رجوع", tint = TextDark)
            }
            Text(
                if (form.id == null) "إضافة جهاز" else "تعديل ${form.host.ifBlank { ""} }",
                color = TextDark,
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp,
                modifier = Modifier.weight(1f),
            )
        }
        RoundedField(
            if (spec.isSector) "اسم السكتر / الملاحظة" else "يمكنك إضافة ملاحظتك هنا",
            form.note,
            colors,
        ) { vm.updateForm { f -> f.copy(note = it) } }
        RoundedField("عنوان IP", form.host, colors) { vm.updateForm { f -> f.copy(host = it) } }
        if (spec.usesUsernamePassword) {
            RoundedField("اسم المستخدم", form.username, colors) { vm.updateForm { f -> f.copy(username = it) } }
            OutlinedTextField(
                form.password,
                { vm.updateForm { f -> f.copy(password = it) } },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("كلمة المرور") },
                visualTransformation = PasswordVisualTransformation(),
                shape = RoundedCornerShape(28.dp),
                colors = colors,
                singleLine = true,
                placeholder = { if (form.id != null) Text("••••••") },
            )
        }
        if (spec.usesSnmpCommunity) {
            RoundedField("بروتوكول SNMP", form.snmpCommunity, colors) { vm.updateForm { f -> f.copy(snmpCommunity = it) } }
        }
        Column {
            Text("نوع الجهاز", color = TextMute, fontSize = 12.sp, modifier = Modifier.align(Alignment.End).padding(end = 8.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(28.dp))
                    .background(Color.White)
                    .clickable { typeOpen = true }
                    .padding(18.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, tint = TextMute)
                Text(spec.label, color = Orange, fontWeight = FontWeight.Bold)
            }
        }
        if (spec.usesSnmpCommunity) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
            ) {
                Text(
                    "فعّل SNMPv2c على الجهاز ثم أدخل مجتمع SNMP هنا",
                    color = Color(0xFF22C3E6),
                    fontSize = 12.sp,
                )
                Icon(Icons.Default.Link, contentDescription = null, tint = Color(0xFF22C3E6), modifier = Modifier.size(16.dp).padding(start = 4.dp))
            }
        }
        form.message?.let { Text(it, color = RedBtn, fontSize = 13.sp) }
        Button(
            onClick = { vm.save(onClose) },
            enabled = !form.saving && form.host.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = RoundedCornerShape(28.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Purple),
        ) { Text("حفظ التغيرات", fontSize = 16.sp, fontWeight = FontWeight.Bold) }
        Button(
            onClick = { moveOpen = true },
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = RoundedCornerShape(28.dp),
            colors = ButtonDefaults.buttonColors(containerColor = BlueBtn),
        ) { Text("نقل الى بوينت اخر", fontWeight = FontWeight.Bold) }
        if (form.id != null) {
            Button(
                onClick = { vm.delete(form.id!!, onClose) },
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(28.dp),
                colors = ButtonDefaults.buttonColors(containerColor = RedBtn),
            ) { Text("حذف", fontWeight = FontWeight.Bold) }
        }
        Spacer(Modifier.height(12.dp))
    }

    if (typeOpen) {
        Dialog(onDismissRequest = { typeOpen = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(top = 80.dp)
                    .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                    .background(Color.White)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 12.dp),
            ) {
                Column {
                    Text("السكترات", color = TextMute, fontSize = 13.sp, modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 8.dp))
                    vm.models.filter { it.isSector }.forEach { m -> TypeRow(m.label, m.id == form.kindId) { vm.updateForm { f -> f.copy(kindId = m.id) }; typeOpen = false } }
                    Text("اللنكات", color = TextMute, fontSize = 13.sp, modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 8.dp))
                    vm.models.filter { !it.isSector }.forEach { m -> TypeRow(m.label, m.id == form.kindId) { vm.updateForm { f -> f.copy(kindId = m.id) }; typeOpen = false } }
                    Spacer(Modifier.height(40.dp))
                }
            }
        }
    }

    if (moveOpen) {
        AlertDialog(
            onDismissRequest = { moveOpen = false },
            title = { Text("نقل الى بوينت اخر") },
            text = {
                Column {
                    sites.forEach { s ->
                        Text(
                            s.name,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    form.id?.let { vm.move(it, s.id) }
                                    moveOpen = false
                                    onClose()
                                }
                                .padding(vertical = 10.dp),
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    OutlinedTextField(newSite, { newSite = it }, label = { Text("موقع جديد") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newSite.isNotBlank()) {
                        vm.addSiteAndMove(form.id, newSite)
                        moveOpen = false
                        onClose()
                    }
                }) { Text("نقل") }
            },
            dismissButton = { TextButton(onClick = { moveOpen = false }) { Text("إلغاء") } },
        )
    }
}

@Composable
private fun TypeRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        Text(
            label,
            color = if (selected) Orange else TextDark,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            fontSize = 16.sp,
        )
    }
}

@Composable
private fun RoundedField(
    label: String,
    value: String,
    colors: androidx.compose.material3.TextFieldColors,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        shape = RoundedCornerShape(28.dp),
        colors = colors,
        singleLine = true,
    )
}
