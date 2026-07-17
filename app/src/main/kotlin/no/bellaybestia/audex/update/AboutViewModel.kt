package no.bellaybestia.audex.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Drives the About page's live "check for updates" section. Unlike the launch
 * [UpdateViewModel] (which silently surfaces a dialog only when a newer build
 * exists), this exposes every state — checking, up to date, unreachable, or a
 * newer version with its notes — and a manual re-check the user can trigger.
 */
@HiltViewModel
class AboutViewModel @Inject constructor(
    private val updates: UpdateManager,
) : ViewModel() {

    sealed interface State {
        data object Checking : State
        data object UpToDate : State
        data object Unreachable : State
        data class Available(val info: UpdateManager.UpdateInfo) : State
        data class Downloading(val progress: Float) : State
    }

    private val _state = MutableStateFlow<State>(State.Checking)
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        check()
    }

    /** Re-query the update host(s) live. */
    fun check() {
        viewModelScope.launch {
            _state.value = State.Checking
            _state.value = when (val r = updates.checkDetailed()) {
                is UpdateManager.CheckResult.Available -> State.Available(r.info)
                UpdateManager.CheckResult.UpToDate -> State.UpToDate
                UpdateManager.CheckResult.Unreachable -> State.Unreachable
            }
        }
    }

    /** Download the advertised APK and hand it to the system installer. */
    fun install() {
        val current = _state.value
        if (current !is State.Available) return
        viewModelScope.launch {
            _state.value = State.Downloading(0f)
            val ok = runCatching {
                updates.downloadAndInstall(current.info) { p ->
                    _state.value = State.Downloading(p)
                }
            }.isSuccess
            // Installer (separate system UI) has taken over on success; on failure
            // fall back to the offer so the user can retry.
            _state.value = if (ok) State.UpToDate else current
        }
    }
}
