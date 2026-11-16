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
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
    Column(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        AccountPanel(
            ui = ui,
            files = files,
            now = now,
            error = error,
            onSignIn = { viewModel.signIn(openSignIn, waitForSignIn, returnToApp) },
            onReopen = openSignIn,
            onSignOut = viewModel::signOut,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
        FileList(files, now, Modifier.weight(2f).fillMaxWidth())
    }
}

@Composable
private fun AccountPanel(
    ui: UiState,
    files: List<AccessEntry>,
    now: Long,
    error: String?,
    onSignIn: () -> Unit,
    onReopen: (String) -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.clip(PanelShape).background(MaterialTheme.colorScheme.surfaceContainer).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (ui) {
            UiState.SignedOut -> {
                Text("Neutrino", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.weight(1f))
                Button(onClick = onSignIn, modifier = Modifier.fillMaxWidth()) { Text("Sign in with Proton") }
            }
            is UiState.SigningIn -> {
                Text("Signing in", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.weight(1f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    TextButton(onClick = { onReopen(ui.signInUrl) }) { Text("Open the sign-in page again") }
                }
            }
            is UiState.SignedIn -> SignedIn(ui, files, now, onSignOut)
        }
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, maxLines = 3)
        }
    }
}

@Composable
private fun ColumnScope.SignedIn(ui: UiState.SignedIn, files: List<AccessEntry>, now: Long, onSignOut: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Avatar(ui.email)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                ui.email ?: "Signed in",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "Connected to Drive",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Menu(onSignOut)
    }
    Spacer(Modifier.weight(1f))
    val lastSync = files.mapNotNull { it.lastUploadedAt }.maxOrNull()
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatTile(files.map { it.documentId }.distinct().size.toString(), "files")
        StatTile(ui.pendingUploads.toString(), "waiting")
        StatTile(lastSync?.let { shortAgo(it, now) } ?: "—", "synced")
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
        Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
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
private fun Menu(onSignOut: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text("⋮", style = MaterialTheme.typography.titleLarge) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Sign out") },
                onClick = {
                    open = false
                    onSignOut()
                },
            )
        }
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
                if (entry != files.last()) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
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
            Detail("Accessed ${ago(entry.lastOpenedAt, now)}")
            Detail("Updated here ${entry.lastSavedAt?.let { ago(it, now) } ?: "never"}")
            Detail("Updated in Drive ${entry.lastRemoteUpdateAt?.let { ago(it, now) } ?: "unknown"}")
        }
    }
}

@Composable
private fun Detail(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
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

private fun ago(time: Long, now: Long): String =
    DateUtils.getRelativeTimeSpanString(time, maxOf(now, time), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE)
        .toString()

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
