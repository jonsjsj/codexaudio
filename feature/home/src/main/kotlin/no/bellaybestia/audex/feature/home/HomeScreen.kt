package no.bellaybestia.audex.feature.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlin.math.roundToInt
import no.bellaybestia.audex.domain.model.Work

/**
 * Home: the "Continue" rail as a flat list of in-progress works. Uses its own
 * compact row on purpose — feature modules must not depend on each other.
 */
@Composable
fun HomeScreen(
    onWorkClick: (Work) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val continueWorks by viewModel.continueWorks.collectAsState()
    val listState = rememberLazyListState()

    Column(modifier.fillMaxSize()) {
        Text(
            text = "Continue",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
        if (continueWorks.isEmpty()) {
            Text(
                text = "Nothing in progress yet — add a server and start listening or " +
                    "reading; books you're partway through appear here to pick back up.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            )
            return
        }
        HorizontalDivider(
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            itemsIndexed(continueWorks, key = { _, work -> work.id }) { index, work ->
                ContinueRow(work = work, onClick = { onWorkClick(work) })
                if (index < continueWorks.lastIndex) {
                    HorizontalDivider(
                        thickness = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ContinueRow(work: Work, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = work.title,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        work.authorName?.takeIf { it.isNotBlank() }?.let { author ->
            Text(
                text = author,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (work.hasAudio) {
                InlineProgress(
                    icon = Icons.Outlined.Headphones,
                    contentDescription = "Listen progress",
                    fraction = work.listenFraction,
                )
            }
            if (work.hasAudio && work.hasEbook) {
                Text(
                    text = " · ",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (work.hasEbook) {
                InlineProgress(
                    icon = Icons.Outlined.MenuBook,
                    contentDescription = "Read progress",
                    fraction = work.readFraction,
                )
            }
        }
    }
}

@Composable
private fun InlineProgress(
    icon: ImageVector,
    contentDescription: String,
    fraction: Double,
) {
    val percent = (fraction.coerceIn(0.0, 1.0) * 100).roundToInt()
    Icon(
        imageVector = icon,
        contentDescription = contentDescription,
        modifier = Modifier.size(14.dp),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.width(4.dp))
    Text(
        text = "$percent%",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
