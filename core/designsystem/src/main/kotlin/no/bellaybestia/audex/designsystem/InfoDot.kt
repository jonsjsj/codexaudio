package no.bellaybestia.audex.designsystem

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * A small tappable ⓘ that reveals an explanation in a dialog on demand — so a screen
 * can stay uncluttered instead of carrying a paragraph of helper text inline. Pass the
 * explanation that used to sit as subtext; an optional [title] heads the dialog.
 */
@Composable
fun InfoDot(
    text: String,
    modifier: Modifier = Modifier,
    title: String? = null,
) {
    var show by remember { mutableStateOf(false) }
    Text(
        text = "ⓘ",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .clickable { show = true }
            .padding(4.dp),
    )
    if (show) {
        AlertDialog(
            onDismissRequest = { show = false },
            confirmButton = { TextButton(onClick = { show = false }) { Text("Got it") } },
            title = title?.let { { Text(it) } },
            text = { Text(text, style = MaterialTheme.typography.bodyMedium) },
        )
    }
}
