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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import no.bellaybestia.audex.domain.model.UpcomingItem
import no.bellaybestia.audex.domain.model.Work
import no.bellaybestia.audex.domain.settings.HomeLook
import no.bellaybestia.audex.domain.settings.HomeSection

@Composable
fun HomeScreen(
    onWorkClick: (Work) -> Unit = {},
    onOpenReader: (String, String, String) -> Unit = { _, _, _ -> },
    onOpenPlayer: () -> Unit = {},
    onSeeAll: (HomeSection) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val look by viewModel.look.collectAsState()
    val continueWorks by viewModel.continueWorks.collectAsState()
    val recentWorks by viewModel.recentWorks.collectAsState()
    val recentlyReleasedWorks by viewModel.recentlyReleasedWorks.collectAsState()
    val upcomingWorks by viewModel.upcomingWorks.collectAsState()
    val hiddenSections by viewModel.hiddenSections.collectAsState()
    val totalBooks by viewModel.totalBooks.collectAsState()
    val serverCount by viewModel.serverCount.collectAsState()
    var showEdit by remember { mutableStateOf(false) }

    // Resume jumps straight into the full-screen experience: the reader for
    // an ebook, the player for an audiobook — not just a mini-player.
    LaunchedEffect(Unit) {
        viewModel.openReader.collect { onOpenReader(it.serverId, it.libraryItemId, it.title) }
    }
    LaunchedEffect(Unit) {
        viewModel.openPlayer.collect { onOpenPlayer() }
    }

    if (showEdit) {
        HomeEditDialog(
            hiddenSections = hiddenSections,
            onSetHidden = viewModel::setSectionHidden,
            onDismiss = { showEdit = false },
        )
    }

    // A section only actually shows when it's both non-empty AND not turned
    // off via Edit — an empty section renders nothing either way, so "nothing
    // here yet" only fires when there's truly nothing left to show.
    fun visible(section: HomeSection) = section !in hiddenSections
    val shownContinue = continueWorks.takeIf { visible(HomeSection.CONTINUE) } ?: emptyList()
    val shownRecent = recentWorks.takeIf { visible(HomeSection.RECENTLY_ADDED) } ?: emptyList()
    val shownReleased = recentlyReleasedWorks.takeIf { visible(HomeSection.RECENTLY_RELEASED) } ?: emptyList()
    val shownUpcoming = upcomingWorks.takeIf { visible(HomeSection.UPCOMING) } ?: emptyList()

    if (shownContinue.isEmpty() && shownRecent.isEmpty() && shownReleased.isEmpty() && shownUpcoming.isEmpty()) {
        Column(modifier.fillMaxSize().padding(24.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Nothing here yet", style = MaterialTheme.typography.headlineSmall)
                IconButton(onClick = { showEdit = true }) {
                    Icon(Icons.Outlined.Edit, contentDescription = "Edit Home")
                }
            }
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
            NightfallHome(
                shownContinue, shownRecent, shownReleased, shownUpcoming, serverCount,
                onWorkClick, viewModel::resume, onSeeAll, { showEdit = true }, modifier,
            )
        HomeLook.STACKS ->
            StacksHome(
                shownContinue, shownRecent, shownReleased, shownUpcoming, totalBooks, serverCount,
                onWorkClick, viewModel::resume, onSeeAll, { showEdit = true }, modifier,
            )
    }
}

/** Home's "Edit" affordance: a plain checklist of the 4 sections, each with a
 *  Switch — no reordering, just show/hide, per the ask ("add and remove these
 *  elements"). Persisted immediately per-toggle via [HomeViewModel.setSectionHidden]
 *  rather than needing a separate Save action. */
@Composable
private fun HomeEditDialog(
    hiddenSections: Set<HomeSection>,
    onSetHidden: (HomeSection, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val sections = listOf(
        HomeSection.CONTINUE to "Continue",
        HomeSection.RECENTLY_ADDED to "Recently added",
        HomeSection.RECENTLY_RELEASED to "Recently released",
        HomeSection.UPCOMING to "Upcoming releases",
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Home sections") },
        text = {
            Column {
                sections.forEach { (section, label) ->
                    val shown = section !in hiddenSections
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                        Switch(checked = shown, onCheckedChange = { onSetHidden(section, !it) })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
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
    recentlyReleasedWorks: List<Work>,
    upcomingWorks: List<UpcomingItem>,
    serverCount: Int,
    onWorkClick: (Work) -> Unit,
    onResume: (Work) -> Unit,
    onSeeAll: (HomeSection) -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier,
) {
    LazyColumn(state = rememberLazyListState(), modifier = modifier.fillMaxSize()) {
        item(key = "synced") {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = syncedLabel(serverCount)?.uppercase().orEmpty(),
                    style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.4.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                IconButton(onClick = onEdit) {
                    Icon(
                        Icons.Outlined.Edit,
                        contentDescription = "Edit Home",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        continueWorks.firstOrNull()?.let { hero ->
            item(key = "hero") { HeroCard(hero, onWorkClick, onResume) }
        }
        // Below the hero: flat hairline rows per the mockup (2a) — the rest of
        // in-progress books with dual listen/read bars, then discovery
        // sections. Each is capped to HOME_SECTION_PREVIEW_COUNT here; the
        // section header opens the full, uncapped list.
        if (continueWorks.size > 1) {
            item(key = "l_cont") {
                SectionEyebrow("Continue", onClick = { onSeeAll(HomeSection.CONTINUE) })
            }
            items(
                continueWorks.drop(1).take(HOME_SECTION_PREVIEW_COUNT),
                key = { "c_${it.id}" },
            ) { w -> FlatWorkRow(w, showBars = true, onWorkClick) }
        }
        if (recentWorks.isNotEmpty()) {
            item(key = "l_recent") {
                SectionEyebrow("Recently added", onClick = { onSeeAll(HomeSection.RECENTLY_ADDED) })
            }
            items(
                recentWorks.take(HOME_SECTION_PREVIEW_COUNT),
                key = { "r_${it.id}" },
            ) { w -> FlatWorkRow(w, showBars = false, onWorkClick) }
        }
        if (recentlyReleasedWorks.isNotEmpty()) {
            item(key = "l_released") {
                SectionEyebrow("Recently released", onClick = { onSeeAll(HomeSection.RECENTLY_RELEASED) })
            }
            items(
                recentlyReleasedWorks.take(HOME_SECTION_PREVIEW_COUNT),
                key = { "rr_${it.id}" },
            ) { w -> FlatWorkRow(w, showBars = false, onWorkClick, showYear = true) }
        }
        if (upcomingWorks.isNotEmpty()) {
            item(key = "l_upcoming") {
                SectionEyebrow("Upcoming releases", onClick = { onSeeAll(HomeSection.UPCOMING) })
            }
            items(
                upcomingWorks.take(HOME_SECTION_PREVIEW_COUNT),
                key = { "u_${it.mediaId}" },
            ) { UpcomingRow(it) }
        }
        item(key = "tail") { Spacer(Modifier.height(24.dp)) }
    }
}

/** Full-bleed featured card for the book you're mid-way through. */
@Composable
private fun HeroCard(work: Work, onWorkClick: (Work) -> Unit, onResume: (Work) -> Unit) {
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
                    .clickable { onResume(work) }
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

/** Mockup 2a section eyebrow: letter-spaced muted caps over a hairline. Tapping
 *  it opens the full, uncapped list for this section ("See all"). */
@Composable
private fun SectionEyebrow(text: String, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 20.dp, end = 20.dp, top = 18.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = text.uppercase(),
                style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 2.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "See all",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/**
 * Flat hairline row (mockup 2a): small cover + title + subtitle, with dual
 * listen/read bars when [showBars] (Continue) or plain when off (Recently added).
 */
@Composable
internal fun FlatWorkRow(
    work: Work,
    showBars: Boolean,
    onWorkClick: (Work) -> Unit,
    showYear: Boolean = false,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onWorkClick(work) }
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CoverImage(
                url = work.coverUrl,
                contentDescription = work.title,
                progress = if (showBars) furthestFraction(work).takeIf { it > 0f } else null,
                hasAudio = work.hasAudio,
                hasEbook = work.hasEbook,
                modifier = Modifier.size(width = 48.dp, height = 66.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
                Text(
                    text = work.title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val subtitle = listOfNotNull(
                    subtitleOf(work),
                    work.year?.takeIf { showYear }?.toString(),
                ).joinToString(" · ").ifBlank { null }
                subtitle?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (showBars && work.hasAudio) {
                    FlatFormatBar(Icons.Outlined.Headphones, "Listen progress", work.listenFraction)
                }
                if (showBars && work.hasEbook) {
                    FlatFormatBar(Icons.Outlined.MenuBook, "Read progress", work.readFraction)
                }
            }
        }
        HorizontalDivider(
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp),
        )
    }
}

/**
 * A book Codex knows about but you don't own yet (upcoming, in a series/by an
 * author you follow there) — same flat-row shape as [FlatWorkRow] but not
 * clickable (there's no local catalog entry to open) and showing a release
 * date instead of progress.
 */
@Composable
internal fun UpcomingRow(item: UpcomingItem) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CoverImage(
                url = item.coverUrl,
                contentDescription = item.title,
                modifier = Modifier.size(width = 48.dp, height = 66.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                item.releaseDate?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        HorizontalDivider(
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp),
        )
    }
}

@Composable
private fun FlatFormatBar(icon: ImageVector, contentDescription: String, fraction: Double) {
    val clamped = fraction.coerceIn(0.0, 1.0).toFloat()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(13.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f).height(2.dp).background(MaterialTheme.colorScheme.outlineVariant)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(clamped).background(MaterialTheme.colorScheme.primary))
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = "${(clamped * 100).roundToInt()}%",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/* ------------------------------- STACKS ------------------------------ */

@Composable
private fun StacksHome(
    continueWorks: List<Work>,
    recentWorks: List<Work>,
    recentlyReleasedWorks: List<Work>,
    upcomingWorks: List<UpcomingItem>,
    totalBooks: Int,
    serverCount: Int,
    onWorkClick: (Work) -> Unit,
    onResume: (Work) -> Unit,
    onSeeAll: (HomeSection) -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier,
) {
    LazyColumn(
        state = rememberLazyListState(),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 28.dp),
    ) {
        item(key = "head") {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 20.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
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
                IconButton(onClick = onEdit) {
                    Icon(
                        Icons.Outlined.Edit,
                        contentDescription = "Edit Home",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (continueWorks.isNotEmpty()) {
            item(key = "l_cont") { StacksLabel("Continue") { onSeeAll(HomeSection.CONTINUE) } }
            items(
                continueWorks.take(HOME_SECTION_PREVIEW_COUNT),
                key = { "c_${it.id}" },
            ) { BigContinueRow(it, onWorkClick) }
        }
        if (recentWorks.isNotEmpty()) {
            item(key = "l_recent") { StacksLabel("Recently added") { onSeeAll(HomeSection.RECENTLY_ADDED) } }
            items(
                recentWorks.take(HOME_SECTION_PREVIEW_COUNT).chunked(2),
                key = { it.first().id },
            ) { pair -> PosterRow(pair, onWorkClick) }
        }
        if (recentlyReleasedWorks.isNotEmpty()) {
            item(key = "l_released") {
                StacksLabel("Recently released") { onSeeAll(HomeSection.RECENTLY_RELEASED) }
            }
            items(
                recentlyReleasedWorks.take(HOME_SECTION_PREVIEW_COUNT).chunked(2),
                key = { "rr_" + it.first().id },
            ) { pair -> PosterRow(pair, onWorkClick, showYear = true) }
        }
        if (upcomingWorks.isNotEmpty()) {
            item(key = "l_upcoming") {
                StacksLabel("Upcoming releases") { onSeeAll(HomeSection.UPCOMING) }
            }
            items(
                upcomingWorks.take(HOME_SECTION_PREVIEW_COUNT),
                key = { "u_${it.mediaId}" },
            ) { UpcomingRow(it) }
        }
    }
}

@Composable
private fun PosterRow(pair: List<Work>, onWorkClick: (Work) -> Unit, showYear: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        pair.forEach { w ->
            Box(Modifier.weight(1f)) {
                PosterTile(
                    coverUrl = w.coverUrl,
                    title = w.title,
                    subtitle = listOfNotNull(
                        w.authorName?.takeIf { it.isNotBlank() },
                        w.year?.takeIf { showYear }?.toString(),
                    ).joinToString(" · ").ifBlank { null },
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

@Composable
private fun StacksLabel(text: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 16.dp, top = 22.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.6.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "See all",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
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
            hasAudio = work.hasAudio,
            hasEbook = work.hasEbook,
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

// The furthest you've gotten in EITHER format — a book you've read further on
// ebook than audio (or vice versa) shows that further spot, not just the audio %.
private fun furthestFraction(work: Work): Float =
    maxOf(work.listenFraction, work.readFraction).toFloat()

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
