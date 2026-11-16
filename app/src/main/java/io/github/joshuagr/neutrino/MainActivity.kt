package io.github.joshuagr.neutrino

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.joshuagr.neutrino.ui.MainViewModel
import io.github.joshuagr.neutrino.ui.UiState
import org.koin.androidx.compose.koinViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Scaffold { insets ->
                    MainScreen(
                        open = { url -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) },
                        modifier = Modifier.padding(insets).padding(24.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun MainScreen(open: (String) -> Unit, modifier: Modifier = Modifier, viewModel: MainViewModel = koinViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    // Failed uploads and conflict copies are reported as notifications; ask once.
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) { askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) }
    val error by viewModel.error.collectAsStateWithLifecycle()
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Neutrino", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Third-party application, not affiliated with or supported by Proton.",
            style = MaterialTheme.typography.bodySmall,
        )
        when (val state = ui) {
            UiState.SignedOut -> Button(onClick = { viewModel.signIn(open) }) { Text("Sign in with Proton") }
            is UiState.SigningIn -> SigningIn { open(state.signInUrl) }
            is UiState.SignedIn -> {
                SignedIn(state, onRefresh = viewModel::loadAccount, onSignOut = viewModel::signOut)
                if (BuildConfig.DEBUG) DebugActions(viewModel)
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun DebugActions(viewModel: MainViewModel) {
    OutlinedButton(onClick = { viewModel.debugExpireAndReload(viaAccount = false) }) {
        Text("Debug: expire token (SDK path)")
    }
    OutlinedButton(onClick = { viewModel.debugExpireAndReload(viaAccount = true) }) {
        Text("Debug: expire token (account path)")
    }
    OutlinedButton(onClick = viewModel::debugSimulateRemoteChange) {
        Text("Debug: change test file in Drive")
    }
}

@Composable
private fun SigningIn(reopen: () -> Unit) {
    Text("Finish signing in on Proton's page in your browser.")
    CircularProgressIndicator()
    OutlinedButton(onClick = reopen) { Text("Open the sign-in page again") }
}

@Composable
private fun SignedIn(state: UiState.SignedIn, onRefresh: () -> Unit, onSignOut: () -> Unit) {
    Text("Signed in" + (state.email?.let { " as $it" } ?: ""))
    OutlinedButton(onClick = onRefresh) { Text("Refresh") }
    OutlinedButton(onClick = onSignOut) { Text("Sign out") }
    val entries = state.rootEntries
    if (entries == null) {
        CircularProgressIndicator()
    } else {
        Text("My files (${entries.size})", style = MaterialTheme.typography.titleMedium)
        LazyColumn { items(entries) { Text(it, modifier = Modifier.padding(vertical = 4.dp)) } }
    }
}
