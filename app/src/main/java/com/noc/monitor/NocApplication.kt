package com.noc.monitor

import android.app.Application
import androidx.room.Room
import com.noc.monitor.data.crypto.CredentialStore
import com.noc.monitor.data.local.NocDatabase
import com.noc.monitor.data.repo.DeviceRepository
import com.noc.monitor.work.DevicePoller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class NocApplication : Application() {
    lateinit var database: NocDatabase
        private set
    lateinit var credentials: CredentialStore
        private set
    lateinit var repository: DeviceRepository
        private set
    lateinit var poller: DevicePoller
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        database = Room.databaseBuilder(this, NocDatabase::class.java, "noc-monitor.db")
            .fallbackToDestructiveMigration()
            .build()
        credentials = CredentialStore(this)
        repository = DeviceRepository(database, credentials)
        poller = DevicePoller(repository, appScope)
        poller.start()
    }
}
