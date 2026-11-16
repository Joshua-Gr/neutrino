package io.github.joshuagr.neutrino

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.core.net.toUri
import io.github.joshuagr.neutrino.auth.SignInWaitService
import io.github.joshuagr.neutrino.ui.MainScreen
import io.github.joshuagr.neutrino.ui.theme.NeutrinoTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NeutrinoTheme {
                // Failed uploads and conflict copies are reported as notifications; ask once.
                val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
                LaunchedEffect(Unit) { askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) }
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MainScreen(
                        openSignIn = ::openSignIn,
                        waitForSignIn = { waiting ->
                            if (waiting) SignInWaitService.start(applicationContext) else SignInWaitService.stop(applicationContext)
                        },
                        returnToApp = { returnToApp(applicationContext) },
                    )
                }
            }
        }
    }

    /** Opens Proton's sign-in page in a Custom Tab, which stays in this app's task. */
    private fun openSignIn(url: String) {
        CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(this, url.toUri())
    }

    companion object {
        /** Brings the app back over the sign-in tab once the session has arrived. */
        fun returnToApp(context: Context) {
            val intent = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            runCatching { context.startActivity(intent) }
                .onFailure { android.util.Log.w("Neutrino", "Return to app refused: ${it::class.simpleName}") }
        }
    }
}
