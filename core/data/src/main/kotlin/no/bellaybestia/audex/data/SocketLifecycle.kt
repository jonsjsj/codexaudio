package no.bellaybestia.audex.data

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import no.bellaybestia.audex.auth.ServerTokenStore
import no.bellaybestia.audex.common.DefaultDispatcher
import no.bellaybestia.audex.database.ServerDao
import no.bellaybestia.audex.network.abs.AbsSocket
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds a socket.io connection per enabled server while the app is foregrounded
 * (the playback service keeps its own alive during background playback). On
 * (re)connect it reconciles progress via GET /api/me; a `user_updated` event
 * triggers the same lightweight reconcile, and library changes enqueue a sync.
 * Sockets are torn down when the app goes to the background to save battery.
 */
@Singleton
class SocketLifecycle @Inject constructor(
    private val serverDao: ServerDao,
    private val tokenStore: ServerTokenStore,
    private val librarySyncer: LibrarySyncer,
    private val workScheduler: WorkScheduler,
    @DefaultDispatcher private val dispatcher: CoroutineDispatcher,
) : DefaultLifecycleObserver {

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val sockets = ConcurrentHashMap<String, AbsSocket>()

    /** Observe the process lifecycle. Must be called on the main thread. */
    fun bind() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) = connectAll()

    override fun onStop(owner: LifecycleOwner) = disconnectAll()

    private fun connectAll() {
        scope.launch {
            for (server in serverDao.enabled()) {
                // GET /api/me reconcile on (re)connect.
                launch { runCatching { librarySyncer.reconcileProgress(server.serverId) } }
                if (sockets.containsKey(server.serverId)) continue
                val socket = AbsSocket(
                    baseUrl = server.baseUrl,
                    tokenProvider = { tokenStore.accessToken(server.serverId) },
                    onUserUpdated = {
                        scope.launch { runCatching { librarySyncer.reconcileProgress(server.serverId) } }
                    },
                    onLibraryChanged = { workScheduler.syncNow() },
                )
                runCatching { socket.connect() }
                sockets[server.serverId] = socket
            }
        }
    }

    private fun disconnectAll() {
        sockets.values.forEach { runCatching { it.disconnect() } }
        sockets.clear()
    }
}
