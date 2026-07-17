package no.bellaybestia.audex.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlin.math.roundToInt
import no.bellaybestia.audex.designsystem.CoverImage
import no.bellaybestia.audex.designsystem.PosterTile
import no.bellaybestia.audex.domain.model.Work
import no.bellaybestia.audex.domain.settings.HomeLook

@Composable
fun HomeScreen(
    onWorkClick: (Work) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val look by viewModel.look.collectAsState()
    val continueWorks by viewModel.continueWorks.collectAsState()
    val recentWorks by viewModel.recentWorks.collectAsState()
    val totalBooks by viewModel.totalBooks.collectAsState()
    val serverCount by viewModel.serverCount.collectAsState()

    if (continueWorks.isEmpty() && recentWorks.isEmpty()) {
        Column(modifier.fillMaxSize().padding(24.dp)) {
            Text("Nothing here yet", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Add a server and start a book — what you're partway through shows up here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    when (look) {
        HomeLook.NIGHTFALL ->
            NightfallHome(continueWorks, recentWorks, serverCount, onWorkClick, modifier)
        HomeLook.STACKS ->
            StacksHome(continueWorks, recentWorks, totalBooks, serverCount, onWorkClick, modifier)
    }
}

/** "Synced · 2 servers" (mockup 2a). Singular/plural, hidden with no servers. */
private fun syncedLabel(servers: Int): String? = when {
    servers <= 0 -> null
    servers == 1 -> "Synced · 1 server"
    else -> "Synced · $servers servers"
}

/* ----------------------------- NIGHTFALL ----------------------------- */

@Composable
private fun NightfallHome(
    continueWorks: List<Work>,
    recentWorks: List<Work>,
    serverCount: Int,
    onWorkClick: (Work) -> Unit,
    modifier: Modifier,
) {
    LazyColumn(state = rememberLazyListState(), modifier = modifier.fillMaxSize()) {
        syncedLabel(serverCount)?.let { synced ->
            item(key = "synced") {
                Text(
                    text = synced.uppercase(),
                    style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.4.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
                )
            }
        }
        continueWorks.firstOrNull()?.let { hero ->
            item(key = "hero") { HeroCard(hero, onWorkClick) }
        }
        if (continueWorks.size > 1) {
            item(key = "cont") { PosterRail("Keep going", continueWorks.drop(1), onWorkClick) }
        }
        if (recentWorks.isNotEmpty()) {
            item(key = "recent") { PosterRail("Recently added", recentWorks, onWorkClick) }
        }
        item(key = "tail") { Spacer(Modifier.height(24.dp)) }
    }
}

/** Full-bleed featured card for the book you're mid-way through. */
@Composable
private fun HeroCard(work: Work, onWorkClick: (Work) -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Box(
        Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .height(380.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable { onWorkClick(work) },
    ) {
        CoverImage(url = work.coverUrl, contentDescription = work.title, modifier = Modifier.fillMaxSize())
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0.0f to Color.Transparent,
                    0.5f to Color(0x55000000),
                    1.0f to Color(0xF00A0B0F),
                ),
            ),
        )
        Column(Modifier.align(Alignment.BottomStart).padding(20.dp)) {
            val pct = (furthestFraction(work).coerceIn(0f, 1f) * 100).roundToInt()
            Text(
                text = "CONTINUE · $pct%",
                style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.4.sp),
                color = accent,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = work.title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFF4F6FA),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
            subtitleOf(work)?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFB9C0CC),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                modifier = Modifier
                    .padding(top = 14.dp)
                    .clip(RoundedCornerShape(30.dp))
                    .background(accent)
                    .clickable { onWorkClick(work) }
                    .padding(horizontal = 22.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Outlined.PlayArrow,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Resume",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}

@Composable
private fun PosterRail(title: String, works: List<Work>, onWorkClick: (Work) -> Unit) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 12.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(works, key = { it.id }) { work ->
                PosterTile(
                    coverUrl = work.coverUrl,
                    title = work.title,
                    subtitle = work.authorName?.takeIf { it.isNotBlank() },
                    progress = furthestFraction(work).takeIf { it > 0f },
                    hasAudio = work.hasAudio,
                    hasEbook = work.hasEbook,
                    onClick = { onWorkClick(work) },
                    modifier = Modifier.width(132.dp),
                )
            }
        }
    }
}

/* ------------------------------- STACKS ------------------------------ */

@Composable
private fun StacksHome(
    continueWorks: List<Work>,
    recentWorks: List<Work>,
    totalBooks: Int,
    serverCount: Int,
    onWorkClick: (Work) -> Unit,
    modifier: Modifier,
) {
    LazyColumn(
        state = rememberLazyListState(),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 28.dp),
    ) {
        item(key = "head") {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp)) {
                Text(text = "Home", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                Text(
                    text = listOfNotNull(
                        "$totalBooks books · ${continueWorks.size} in progress",
                        syncedLabel(serverCount),
                    ).joinToString(" · ").uppercase(),
                    style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.4.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
        if (continueWorks.isNotEmpty()) {
            item(key = "l_cont") { StacksLabel("Continue") }
            items(continueWorks, key = { "c_${it.id}" }) { BigContinueRow(it, onWorkClick) }
        }
        if (recentWorks.isNotEmpty()) {
            item(key = "l_recent") { StacksLabel("Recently added") }
            items(recentWorks.chunked(2), key = { it.first().id }) { pair ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 9.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    pair.forEach { w ->
                        Box(Modifier.weight(1f)) {
                            PosterTile(
                                coverUrl = w.coverUrl,
                                title = w.title,
                                subtitle = w.authorName?.takeIf { it.isNotBlank() },
                                progress = furthestFraction(w).takeIf { it > 0f },
                                hasAudio = w.hasAudio,
                                hasEbook = w.hasEbook,
                                onClick = { onWorkClick(w) },
                            )
                        }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun StacksLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.6.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 22.dp, bottom = 12.dp),
    )
}

@Composable
private fun BigContinueRow(work: Work, onWorkClick: (Work) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onWorkClick(work) }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        CoverImage(
            url = work.coverUrl,
            contentDescription = work.title,
            progress = furthestFraction(work).takeIf { it > 0f },
            modifier = Modifier.width(92.dp).aspectRatio(2f / 3f),
        )
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
            Text(
                text = work.title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            subtitleOf(work)?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            val pct = (furthestFraction(work).coerceIn(0f, 1f) * 100).roundToInt()
            Text(
                text = "$pct%",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp),
            )
            Box(
                Modifier
                    .padding(top = 5.dp)
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.outlineVariant),
            ) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(furthestFraction(work).coerceIn(0f, 1f))
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
    }
}

/* ------------------------------ shared ------------------------------- */

private fun furthestFraction(work: Work): Float =
    (if (work.hasAudio) work.listenFraction else work.readFraction).toFloat()

private fun subtitleOf(work: Work): String? {
    val parts = buildList {
        work.authorName?.takeIf { it.isNotBlank() }?.let { add(it) }
        work.seriesName?.takeIf { it.isNotBlank() }?.let { series ->
            val pos = work.seriesPosition?.let { p -> if (p % 1.0 == 0.0) " #${p.toInt()}" else " #$p" }.orEmpty()
            add(series + pos)
        }
    }
    return parts.joinToString(" · ").ifBlank { null }
}
