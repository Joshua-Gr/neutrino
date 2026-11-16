package io.github.joshuagr.neutrino.ui

import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.joshuagr.neutrino.BuildConfig
import io.github.joshuagr.neutrino.access.AccessEntry
import org.koin.androidx.compose.koinViewModel

/** One page: the account in the top third, the files other apps opened through Neutrino below. */
@Composable
fun MainScreen(
    openSignIn: (String) -> Unit,
    waitForSignIn: (Boolean) -> Unit,
    returnToApp: () -> Unit,
    viewModel: MainViewModel = koinViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val files by viewModel.files.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        AccountPanel(
            ui = ui,
            error = error,
            onSignIn = { viewModel.signIn(openSignIn, waitForSignIn, returnToApp) },
            onReopen = openSignIn,
            onSignOut = viewModel::signOut,
            viewModel = viewModel,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(16.dp),
        )
        FileList(files, Modifier.weight(2f).fillMaxWidth())
    }
}

@Composable
private fun AccountPanel(
    ui: UiState,
    error: String?,
    onSignIn: () -> Unit,
    onReopen: (String) -> Unit,
    onSignOut: () -> Unit,
    viewModel: MainViewModel,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when (ui) {
            UiState.SignedOut -> {
                Text("Neutrino", style = MaterialTheme.typography.headlineSmall)
                Text("Open and save your Proton Drive files from any app.", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.weight(1f))
                Button(onClick = onSignIn, modifier = Modifier.fillMaxWidth()) { Text("Sign in with Proton") }
            }
            is UiState.SigningIn -> {
                Text("Signing in", style = MaterialTheme.typography.headlineSmall)
                Text("Finish signing in on Proton's page.", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.weight(1f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    TextButton(onClick = { onReopen(ui.signInUrl) }) { Text("Open the sign-in page again") }
                }
            }
            is UiState.SignedIn -> SignedIn(ui, onSignOut, viewModel)
        }
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, maxLines = 3)
        }
        Text(
            "Third-party app, not affiliated with or supported by Proton.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ColumnScope.SignedIn(ui: UiState.SignedIn, onSignOut: () -> Unit, viewModel: MainViewModel) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Avatar(ui.email)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(ui.email ?: "Signed in", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (ui.pendingUploads == 0) "Connected to Drive" else "${ui.pendingUploads} save(s) waiting to upload",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Menu(onSignOut, viewModel)
    }
    Spacer(Modifier.weight(1f))
}

@Composable
private fun Avatar(email: String?) {
    Box(
        Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            email?.firstOrNull()?.uppercase() ?: "·",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onPrimary,
        )
    }
}

@Composable
private fun Menu(onSignOut: () -> Unit, viewModel: MainViewModel) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text("⋮", style = MaterialTheme.typography.titleLarge) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Sign out") }, onClick = { open = false; onSignOut() })
            if (BuildConfig.DEBUG) {
                DropdownMenuItem(
                    text = { Text("Debug: expire token (SDK path)") },
                    onClick = { open = false; viewModel.debugExpireAndReload(viaAccount = false) },
                )
                DropdownMenuItem(
                    text = { Text("Debug: expire token (account path)") },
                    onClick = { open = false; viewModel.debugExpireAndReload(viaAccount = true) },
                )
                DropdownMenuItem(
                    text = { Text("Debug: change test file in Drive") },
                    onClick = { open = false; viewModel.debugSimulateRemoteChange() },
                )
            }
        }
    }
}

@Composable
private fun FileList(files: List<AccessEntry>, modifier: Modifier = Modifier) {
    if (files.isEmpty()) {
        Box(modifier.padding(32.dp), contentAlignment = Alignment.Center) {
            Text(
                "Files other apps open through Neutrino appear here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    LazyColumn(modifier.padding(horizontal = 16.dp)) {
        items(files, key = { "${it.documentId}|${it.packageName}" }) { entry ->
            FileRow(entry)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun FileRow(entry: AccessEntry) {
    Row(Modifier.fillMaxWidth().padding(vertical = 14.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        AppIcon(entry.packageName)
        Spacer(Modifier.width(16.dp))
        Text(entry.fileName, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun AppIcon(packageName: String) {
    val context = LocalContext.current
    val icon: ImageBitmap? = remember(packageName) {
        runCatching { context.packageManager.getApplicationIcon(packageName).toBitmap(ICON_PX, ICON_PX).asImageBitmap() }
            .getOrNull()
    }
    if (icon != null) {
        Image(icon, contentDescription = appLabel(context.packageManager, packageName), modifier = Modifier.size(40.dp))
    } else {
        Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh))
    }
}

private fun appLabel(pm: PackageManager, packageName: String): String =
    runCatching { pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString() }.getOrDefault(packageName)

private const val ICON_PX = 128
