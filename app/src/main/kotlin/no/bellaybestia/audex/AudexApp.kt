package no.bellaybestia.audex

import android.app.Application
import android.content.pm.ApplicationInfo
import android.webkit.WebView
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil.ImageLoader
import coil.ImageLoaderFactory
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import no.bellaybestia.audex.data.AppStartup
import no.bellaybestia.audex.data.SocketLifecycle
import no.bellaybestia.audex.data.WorkScheduler
import no.bellaybestia.audex.player.StreamTokenResolver
import okhttp3.OkHttpClient
import javax.inject.Inject

@HiltAndroidApp
class AudexApp : Application(), Configuration.Provider, ImageLoaderFactory {

    @Inject lateinit var appStartup: AppStartup
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var workScheduler: WorkScheduler
    @Inject lateinit var socketLifecycle: SocketLifecycle
    @Inject lateinit var streamTokenResolver: StreamTokenResolver

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** On-demand WorkManager init (the default initializer is removed in the manifest). */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (debuggable) {
            // Readium logs exclusively through Timber — without a planted tree
            // every navigator/JS-console diagnostic is silently dropped.
            Timber.plant(Timber.DebugTree())
            // Lets chrome://inspect (via adb forward) attach to the reader WebView.
            WebView.setWebContentsDebuggingEnabled(true)
        }
        appScope.launch {
            appStartup.initialize()   // hydrate tokens + wire the refresher
            workScheduler.scheduleAll() // periodic library sync + session drain
        }
        // Socket.io connects per server while the app is foregrounded.
        socketLifecycle.bind()
    }

    /**
     * App-wide Coil loader for ABS cover art: same per-host Bearer resolution as
     * audio streaming, so `absCoverUrl(...)` values load against any connected
     * server (and correctly 401→skip for hosts we don't know).
     */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .okHttpClient {
                OkHttpClient.Builder()
                    .addInterceptor { chain ->
                        val token = streamTokenResolver.bearerForUrl(chain.request().url.toString())
                        val request = if (token.isNullOrBlank()) chain.request() else {
                            chain.request().newBuilder()
                                .header("Authorization", "Bearer $token")
                                .build()
                        }
                        chain.proceed(request)
                    }
                    .build()
            }
            .crossfade(true)
            .build()
}
