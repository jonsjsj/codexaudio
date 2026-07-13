package no.bellaybestia.codexaudio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dagger.hilt.android.AndroidEntryPoint
import no.bellaybestia.codexaudio.designsystem.CodexAudioTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CodexAudioTheme {
                AppNav()
            }
        }
    }
}
