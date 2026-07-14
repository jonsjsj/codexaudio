package no.bellaybestia.audex.feature.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import no.bellaybestia.audex.designsystem.FlatTabRow
import no.bellaybestia.audex.domain.model.Author
import no.bellaybestia.audex.domain.model.Series
import no.bellaybestia.audex.domain.model.Work

private fun workCountLabel(count: Int): String =
    if (count == 1) "1 work" else "$count works"

@Composable
private fun FlatDivider() {
    HorizontalDivider(
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

/**
 * The Library centerpiece: flat underlined tabs (Authors / Series / All) with
 * one flat hairline-divided list per tab. The selected tab lives in the
 * ViewModel ([SavedStateHandle]); each tab's [LazyListState] is hoisted here
 * (created via rememberLazyListState, which is saveable-backed) so scroll
 * positions survive both tab switches and back-navigation.
 */
@Composable
fun LibraryScreen(
    onAuthorClick: (id: String, name: String) -> Unit,
    onSeriesClick: (id: String, name: String) -> Unit,
    onWorkClick: (Work) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val selectedTab by viewModel.selectedTab.collectAsState()
    val query by viewModel.query.collectAsState()
    val authors by viewModel.authors.collectAsState()
    val series by viewModel.series.collectAsState()
    val works by viewModel.works.collectAsState()

    // Hoisted above the tab switch so every tab keeps its scroll position
    // while another tab is showing (rememberLazyListState is rememberSaveable
    // internally, so back-navigation restores them too — docs/02 §2.4).
    val authorsListState = rememberLazyListState()
    val seriesListState = rememberLazyListState()
    val worksListState = rememberLazyListState()

    Column(modifier.fillMaxSize()) {
        FlatSearchField(query = query, onQueryChange = viewModel::setQuery)
        FlatTabRow(
            tabs = listOf("Authors", "Series", "All"),
            selectedIndex = selectedTab,
            onSelect = viewModel::selectTab,
        )
        when (selectedTab) {
            0 -> AuthorsList(authors, authorsListState, onAuthorClick)
            1 -> SeriesList(series, seriesListState, onSeriesClick)
            else -> WorksList(works, worksListState, onWorkClick)
        }
    }
}

/**
 * Flat search line: glyph + borderless text field + clear, over a hairline —
 * no pill/outline chrome per the design rules. Filters all three tabs.
 */
@Composable
private fun FlatSearchField(query: String, onQueryChange: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Search,
            contentDescription = "Search",
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text(
                    text = "Search title, author, or series",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = "Clear search",
                modifier = Modifier
                    .size(18.dp)
                    .clickable { onQueryChange("") },
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    FlatDivider()
}

@Composable
fun AuthorsList(
    authors: List<Author>,
    listState: LazyListState,
    onAuthorClick: (id: String, name: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(state = listState, modifier = modifier.fillMaxSize()) {
        itemsIndexed(authors, key = { _, author -> author.id }) { index, author ->
            Text(
                text = "${author.name} — ${workCountLabel(author.workCount)}",
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onAuthorClick(author.id, author.name) }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            )
            if (index < authors.lastIndex) FlatDivider()
        }
    }
}

@Composable
fun SeriesList(
    series: List<Series>,
    listState: LazyListState,
    onSeriesClick: (id: String, name: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(state = listState, modifier = modifier.fillMaxSize()) {
        itemsIndexed(series, key = { _, item -> item.id }) { index, item ->
            Text(
                text = "${item.name} — ${workCountLabel(item.workCount)}",
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSeriesClick(item.id, item.name) }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            )
            if (index < series.lastIndex) FlatDivider()
        }
    }
}

@Composable
fun WorksList(
    works: List<Work>,
    listState: LazyListState,
    onWorkClick: (Work) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    LazyColumn(state = listState, modifier = modifier.fillMaxSize()) {
        itemsIndexed(works, key = { _, work -> work.id }) { index, work ->
            WorkRowItem(work = work, onClick = { onWorkClick(work) })
            if (index < works.lastIndex) FlatDivider()
        }
    }
}
