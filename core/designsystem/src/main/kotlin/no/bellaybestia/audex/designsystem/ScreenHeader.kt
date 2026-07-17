package no.bellaybestia.audex.designsystem

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The app-wide screen title: a big bold heading with an optional uppercase
 * eyebrow beneath it — the same identity as Home's "Home" header, so every
 * top-level screen and detail page reads as one design instead of Home being
 * the only styled surface. Pass [compact] on detail pages where the title is a
 * name (author/series) that can run long.
 */
@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    compact: Boolean = false,
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(
                start = 16.dp,
                end = 16.dp,
                top = 20.dp,
                bottom = if (subtitle != null) 8.dp else 12.dp,
            ),
    ) {
        Text(
            text = title,
            style = if (compact) MaterialTheme.typography.headlineMedium
            else MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle.uppercase(),
                style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.4.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}
