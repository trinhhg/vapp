package dev.trinhhg.vapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.trinhhg.vapp.settings.AppSettings
import dev.trinhhg.vapp.settings.AppSettingsRepository
import dev.trinhhg.vapp.ui.navigation.VappNavHost
import org.koin.compose.koinInject

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val settingsRepo: AppSettingsRepository = koinInject()
            val settings by settingsRepo.settings.collectAsState(initial = AppSettings())
            val darkTheme =
                when (settings.themePresetId.lowercase()) {
                    "dark", "oled" -> true
                    "light", "sepia", "green" -> false
                    else -> isSystemInDarkTheme()
                }
            MaterialTheme(
                colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme(),
            ) {
                VappNavHost()
            }
        }
    }
}
