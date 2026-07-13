package no.bellaybestia.audex.feature.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import no.bellaybestia.audex.domain.download.Downloads
import no.bellaybestia.audex.domain.reader.EbookProgressWriter
import javax.inject.Inject

@HiltViewModel
class ReaderViewModel @Inject constructor(
    private val downloads: Downloads,
    private val ebookProgressWriter: EbookProgressWriter,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val serverId: String = checkNotNull(savedStateHandle["serverId"])
    private val libraryItemId: String = checkNotNull(savedStateHandle["itemId"])
    val title: String = savedStateHandle.get<String>("title")?.takeIf { it.isNotBlank() } ?: "Reader"

    private val _ebookPath = MutableStateFlow<String?>(null)
    val ebookPath: StateFlow<String?> = _ebookPath.asStateFlow()

    init {
        viewModelScope.launch { _ebookPath.value = downloads.localEbookPath(serverId, libraryItemId) }
    }

    /**
     * Record a reading position (the Readium navigator will call this on every
     * locator change, debounced). Routes through the offline-safe ebook queue —
     * never the audio path.
     */
    fun savePosition(location: String, progress: Double, isFinished: Boolean = false) {
        viewModelScope.launch {
            ebookProgressWriter.record(serverId, libraryItemId, location, progress, isFinished)
        }
    }
}
