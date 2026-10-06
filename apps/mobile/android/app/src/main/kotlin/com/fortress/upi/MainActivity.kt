package com.fortress.upi

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.fragment.app.FragmentActivity
import com.fortress.upi.core.designsystem.FortressTheme
import com.fortress.upi.core.designsystem.paletteFor
import com.fortress.upi.core.security.hygiene.SecureWindow
import com.fortress.upi.navigation.FortressNavHost
import com.fortress.upi.platform.AuthGate
import com.fortress.upi.platform.HighRefreshRate
import com.fortress.upi.upi.UpiPaymentIntent
import com.fortress.upi.upi.UpiUri

/** FragmentActivity (not ComponentActivity) because BiometricPrompt requires it. */
class MainActivity : FragmentActivity() {
    private val incomingUpi = mutableStateOf<UpiPaymentIntent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SecureWindow.apply(this)
        HighRefreshRate.request(this)
        enableEdgeToEdge()
        incomingUpi.value = parseUpi(intent)

        val container = (application as FortressApplication).container
        val authGate = AuthGate(this)
        setContent {
            val mode by container.prefs.theme.collectAsStateWithLifecycle()
            val dark = paletteFor(mode).isDark
            // System bar icons follow the in-app theme, not just the phone's setting.
            LaunchedEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            FortressTheme(mode) {
                FortressNavHost(container = container, authGate = authGate, incomingUpi = incomingUpi)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        incomingUpi.value = parseUpi(intent)
    }

    private fun parseUpi(intent: Intent?): UpiPaymentIntent? =
        intent?.takeIf { it.action == Intent.ACTION_VIEW }?.dataString?.let(UpiUri::parse)
}
