package no.bellaybestia.audex

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import no.bellaybestia.audex.auth.AbsOidcFlow
import no.bellaybestia.audex.designsystem.AudexTheme
import no.bellaybestia.audex.domain.repository.AuthRepository
import javax.inject.Inject

// FragmentActivity (not plain ComponentActivity) so the Readium
// EpubNavigatorFragment has a FragmentManager to live in.
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject
    lateinit var authRepository: AuthRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleOAuthRedirect(intent)
        setContent {
            AudexTheme {
                AppNav()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOAuthRedirect(intent)
    }

    /** Catch the `audiobookshelf://oauth?...` Custom Tab redirect and finish the login. */
    private fun handleOAuthRedirect(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != AbsOidcFlow.REDIRECT_SCHEME) return
        val url = data.toString()
        lifecycleScope.launch { authRepository.completeLogin(url) }
    }
}
