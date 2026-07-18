package no.bellaybestia.audex.feature.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * The last CONTENT screen the user was on, so a report filed from the reporter can say
 * WHICH window it's about (parity with Codex Companion's per-screen reporting). AppNav
 * records it on navigation — skipping the report screen itself, so it always points at
 * the screen you came from. In-memory / process-scoped: a report only needs the current
 * session's context.
 */
object CurrentScreen {
    @Volatile
    private var label: String = ""
    fun record(screen: String) { if (screen.isNotBlank()) label = screen }
    fun get(): String = label
}

/**
 * A small flat "report this screen" button (matches Codex's ReportFab). Shown by AppNav
 * on content/detail screens where the Settings → Report path is a few taps away; tapping
 * it opens the reporter with "About: <this screen>" already filled in.
 */
@Composable
fun ReportFab(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val mark = MaterialTheme.colorScheme.onSurfaceVariant
    androidx.compose.foundation.layout.Box(
        modifier = modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = "Report a problem with this screen" },
        contentAlignment = Alignment.Center,
    ) {
        // A simple "!" glyph drawn inline — keeps the flat look without pulling in
        // the material-icons-extended artifact just for one icon.
        Canvas(Modifier.size(20.dp)) {
            val cx = size.width / 2f
            val stroke = size.width * 0.16f
            drawLine(
                color = mark,
                start = Offset(cx, size.height * 0.18f),
                end = Offset(cx, size.height * 0.62f),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
            drawCircle(
                color = mark,
                radius = stroke * 0.62f,
                center = Offset(cx, size.height * 0.84f),
            )
        }
    }
}
