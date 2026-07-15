package no.bellaybestia.audex.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import no.bellaybestia.audex.designsystem.FlatTabRow
import no.bellaybestia.audex.domain.settings.ReportKind

/**
 * Report a problem — same idea as Codex's reporter: pick bug/idea/feedback,
 * write it up, send. Files a GitHub issue on the Audex repo through the
 * self-hosted service (token stays server-side). Closing the loop happens on
 * the update page: fixed reports reference the release that fixed them.
 */
@Composable
fun ReportScreen(
    appVersion: String,
    modifier: Modifier = Modifier,
    viewModel: ReportViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        FlatTabRow(
            tabs = listOf("Bug", "Idea", "Feedback"),
            selectedIndex = state.kind.ordinal,
            onSelect = { viewModel.setKind(ReportKind.entries[it]) },
        )

        FieldLabel("Title")
        FlatField(
            value = state.title,
            onValueChange = viewModel::setTitle,
            placeholder = "One line — what happened / what do you want?",
            singleLine = true,
        )
        HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)

        FieldLabel("Details")
        FlatField(
            value = state.body,
            onValueChange = viewModel::setBody,
            placeholder = "What did you do, what did you expect, what happened instead? " +
                "For ideas: what should it do?",
            singleLine = false,
        )
        HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)

        val sendable = state.title.isNotBlank() && !state.sending
        Text(
            text = when {
                state.sending -> "Sending…"
                else -> "Send report"
            },
            style = MaterialTheme.typography.bodyLarge,
            color = if (sendable) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = sendable) { viewModel.send(appVersion) }
                .padding(horizontal = 16.dp, vertical = 16.dp),
        )

        state.result?.let { result ->
            Text(
                text = result,
                style = MaterialTheme.typography.bodyMedium,
                color = if (state.resultIsError) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        Text(
            text = "Sent with app version $appVersion. Fixed reports show up on the " +
                "update page under the release that fixed them.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.2.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 6.dp),
    )
}

@Composable
private fun FlatField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    singleLine: Boolean,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .defaultMinSize(minHeight = if (singleLine) 20.dp else 120.dp),
    ) {
        if (value.isEmpty()) {
            Text(
                text = placeholder,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = MaterialTheme.colorScheme.onSurface,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
