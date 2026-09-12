package no.bellaybestia.audex.feature.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import no.bellaybestia.audex.designsystem.ScreenHeader
import no.bellaybestia.audex.domain.model.Work

private fun HomeSection.title(): String = when (this) {
    HomeSection.CONTINUE -> "Continue"
    HomeSection.RECENTLY_ADDED -> "Recently added"
    HomeSection.RECENTLY_RELEASED -> "Recently released"
}

/**
 * The full, uncapped list behind a Home section header's "See all" — reuses
 * [HomeViewModel] (via Hilt's default view-model-scoped-to-this-destination
 * behavior) so it reads the exact same flows Home itself capped to 3, instead
 * of re-querying the catalog with different logic that could drift out of sync.
 */
@Composable
fun HomeSeeAllScreen(
    section: HomeSection,
    onWorkClick: (Work) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val works by when (section) {
        HomeSection.CONTINUE -> viewModel.continueWorks
        HomeSection.RECENTLY_ADDED -> viewModel.recentWorks
        HomeSection.RECENTLY_RELEASED -> viewModel.recentlyReleasedWorks
    }.collectAsState()

    Column(modifier.fillMaxSize()) {
        ScreenHeader(title = section.title(), subtitle = "${works.size} books", compact = true)
        HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
        if (works.isEmpty()) {
            Text(
                text = "Nothing here yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(20.dp),
            )
            return@Column
        }
        LazyColumn(state = rememberLazyListState(), modifier = Modifier.fillMaxSize()) {
            items(works, key = { it.id }) { w ->
                FlatWorkRow(
                    work = w,
                    showBars = section == HomeSection.CONTINUE,
                    onWorkClick = onWorkClick,
                    showYear = section == HomeSection.RECENTLY_RELEASED,
                )
            }
        }
    }
}
