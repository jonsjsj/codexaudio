package no.bellaybestia.codexaudio.feature.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import no.bellaybestia.codexaudio.domain.model.Work
import no.bellaybestia.codexaudio.domain.repository.CatalogRepository

@HiltViewModel
class AuthorDetailViewModel @Inject constructor(
    catalogRepository: CatalogRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    /** Nav argument from the "author/{id}?name={name}" route. */
    private val authorId: String = checkNotNull(savedStateHandle["id"]) {
        "AuthorDetailViewModel requires an \"id\" nav argument"
    }

    val authorName: String? = savedStateHandle["name"]

    /** Works for this author, in the order the repository delivers them. */
    val works: StateFlow<List<Work>> = catalogRepository.worksForAuthor(authorId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
