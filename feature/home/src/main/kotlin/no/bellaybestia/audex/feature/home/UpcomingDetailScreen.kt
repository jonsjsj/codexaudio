package no.bellaybestia.audex.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import no.bellaybestia.audex.designsystem.CoverImage
import no.bellaybestia.audex.domain.model.MediaDetail

/**
 * Read-only detail for a book you don't own yet — tapped from Home's Upcoming
 * releases. Shows Codex's own catalog entry ([MediaDetail.source] "codex") when
 * it has one, or a public Open Library lookup ("public") when it doesn't; the
 * nav-arg title/cover/release date paint instantly while that loads. There's
 * no play/read/download here (nothing to open yet) — just enough context to
 * recognize the book ahead of its release.
 */
@Composable
fun UpcomingDetailScreen(
    modifier: Modifier = Modifier,
    viewModel: UpcomingDetailViewModel = hiltViewModel(),
) {
    val detail by viewModel.detail.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    val title = detail?.title?.takeIf { it.isNotBlank() } ?: viewModel.initialTitle
    val coverUrl = detail?.coverUrl ?: viewModel.initialCoverUrl
    val releaseDate = detail?.releaseDate ?: viewModel.initialReleaseDate

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Box(Modifier.fillMaxWidth().aspectRatio(0.96f)) {
            CoverImage(
                url = coverUrl,
                contentDescription = title,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0.0f to Color.Transparent,
                        0.5f to Color(0x55000000),
                        1.0f to Color(0xF00A0B0F),
                    ),
                ),
            )
            Column(
                Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 18.dp),
            ) {
                detail?.seriesName?.let { series ->
                    val pos = detail?.seriesPosition?.let { p ->
                        " · #" + if (p % 1.0 == 0.0) p.toInt().toString() else p.toString()
                    }.orEmpty()
                    Text(
                        text = (series + pos).uppercase(),
                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp),
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFEAEEF5),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val byline = listOfNotNull(detail?.author, detail?.narrator?.let { "Read by $it" })
                    .joinToString(" · ")
                if (byline.isNotBlank()) {
                    Text(
                        text = byline,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFC8CDD8),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            releaseDate?.let {
                Text(
                    text = "RELEASES $it".uppercase(),
                    style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.4.sp),
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(14.dp))
            }
            when {
                isLoading && detail == null -> CircularProgressIndicator(modifier = Modifier.padding(top = 8.dp))
                !detail?.description.isNullOrBlank() -> Text(
                    text = detail!!.description!!,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                !isLoading -> Text(
                    text = "No description available yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (detail?.source == "public") {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "Via Open Library — not yet tracked in Codex.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
