package no.bellaybestia.audex.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import no.bellaybestia.audex.domain.model.ServerAccount
import no.bellaybestia.audex.domain.reader.AlignmentRepository
import no.bellaybestia.audex.domain.repository.ServerRepository
import no.bellaybestia.audex.domain.settings.AccentChoice
import no.bellaybestia.audex.domain.settings.ThemeMode
import no.bellaybestia.audex.domain.settings.ThemePrefs
import no.bellaybestia.audex.domain.settings.ThemeSettings
import no.bellaybestia.audex.domain.settings.UpdateChannel
import no.bellaybestia.audex.domain.settings.UpdateSettings

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val serverRepository: ServerRepository,
    private val alignmentRepository: AlignmentRepository,
    private val themeSettings: ThemeSettings,
    private val updateSettings: UpdateSettings,
) : ViewModel() {

    val themePrefs: StateFlow<ThemePrefs> = themeSettings.prefs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThemePrefs())

    val updateChannel: StateFlow<UpdateChannel> = updateSettings.channel
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UpdateChannel.STABLE)

    fun setUpdateChannel(channel: UpdateChannel) {
        viewModelScope.launch { updateSettings.setChannel(channel) }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { themeSettings.set(themePrefs.value.copy(mode = mode)) }
    }

    fun setAccent(accent: AccentChoice) {
        viewModelScope.launch { themeSettings.set(themePrefs.value.copy(accent = accent)) }
    }

    /** Remove a server entirely (tokens, items, progress, graph rebuild). */
    fun removeServer(serverId: String) {
        viewModelScope.launch { serverRepository.removeServer(serverId) }
    }

    val servers: StateFlow<List<ServerAccount>> = serverRepository.servers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _alignServiceUrl = MutableStateFlow("")
    val alignServiceUrl: StateFlow<String> = _alignServiceUrl.asStateFlow()

    private val _codexUrl = MutableStateFlow("")
    val codexUrl: StateFlow<String> = _codexUrl.asStateFlow()

    private val _codexFetch = MutableStateFlow<CodexFetch>(CodexFetch.Idle)
    val codexFetch: StateFlow<CodexFetch> = _codexFetch.asStateFlow()

    init {
        viewModelScope.launch {
            _alignServiceUrl.value = alignmentRepository.serviceUrl().orEmpty()
            _codexUrl.value = alignmentRepository.codexUrl().orEmpty()
        }
    }

    /** Edit + persist the audex-align service URL (blank disables word sync). */
    fun setAlignServiceUrl(url: String) {
        _alignServiceUrl.value = url
        viewModelScope.launch { alignmentRepository.setServiceUrl(url) }
    }

    fun setCodexUrl(url: String) {
        _codexUrl.value = url
        if (_codexFetch.value != CodexFetch.Idle) _codexFetch.value = CodexFetch.Idle
    }

    /** Ask Codex for its word-sync server and, if it has one, fill it in above. */
    fun fetchWordSyncFromCodex() {
        _codexFetch.value = CodexFetch.Loading
        viewModelScope.launch {
            alignmentRepository.fetchServiceUrlFromCodex(_codexUrl.value).fold(
                onSuccess = { url ->
                    if (url.isNullOrBlank()) {
                        _codexFetch.value = CodexFetch.Error("Codex has no word-sync server set up.")
                    } else {
                        setAlignServiceUrl(url)
                        _codexFetch.value = CodexFetch.Success(url)
                    }
                },
                onFailure = { _codexFetch.value = CodexFetch.Error(it.message ?: "Couldn't reach Codex.") },
            )
        }
    }
}

/** Result of a "get word-sync URL from Codex" attempt, for the settings UI. */
sealed interface CodexFetch {
    data object Idle : CodexFetch
    data object Loading : CodexFetch
    data class Success(val url: String) : CodexFetch
    data class Error(val message: String) : CodexFetch
}
