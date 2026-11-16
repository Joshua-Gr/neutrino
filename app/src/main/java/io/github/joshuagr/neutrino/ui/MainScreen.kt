package io.github.joshuagr.neutrino.ui

import android.content.pm.PackageManager
import android.text.format.DateUtils
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.joshuagr.neutrino.R
import io.github.joshuagr.neutrino.access.AccessEntry
import kotlinx.coroutines.delay
import org.koin.androidx.compose.koinViewModel

private const val CLOCK_TICK_MS = 30_000L
private const val ICON_PX = 128
private const val MINUTES_PER_HOUR = 60
private const val MINUTES_PER_DAY = 60 * 24
private val PanelShape = RoundedCornerShape(24.dp)

/** One page: the account and its activity in the top third, the files other apps opened below. */
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
    // Relative times ("5 min ago") are re-evaluated on a slow tick.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(CLOCK_TICK_MS)
            now = System.currentTimeMillis()
        }
    }
    val signedIn = ui as? UiState.SignedIn
    if (signedIn == null) {
        LoginScreen(ui, error, onSignIn = { viewModel.signIn(openSignIn, waitForSignIn, returnToApp) }, onReopen = openSignIn)
        return
    }
    Column(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        AccountPanel(signedIn, files, now, error, viewModel::signOut, Modifier.weight(1f).fillMaxWidth())
        FileList(files, now, Modifier.weight(2f).fillMaxWidth())
    }
}

/** Signed out: the name and one button. While signing in, a way back to the page. */
@Composable
private fun LoginScreen(ui: UiState, error: String?, onSignIn: () -> Unit, onReopen: (String) -> Unit) {
    Column(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Neutrino", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.size(32.dp))
        if (ui is UiState.SigningIn) {
            CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.5.dp)
            Spacer(Modifier.size(16.dp))
            TextButton(onClick = { onReopen(ui.signInUrl) }) { Text("Open the sign-in page again") }
        } else {
            Button(onClick = onSignIn, modifier = Modifier.fillMaxWidth()) { Text("Sign in with Proton") }
        }
        error?.let {
            Spacer(Modifier.size(16.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun AccountPanel(
    ui: UiState.SignedIn,
    files: List<AccessEntry>,
    now: Long,
    error: String?,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.clip(PanelShape).background(MaterialTheme.colorScheme.surfaceContainer)) {
        TextButton(onClick = onSignOut, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)) { Text("Sign out") }
        Column(
            Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f))
            Avatar(ui.email)
            Spacer(Modifier.size(10.dp))
            Text(
                ui.email ?: "Signed in",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                Spacer(Modifier.width(6.dp))
                Text("Connected to Drive", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, maxLines = 2)
            }
            Spacer(Modifier.weight(1f))
            val lastSync = files.mapNotNull { it.lastUploadedAt }.maxOrNull()
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile(files.map { it.documentId }.distinct().size.toString(), "files")
                StatTile(ui.pendingUploads.toString(), "waiting")
                StatTile(lastSync?.let { shortAgo(it, now) } ?: "—", "synced")
            }
        }
    }
}

@Composable
private fun RowScope.StatTile(value: String, label: String) {
    Column(
        Modifier
            .weight(1f)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, style = MaterialTheme.typography.titleLarge, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Avatar(email: String?) {
    Box(
        Modifier.size(56.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
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
private fun FileList(files: List<AccessEntry>, now: Long, modifier: Modifier = Modifier) {
    Box(modifier.clip(PanelShape).border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline), PanelShape)) {
        if (files.isEmpty()) {
            Text(
                "Files other apps open through Neutrino appear here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).padding(32.dp),
            )
            return@Box
        }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            items(files, key = { "${it.documentId}|${it.packageName}" }) { entry ->
                FileRow(entry, now)
                if (entry != files.last()) HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

@Composable
private fun FileRow(entry: AccessEntry, now: Long) {
    Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        AppIcon(entry.packageName)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(entry.fileName, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Stamp(R.drawable.ic_opened, "Opened", entry.lastOpenedAt, now)
                Stamp(R.drawable.ic_saved, "Saved on this device", entry.lastSavedAt, now)
                Stamp(R.drawable.ic_drive, "Updated in Drive", entry.lastRemoteUpdateAt, now)
            }
        }
    }
}

/** One icon and a short time, e.g. a pencil and "7m"; the icon's description says what the time is. */
@Composable
private fun Stamp(icon: Int, description: String, time: Long?, now: Long) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            painterResource(icon),
            contentDescription = description,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            time?.let { shortAgo(it, now) } ?: "—",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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

/** Compact form for a tile: "now", "5m", "3h", "2d". */
private fun shortAgo(time: Long, now: Long): String {
    val minutes = (now - time).coerceAtLeast(0) / DateUtils.MINUTE_IN_MILLIS
    return when {
        minutes < 1 -> "now"
        minutes < MINUTES_PER_HOUR -> "${minutes}m"
        minutes < MINUTES_PER_DAY -> "${minutes / MINUTES_PER_HOUR}h"
        else -> "${minutes / MINUTES_PER_DAY}d"
    }
}

private fun appLabel(pm: PackageManager, packageName: String): String =
    runCatching { pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString() }.getOrDefault(packageName)
