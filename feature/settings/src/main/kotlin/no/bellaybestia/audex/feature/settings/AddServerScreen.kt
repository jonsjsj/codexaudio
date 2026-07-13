package no.bellaybestia.audex.feature.settings

import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import no.bellaybestia.audex.domain.repository.LoginStatus

/**
 * Add-server flow: enter a base URL (and optional name), then Continue opens
 * Authentik in a Custom Tab. The redirect is caught by MainActivity, which calls
 * completeLogin; this screen observes the shared login status and closes on
 * success. Flat design — no filled buttons; the action is an accent text row,
 * matching the SERVERS list "Add server" affordance.
 */
@Composable
fun AddServerScreen(
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AddServerViewModel = hiltViewModel(),
) {
    val context = LocalContext.current

    // Clear any stale status (Success/Error from a prior attempt) once, before we
    // start observing — otherwise a leftover Success could pop us back instantly.
    remember { viewModel.reset() }
    val status by viewModel.status.collectAsState()

    var url by remember { mutableStateOf("https://") }
    var name by remember { mutableStateOf("") }

    // React to status transitions: open the browser, or finish on success.
    LaunchedEffect(status) {
        when (val s = status) {
            is LoginStatus.AwaitingBrowser ->
                CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(s.authorizationUrl))
            is LoginStatus.Success -> {
                viewModel.reset()
                onDone()
            }
            else -> Unit
        }
    }

    val busy = status is LoginStatus.Connecting || status is LoginStatus.Completing
    val canSubmit = !busy && url.isNotBlank() && url != "https://"

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        Text(
            text = "Add server",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(top = 20.dp, bottom = 4.dp),
        )
        Text(
            text = "Sign in with your Audiobookshelf server's OpenID (Authentik). You'll only be prompted once across servers that share it.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 20.dp),
        )

        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("Server URL") },
            singleLine = true,
            enabled = !busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Name (optional)") },
            singleLine = true,
            enabled = !busy,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
        )

        Text(
            text = if (busy) "Working…" else "Continue",
            style = MaterialTheme.typography.bodyLarge,
            color = if (canSubmit) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(top = 20.dp)
                .wrapContentWidth(Alignment.Start)
                .then(
                    if (canSubmit) Modifier.clickable { viewModel.begin(url, name) } else Modifier
                )
                .padding(vertical = 8.dp),
        )

        (status as? LoginStatus.Error)?.let { err ->
            Text(
                text = err.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}
