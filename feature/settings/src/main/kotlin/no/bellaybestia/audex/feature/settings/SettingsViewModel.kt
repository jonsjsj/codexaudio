package no.bellaybestia.audex.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import no.bellaybestia.audex.domain.model.ServerAccount
import no.bellaybestia.audex.domain.reader.AlignmentRepository
import no.bellaybestia.audex.domain.reader.ReaderBarPosition
import no.bellaybestia.audex.domain.reader.ReaderPrefs
import no.bellaybestia.audex.domain.reader.ReaderSettingsStore
import no.bellaybestia.audex.domain.repository.ServerRepository
import no.bellaybestia.audex.domain.settings.AccentChoice
import no.bellaybestia.audex.domain.settings.CodexSync
import no.bellaybestia.audex.domain.settings.HomeLook
import no.bellaybestia.audex.domain.settings.ProgressUnit
import no.bellaybestia.audex.domain.settings.PlaybackSettings
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
    private val codexSync: CodexSync,
    private val playbackSettings: PlaybackSettings,
    private val readerSettings: ReaderSettingsStore,
) : ViewModel() {

    val readerPrefs: StateFlow<ReaderPrefs> = readerSettings.prefs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReaderPrefs())

    fun setReaderBarPosition(position: ReaderBarPosition) {
        viewModelScope.launch { readerSettings.set(readerPrefs.value.copy(barPosition = position)) }
    }

    val skipSilence: StateFlow<Boolean> = playbackSettings.skipSilence
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setSkipSilence(enabled: Boolean) {
        viewModelScope.launch { playbackSettings.setSkipSilence(enabled) }
    }

    val skipSeconds: StateFlow<Int> = playbackSettings.skipSeconds
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 30)

    fun setSkipSeconds(seconds: Int) {
        viewModelScope.launch { playbackSettings.setSkipSeconds(seconds) }
    }

    val mergeProgress: StateFlow<Boolean> = playbackSettings.mergeProgress
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setMergeProgress(merged: Boolean) {
        viewModelScope.launch { playbackSettings.setMergeProgress(merged) }
    }

    // Local mirrors for smooth editing; persisted on every change.
    private val _codexUrl = MutableStateFlow("")
    val codexUrl: StateFlow<String> = _codexUrl.asStateFlow()
    private val _codexToken = MutableStateFlow("")
    val codexToken: StateFlow<String> = _codexToken.asStateFlow()
    private val _codexEnabled = MutableStateFlow(false)
    val codexEnabled: StateFlow<Boolean> = _codexEnabled.asStateFlow()

    private fun persistCodex() {
        viewModelScope.launch {
            codexSync.setSettings(_codexUrl.value, _codexToken.value, _codexEnabled.value)
        }
    }

    fun setCodexUrl(url: String) { _codexUrl.value = url; persistCodex() }
    fun setCodexToken(token: String) { _codexToken.value = token; persistCodex() }
    fun setCodexEnabled(enabled: Boolean) { _codexEnabled.value = enabled; persistCodex() }

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

    fun setLook(look: HomeLook) {
        viewModelScope.launch { themeSettings.set(themePrefs.value.copy(look = look)) }
    }

    fun setProgressUnit(unit: ProgressUnit) {
        viewModelScope.launch { themeSettings.set(themePrefs.value.copy(progressUnit = unit)) }
    }

    /** Remove a server entirely (tokens, items, progress, graph rebuild). */
    fun removeServer(serverId: String) {
        viewModelScope.launch { serverRepository.removeServer(serverId) }
    }

    val servers: StateFlow<List<ServerAccount>> = serverRepository.servers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _alignServiceUrl = MutableStateFlow("")
    val alignServiceUrl: StateFlow<String> = _alignServiceUrl.asStateFlow()

    init {
        viewModelScope.launch {
            _alignServiceUrl.value = alignmentRepository.serviceUrl().orEmpty()
            val cs = codexSync.settings.first()
            _codexUrl.value = cs.url
            _codexToken.value = cs.token
            _codexEnabled.value = cs.enabled
        }
    }

    /** Edit + persist the audex-align service URL (blank disables word sync). */
    fun setAlignServiceUrl(url: String) {
        _alignServiceUrl.value = url
        viewModelScope.launch { alignmentRepository.setServiceUrl(url) }
    }
}
