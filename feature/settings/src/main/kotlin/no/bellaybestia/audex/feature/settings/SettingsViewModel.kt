package no.bellaybestia.audex.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import no.bellaybestia.audex.domain.model.ServerAccount
import no.bellaybestia.audex.domain.repository.ServerRepository

@HiltViewModel
class SettingsViewModel @Inject constructor(
    serverRepository: ServerRepository,
) : ViewModel() {

    val servers: StateFlow<List<ServerAccount>> = serverRepository.servers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
