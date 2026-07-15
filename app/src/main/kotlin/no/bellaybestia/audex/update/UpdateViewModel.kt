package no.bellaybestia.audex.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class UpdateViewModel @Inject constructor(
    private val updates: UpdateManager,
) : ViewModel() {

    sealed interface State {
        data object Idle : State
        data class Available(val info: UpdateManager.UpdateInfo) : State
        data class Downloading(val progress: Float) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            updates.check()?.let { _state.value = State.Available(it) }
        }
    }

    fun install() {
        val current = _state.value
        if (current !is State.Available) return
        viewModelScope.launch {
            _state.value = State.Downloading(0f)
            runCatching {
                updates.downloadAndInstall(current.info) { p ->
                    _state.value = State.Downloading(p)
                }
            }
            // Installer (a separate system UI) has taken over — reset the banner.
            _state.value = State.Idle
        }
    }

    fun dismiss() {
        _state.value = State.Idle
    }
}
