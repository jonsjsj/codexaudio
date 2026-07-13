package no.bellaybestia.audex

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import no.bellaybestia.audex.data.AppStartup
import javax.inject.Inject

@HiltAndroidApp
class AudexApp : Application() {

    @Inject
    lateinit var appStartup: AppStartup

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        // Hydrate the token cache and wire the refresher before any UI runs.
        appScope.launch { appStartup.initialize() }
    }
}
