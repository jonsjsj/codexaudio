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

/**
 * Works of one author as a flat hairline-divided list, in the order the
 * repository delivers them. The list state is saveable-backed, so returning
 * via Back restores the scroll position (docs/02 §2.4).
 */
@Composable
fun AuthorDetailScreen(
    authorId: String,
    authorName: String? = null,
    onWorkClick: (Work) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: AuthorDetailViewModel = hiltViewModel(),
) {
    val works by viewModel.works.collectAsState()
    val listState = rememberLazyListState()

    Column(modifier.fillMaxSize()) {
        Text(
            text = authorName ?: viewModel.authorName ?: "Author",
            style = MaterialTheme.typography.headlineSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
        HorizontalDivider(
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            itemsIndexed(works, key = { _, work -> work.id }) { index, work ->
                WorkRowItem(work = work, onClick = { onWorkClick(work) })
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
