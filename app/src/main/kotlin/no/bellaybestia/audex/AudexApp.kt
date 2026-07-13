package no.bellaybestia.audex

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import no.bellaybestia.audex.data.AppStartup
import no.bellaybestia.audex.data.SocketLifecycle
import no.bellaybestia.audex.data.WorkScheduler
import javax.inject.Inject

@HiltAndroidApp
class AudexApp : Application(), Configuration.Provider {

    @Inject lateinit var appStartup: AppStartup
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var workScheduler: WorkScheduler
    @Inject lateinit var socketLifecycle: SocketLifecycle

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** On-demand WorkManager init (the default initializer is removed in the manifest). */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        appScope.launch {
            appStartup.initialize()   // hydrate tokens + wire the refresher
            workScheduler.scheduleAll() // periodic library sync + session drain
        }
        // Socket.io connects per server while the app is foregrounded.
        socketLifecycle.bind()
    }
}
