package no.bellaybestia.audex.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import no.bellaybestia.audex.domain.repository.AuthRepository
import no.bellaybestia.audex.domain.repository.LoginStatus
import javax.inject.Inject

@HiltViewModel
class AddServerViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {

    val status = authRepository.loginStatus

    fun begin(url: String, name: String) {
        viewModelScope.launch { authRepository.beginLogin(url, name) }
    }

    /** Reset to Idle — call when the screen opens and after consuming a result. */
    fun reset() {
        if (status.value !is LoginStatus.Idle) authRepository.resetLogin()
    }
}
