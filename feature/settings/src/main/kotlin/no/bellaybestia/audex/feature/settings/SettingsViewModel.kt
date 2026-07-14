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

@HiltViewModel
class SettingsViewModel @Inject constructor(
    serverRepository: ServerRepository,
    private val alignmentRepository: AlignmentRepository,
) : ViewModel() {

    val servers: StateFlow<List<ServerAccount>> = serverRepository.servers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _alignServiceUrl = MutableStateFlow("")
    val alignServiceUrl: StateFlow<String> = _alignServiceUrl.asStateFlow()

    init {
        viewModelScope.launch {
            _alignServiceUrl.value = alignmentRepository.serviceUrl().orEmpty()
        }
    }

    /** Edit + persist the audex-align service URL (blank disables word sync). */
    fun setAlignServiceUrl(url: String) {
        _alignServiceUrl.value = url
        viewModelScope.launch { alignmentRepository.setServiceUrl(url) }
    }
}
