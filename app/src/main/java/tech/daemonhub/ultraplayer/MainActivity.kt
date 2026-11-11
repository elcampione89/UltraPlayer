package tech.daemonhub.ultraplayer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import tech.daemonhub.ultraplayer.data.Catalog
import tech.daemonhub.ultraplayer.data.Prefs
import tech.daemonhub.ultraplayer.panel.PanelStartupDialogs
import tech.daemonhub.ultraplayer.ui.Bg
import tech.daemonhub.ultraplayer.ui.HomeScreen
import tech.daemonhub.ultraplayer.ui.LoginScreen
import tech.daemonhub.ultraplayer.ui.UltraTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            UltraTheme {
                var loggedIn by remember { mutableStateOf(Prefs.login != null) }
                Box(Modifier.fillMaxSize().background(Bg)) {
                    if (loggedIn) {
                        HomeScreen(onLogout = {
                            Prefs.login = null
                            Catalog.reset()
                            loggedIn = false
                        })
                    } else {
                        LoginScreen(onLoggedIn = { loggedIn = true })
                    }
                    PanelStartupDialogs()
                }
            }
        }
    }
}
