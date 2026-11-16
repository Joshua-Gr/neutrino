package io.github.joshuagr.neutrino

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
                // Failed uploads, conflict copies and the sign-in return are notifications; ask once.
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

    override fun onResume() {
        super.onResume()
        resumed = true
    }

    override fun onPause() {
        resumed = false
        super.onPause()
    }

    /** Opens Proton's sign-in page in a Custom Tab, which stays in this app's task. */
    private fun openSignIn(url: String) {
        CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(this, url.toUri())
    }

    companion object {
        @Volatile private var resumed = false
        private const val CHANNEL = "sign-in"
        private const val NOTIFICATION_ID = 1
        private const val RETURN_CHECK_MS = 2_000L

        /**
         * Brings the app back over the sign-in tab once the session has arrived. Android may refuse
         * an activity start from the background; if the app is not in front shortly after, a
         * notification offers the way back instead.
         */
        fun returnToApp(context: Context) {
            val intent = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            runCatching { context.startActivity(intent) }
                .onFailure { android.util.Log.w("Neutrino", "Return to app refused: ${it::class.simpleName}") }
            Handler(Looper.getMainLooper()).postDelayed({
                android.util.Log.i("Neutrino", "Return to app: in front=$resumed")
                if (!resumed) notifySignedIn(context, intent)
            }, RETURN_CHECK_MS)
        }

        private fun notifySignedIn(context: Context, intent: Intent) {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (!manager.areNotificationsEnabled()) return
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Sign-in", NotificationManager.IMPORTANCE_HIGH))
            val open = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            manager.notify(
                NOTIFICATION_ID,
                Notification.Builder(context, CHANNEL)
                    .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                    .setContentTitle("Signed in")
                    .setContentText("Tap to return to Neutrino.")
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .setTimeoutAfter(RETURN_CHECK_MS * NOTIFICATION_LIFETIME_FACTOR)
                    .build(),
            )
        }

        private const val NOTIFICATION_LIFETIME_FACTOR = 30
    }
}
