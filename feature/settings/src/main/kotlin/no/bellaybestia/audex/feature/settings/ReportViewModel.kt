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
import no.bellaybestia.audex.domain.settings.MyReport
import no.bellaybestia.audex.domain.settings.ReportKind
import no.bellaybestia.audex.domain.settings.ReportsRepository

data class ReportUiState(
    val kind: ReportKind = ReportKind.BUG,
    val title: String = "",
    val body: String = "",
    val sending: Boolean = false,
    val result: String? = null,
    val resultIsError: Boolean = false,
)

@HiltViewModel
class ReportViewModel @Inject constructor(
    private val reports: ReportsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ReportUiState())
    val state: StateFlow<ReportUiState> = _state.asStateFlow()

    /** This device's filed reports with tracked status (refreshed on open). */
    val myReports: StateFlow<List<MyReport>> = reports.myReports
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch { runCatching { reports.refreshMyReports() } }
    }

    fun setKind(kind: ReportKind) = _state.update { it.copy(kind = kind) }
    fun setTitle(title: String) = _state.update { it.copy(title = title, result = null) }
    fun setBody(body: String) = _state.update { it.copy(body = body, result = null) }

    fun send(appVersion: String) {
        val current = _state.value
        if (current.title.isBlank() || current.sending) return
        _state.update { it.copy(sending = true, result = null) }
        viewModelScope.launch {
            runCatching {
                reports.submit(current.kind, current.title.trim(), current.body.trim(), appVersion)
            }.onSuccess { filed ->
                _state.update {
                    it.copy(
                        sending = false,
                        title = "",
                        body = "",
                        result = "Sent — report #${filed.number}. Thank you!",
                        resultIsError = false,
                    )
                }
            }.onFailure { e ->
                _state.update {
                    it.copy(
                        sending = false,
                        result = e.message ?: "Sending failed.",
                        resultIsError = true,
                    )
                }
            }
        }
    }

    private inline fun MutableStateFlow<ReportUiState>.update(
        block: (ReportUiState) -> ReportUiState,
    ) {
        value = block(value)
    }
}
