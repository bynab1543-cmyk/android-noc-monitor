package com.noc.monitor.work

import com.noc.monitor.data.repo.DeviceRepository
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
            repository.ensureDefaultSite()
            while (isActive) {
                try {
                    for (device in repository.allDevices()) {
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
