package no.bellaybestia.audex.feature.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import no.bellaybestia.audex.designsystem.PosterTile
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import no.bellaybestia.audex.designsystem.FlatTabRow
import no.bellaybestia.audex.designsystem.ScreenHeader
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
    val sort by viewModel.sort.collectAsState()
    val filter by viewModel.filter.collectAsState()
    val authors by viewModel.authors.collectAsState()
    val series by viewModel.series.collectAsState()
    val works by viewModel.works.collectAsState()

    // Hoisted above the tab switch so every tab keeps its scroll position
    // while another tab is showing (rememberLazyListState is rememberSaveable
    // internally, so back-navigation restores them too — docs/02 §2.4).
    val authorsListState = rememberLazyListState()
    val seriesListState = rememberLazyListState()
    val worksGridState = rememberLazyGridState()

    Column(modifier.fillMaxSize()) {
        // "15 works" (mockup 2b) — reflects the active search/filter, so it
        // doubles as the result count while you're narrowing things down.
        ScreenHeader(
            title = "Library",
            subtitle = when (works.size) {
                0 -> null
                1 -> "1 work"
                else -> "${works.size} works"
            },
        )
        FlatSearchField(query = query, onQueryChange = viewModel::setQuery)
        FlatTabRow(
            tabs = listOf("Authors", "Series", "All"),
            selectedIndex = selectedTab,
            onSelect = viewModel::selectTab,
        )
        when (selectedTab) {
            0 -> AuthorsList(authors, authorsListState, onAuthorClick)
            1 -> SeriesList(series, seriesListState, onSeriesClick)
            else -> Column {
                SortRow(selected = sort, onSelect = viewModel::setSort)
                FilterRow(selected = filter, onSelect = viewModel::setFilter)
                WorksList(
                    works = works,
                    gridState = worksGridState,
                    onWorkClick = onWorkClick,
                    // Author shelf order reads best with section headers per author
                    // (Codex's grouped browse); explicit sorts stay a flat list.
                    groupByAuthor = sort == WorkSort.AUTHOR.ordinal,
                )
            }
        }
    }
}

/** Flat quick-filter selector, same language as SortRow. */
@Composable
private fun FilterRow(selected: Int, onSelect: (WorkFilter) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Show",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        listOf(
            WorkFilter.ALL to "All",
            WorkFilter.AUDIO to "Audio",
            WorkFilter.EBOOK to "Ebooks",
            WorkFilter.IN_PROGRESS to "In progress",
        ).forEach { (option, label) ->
            val active = selected == option.ordinal
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (active) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clickable { onSelect(option) }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
    }
    FlatDivider()
}

/** Flat sort selector for the All tab: plain labels, the active one in accent. */
@Composable
private fun SortRow(selected: Int, onSelect: (WorkSort) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Sort",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        listOf(
            WorkSort.AUTHOR to "Author",
            WorkSort.TITLE to "Title",
            WorkSort.RECENT to "Recent",
        ).forEach { (option, label) ->
            val active = selected == option.ordinal
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (active) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clickable { onSelect(option) }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
    }
    FlatDivider()
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
            NameCountRow(
                name = author.name,
                count = author.workCount,
                onClick = { onAuthorClick(author.id, author.name) },
            )
            if (index < authors.lastIndex) FlatDivider()
        }
    }
}

/** Bolder browse row: a strong title over a muted work-count subline. */
@Composable
private fun NameCountRow(name: String, count: Int, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = workCountLabel(count),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
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
            NameCountRow(
                name = item.name,
                count = item.workCount,
                onClick = { onSeriesClick(item.id, item.name) },
            )
            if (index < series.lastIndex) FlatDivider()
        }
    }
}

/**
 * The library as a cover grid — the change that makes it read like a media app
 * instead of a settings list. Responsive columns (adaptive to width), a 2:3
 * poster per work with a progress ribbon, and, when sorted by author, a
 * full-width author header spanning the row above each block.
 */
@Composable
fun WorksList(
    works: List<Work>,
    gridState: LazyGridState,
    onWorkClick: (Work) -> Unit = {},
    modifier: Modifier = Modifier,
    groupByAuthor: Boolean = false,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 108.dp),
        state = gridState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        if (groupByAuthor) {
            works.forEachIndexed { index, work ->
                val author = work.authorName ?: "Unknown author"
                val previous = works.getOrNull(index - 1)
                if (previous == null || (previous.authorName ?: "Unknown author") != author) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "author_$author") {
                        Text(
                            text = author.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp),
                        )
                    }
                }
                item(key = work.id) { WorkPoster(work, onWorkClick) }
            }
        } else {
            items(works, key = { it.id }) { work -> WorkPoster(work, onWorkClick) }
        }
    }
}

/** One grid cell: a work as a poster with its furthest-along fraction as ribbon. */
@Composable
private fun WorkPoster(work: Work, onWorkClick: (Work) -> Unit) {
    val fraction = when {
        work.hasAudio -> work.listenFraction
        work.hasEbook -> work.readFraction
        else -> 0.0
    }.toFloat()
    PosterTile(
        coverUrl = work.coverUrl,
        title = work.title,
        subtitle = work.authorName?.takeIf { it.isNotBlank() },
        progress = fraction.takeIf { it > 0f },
        hasAudio = work.hasAudio,
        hasEbook = work.hasEbook,
        onClick = { onWorkClick(work) },
    )
}
