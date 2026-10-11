package com.youdownloader.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.youdownloader.app.data.local.PreferencesManager
import com.youdownloader.app.data.local.ThemeMode
import com.youdownloader.app.ui.navigation.AppNavigation
import com.youdownloader.app.ui.theme.YouDownloaderTheme

class MainActivity : ComponentActivity() {

    private lateinit var preferencesManager: PreferencesManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferencesManager = PreferencesManager(applicationContext)

        // Handle shared link if launched via Android Share Sheet
        handleIncomingIntent(intent)

        setContent {
            val themeMode by preferencesManager.themeModeFlow.collectAsState(initial = ThemeMode.SYSTEM)

            YouDownloaderTheme(themeMode = themeMode) {
                Surface(
                    modifier = Modifier.fillMaxSize()
                ) {
                    AppNavigation()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            // Can be pre-filled into link download sheet
        }
    }
}
