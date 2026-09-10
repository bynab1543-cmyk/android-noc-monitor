package com.noc.monitor.work

import com.noc.monitor.data.repo.DeviceRepository
import com.noc.monitor.protocol.OperatingMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class DevicePoller(
    private val repository: DeviceRepository,
    private val scope: CoroutineScope,
    private val intervalMs: Long = 3_000,
) {
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                try {
                    val devices = repository.allDevices()
                    for (device in devices) {
                        if (repository.mode == OperatingMode.DEMO && device.id != com.noc.monitor.demo.DEMO_DEVICE_ID) {
                            continue
                        }
                        if (repository.mode == OperatingMode.REAL && device.id == com.noc.monitor.demo.DEMO_DEVICE_ID) {
                            continue
                        }
                        try {
                            repository.pollDevice(device)
                        } catch (_: Throwable) {
                        }
                    }
                } catch (_: Throwable) {
                }
                delay(intervalMs)
            }
        }
    }
}
