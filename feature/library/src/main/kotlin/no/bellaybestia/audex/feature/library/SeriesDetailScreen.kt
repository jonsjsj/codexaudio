package no.bellaybestia.audex.feature.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import no.bellaybestia.audex.domain.model.Work

private fun Work.isFinished(): Boolean =
    listenFraction >= 0.99 || readFraction >= 0.99

/**
 * Volumes of one series in delivered (position) order, with position labels
 * in each row's subline and an "x of y finished" rollup header (a work counts
 * as finished when either format's fraction is >= 0.99 — docs/02 §2.1).
 */
@Composable
fun SeriesDetailScreen(
    seriesId: String,
    seriesName: String? = null,
    modifier: Modifier = Modifier,
    viewModel: SeriesDetailViewModel = hiltViewModel(),
) {
    val works by viewModel.works.collectAsState()
    val listState = rememberLazyListState()
    val finishedCount = works.count { it.isFinished() }

    Column(modifier.fillMaxSize()) {
        Text(
            text = seriesName ?: viewModel.seriesName ?: "Series",
            style = MaterialTheme.typography.headlineSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
        Text(
            text = "$finishedCount of ${works.size} finished",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
        )
        HorizontalDivider(
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            itemsIndexed(works, key = { _, work -> work.id }) { index, work ->
                WorkRowItem(work = work)
                if (index < works.lastIndex) {
                    HorizontalDivider(
                        thickness = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
            }
        }
    }
}
