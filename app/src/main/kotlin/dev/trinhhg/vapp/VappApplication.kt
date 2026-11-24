package dev.trinhhg.vapp

import android.app.Application
import androidx.work.Configuration
import dev.trinhhg.vapp.di.allAppModules
import dev.trinhhg.vapp.downloader.api.DownloadQueueManager
import dev.trinhhg.vapp.downloader.worker.VappWorkerFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin

class VappApplication :
    Application(),
    Configuration.Provider {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val workerFactory: VappWorkerFactory by inject()
    private val downloadQueueManager: DownloadQueueManager by inject()

    override val workManagerConfiguration: Configuration
        get() =
            Configuration
                .Builder()
                .setWorkerFactory(workerFactory)
                .build()

    override fun onCreate() {
        super.onCreate()
        if (GlobalContext.getOrNull() == null) {
            startKoin {
                androidContext(this@VappApplication)
                modules(allAppModules)
            }
        }
        appScope.launch {
            downloadQueueManager.recoverInterruptedTasksOnStartup()
        }
    }
}
