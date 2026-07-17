package no.bellaybestia.audex

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import no.bellaybestia.audex.update.UpdateCheckWorker
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import no.bellaybestia.audex.auth.AbsOidcFlow
import no.bellaybestia.audex.data.TestSeeder
import no.bellaybestia.audex.designsystem.AudexAccent
import no.bellaybestia.audex.designsystem.AudexTheme
import no.bellaybestia.audex.domain.repository.AuthRepository
import no.bellaybestia.audex.domain.settings.AccentChoice
import no.bellaybestia.audex.domain.settings.ThemeMode
import no.bellaybestia.audex.domain.settings.ThemePrefs
import no.bellaybestia.audex.domain.settings.ThemeSettings
import javax.inject.Inject

// FragmentActivity (not plain ComponentActivity) so the Readium
// EpubNavigatorFragment has a FragmentManager to live in.
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject
    lateinit var authRepository: AuthRepository

    @Inject
    lateinit var themeSettings: ThemeSettings

    @Inject
    lateinit var testSeeder: TestSeeder

    @Inject
    lateinit var playbackController: no.bellaybestia.audex.domain.playback.PlaybackController

    @Inject
    lateinit var catalogRepository: no.bellaybestia.audex.domain.repository.CatalogRepository

    /**
     * The cover the whole app takes its palette from ("Evolved" design): what's
     * playing right now, else the book you last had open. Null → the default
     * Steel-World hue. Distinct-until-changed so the palette only re-tints on a
     * real book change, not on every playback tick.
     */
    private val themeCoverUrl: kotlinx.coroutines.flow.Flow<String?> by lazy {
        kotlinx.coroutines.flow.combine(
            playbackController.state,
            catalogRepository.works(),
        ) { playback, works ->
            playback.coverUrl?.takeIf { playback.hasItem }
                ?: works
                    .filter { maxOf(it.listenFraction, it.readFraction).let { f -> f > 0.0 && f < 0.99 } }
                    .maxByOrNull { it.listenedAt ?: Long.MIN_VALUE }
                    ?.coverUrl
        }.distinctUntilChanged()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // targetSdk 35 forces edge-to-edge on Android 15; opt in explicitly so
        // every version behaves the same. AppNav then consumes the system-bar
        // insets (status bar on content, gesture nav under the tab bar) so no
        // control ever sits beneath an Android system element.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handleOAuthRedirect(intent)
        handleTestSeed(intent)
        requestNotificationPermission()
        scheduleUpdateCheck()
        setContent {
            val prefs by themeSettings.prefs.collectAsState(initial = ThemePrefs())
            val playingCover by themeCoverUrl.collectAsState(initial = null)
            // A screen (e.g. a book's page) can take the palette over while open;
            // otherwise we follow what's playing / was last open.
            val screenCover = remember { mutableStateOf<String?>(null) }
            val coverUrl = screenCover.value ?: playingCover
            val dark = when (prefs.mode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            val accent = when (prefs.accent) {
                AccentChoice.COVER -> AudexAccent.COVER
                AccentChoice.MONO -> AudexAccent.MONO
                AccentChoice.BLUE -> AudexAccent.BLUE
                AccentChoice.GOLD -> AudexAccent.GOLD
                AccentChoice.CYAN -> AudexAccent.CYAN
            }
            AudexTheme(darkTheme = dark, accent = accent, coverUrl = coverUrl) {
                androidx.compose.runtime.CompositionLocalProvider(
                    no.bellaybestia.audex.designsystem.LocalThemeCover provides screenCover,
                ) {
                    AppNav()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOAuthRedirect(intent)
    }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** API 33+ needs a runtime grant for the update + download notifications. */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /**
     * "New version ready" notification check (Codex Companion pattern). Two work
     * items: a periodic 6h background check, PLUS an immediate one-time check on
     * every launch — a PeriodicWorkRequest's first run is deferred (often hours,
     * and Doze defers it further), so without the launch check a freshly
     * published version would essentially never notify promptly.
     */
    private fun scheduleUpdateCheck() {
        val connected =
            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val wm = WorkManager.getInstance(this)
        wm.enqueueUniquePeriodicWork(
            UpdateCheckWorker.UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<UpdateCheckWorker>(6, TimeUnit.HOURS)
                .setConstraints(connected)
                .build(),
        )
        wm.enqueueUniqueWork(
            "${UpdateCheckWorker.UNIQUE_NAME}-now",
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<UpdateCheckWorker>()
                .setConstraints(connected)
                .build(),
        )
    }

    /**
     * TEST-ONLY seam: `am start … --es seedUrl <abs> --es seedToken <jwt>` seeds
     * a signed-in session so automated UI testing on an emulator skips the OIDC
     * login. Hard-gated to debug builds ON AN EMULATOR — inert on a real device,
     * even though release APKs are built in debug mode.
     */
    private fun handleTestSeed(intent: Intent?) {
        if (!BuildConfig.DEBUG || !isEmulator()) return
        val url = intent?.getStringExtra("seedUrl") ?: return
        val token = intent.getStringExtra("seedToken") ?: return
        val refresh = intent.getStringExtra("seedRefresh").orEmpty()
        val userId = intent.getStringExtra("seedUserId")
        val name = intent.getStringExtra("seedName") ?: "Test server"
        lifecycleScope.launch { runCatching { testSeeder.seed(url, name, token, refresh, userId) } }
    }

    private fun isEmulator(): Boolean =
        Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.contains("emulator", ignoreCase = true) ||
            Build.MODEL.contains("Emulator", ignoreCase = true) ||
            Build.MODEL.contains("Android SDK built for", ignoreCase = true) ||
            Build.PRODUCT.contains("sdk") ||
            Build.HARDWARE.contains("goldfish") ||
            Build.HARDWARE.contains("ranchu")

    /** Catch the `audiobookshelf://oauth?...` Custom Tab redirect and finish the login. */
    private fun handleOAuthRedirect(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != AbsOidcFlow.REDIRECT_SCHEME) return
        val url = data.toString()
        lifecycleScope.launch { authRepository.completeLogin(url) }
    }
}
