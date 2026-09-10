package com.noc.monitor.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.noc.monitor.NocApplication
import com.noc.monitor.data.local.DeviceEntity
import com.noc.monitor.data.local.SiteEntity
import com.noc.monitor.data.repo.DEFAULT_SITE_ID
import com.noc.monitor.data.repo.SaveDeviceRequest
import com.noc.monitor.protocol.DeviceCatalog
import com.noc.monitor.protocol.DeviceModelSpec
import com.noc.monitor.protocol.NocResult
import com.noc.monitor.protocol.RadioSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DeviceForm(
    val id: String? = null,
    val note: String = "",
    val host: String = "",
    val username: String = "admin",
    val password: String = "",
    val snmpCommunity: String = "public",
    val kindId: String = "mikrotik-link",
    val saving: Boolean = false,
    val message: String? = null,
    val ok: Boolean? = null,
)

class MonitorViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as NocApplication
    private val repo = app.repository

    val sites = repo.observeSites().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val devices = repo.observeDevices().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val selectedSiteId = MutableStateFlow(DEFAULT_SITE_ID)
    val search = MutableStateFlow("")
    val form = MutableStateFlow(DeviceForm())
    val tab = MutableStateFlow(4)
    val samples = MutableStateFlow<Map<String, List<com.noc.monitor.data.repo.TrafficPoint>>>(emptyMap())

    val models: List<DeviceModelSpec> = DeviceCatalog.all

    init {
        viewModelScope.launch {
            repo.ensureDefaultSite()
            val all = repo.allSites()
            if (all.isNotEmpty()) selectedSiteId.value = all.first().id
        }
    }

    fun visibleDevices(): List<DeviceEntity> {
        val q = search.value.trim()
        val site = selectedSiteId.value
        return devices.value.filter { d ->
            d.siteId == site &&
                (q.isEmpty() || d.note.contains(q) || d.host.contains(q))
        }
    }

    fun snapshot(d: DeviceEntity): RadioSnapshot? = RadioSnapshot.fromJson(d.snapshotJson)

    fun openNew() {
        form.value = DeviceForm()
    }

    fun openEdit(d: DeviceEntity) {
        viewModelScope.launch {
            val community = repo.peekSecret(d.snmpCredentialId) ?: "public"
            form.value = DeviceForm(
                id = d.id,
                note = d.note,
                host = d.host,
                username = d.username,
                kindId = d.kindId,
                snmpCommunity = community,
            )
        }
    }

    fun addSite(name: String) {
        viewModelScope.launch {
            val site = repo.addSite(name)
            selectedSiteId.value = site.id
        }
    }

    fun addSiteAndMove(deviceId: String?, name: String) {
        if (deviceId == null) return
        viewModelScope.launch {
            val site = repo.addSite(name)
            repo.moveDevice(deviceId, site.id)
            selectedSiteId.value = site.id
        }
    }

    fun updateForm(t: (DeviceForm) -> DeviceForm) {
        form.value = t(form.value)
    }

    fun save(onDone: () -> Unit) {
        val f = form.value
        viewModelScope.launch {
            form.value = f.copy(saving = true, message = null)
            val result = repo.saveDevice(
                SaveDeviceRequest(
                    id = f.id,
                    siteId = selectedSiteId.value,
                    note = f.note,
                    host = f.host,
                    kindId = f.kindId,
                    username = f.username,
                    password = f.password.toCharArray(),
                    snmpCommunity = f.snmpCommunity,
                ),
            )
            when (result) {
                is NocResult.Ok -> {
                    form.value = DeviceForm()
                    onDone()
                }
                is NocResult.Err -> form.value = form.value.copy(saving = false, ok = false, message = result.error.userMessage)
            }
        }
    }

    fun delete(id: String, onDone: () -> Unit) {
        viewModelScope.launch {
            repo.deleteDevice(id)
            onDone()
        }
    }

    fun move(id: String, siteId: String) {
        viewModelScope.launch { repo.moveDevice(id, siteId) }
    }

    fun selectSite(id: String) {
        selectedSiteId.value = id
    }

    fun refreshSamples(id: String) {
        viewModelScope.launch {
            val list = repo.samples(id)
            samples.value = samples.value + (id to list)
        }
    }

    fun currentSite(): SiteEntity? = sites.value.firstOrNull { it.id == selectedSiteId.value } ?: sites.value.firstOrNull()
}
