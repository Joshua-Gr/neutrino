package io.github.joshuagr.neutrino.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.joshuagr.neutrino.access.AccessEntry
import io.github.joshuagr.neutrino.access.AccessLog
import io.github.joshuagr.neutrino.auth.ForkLogin
import io.github.joshuagr.neutrino.auth.SessionManager
import io.github.joshuagr.neutrino.drive.AccountKeys
import io.github.joshuagr.neutrino.drive.DriveClientProvider
import io.github.joshuagr.neutrino.upload.PendingStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import me.proton.drive.sdk.entity.FileDownloaderRequest
import me.proton.drive.sdk.entity.FileNode
import me.proton.drive.sdk.entity.FileRevisionUploaderRequest

sealed interface UiState {
    data object SignedOut : UiState
    data class SigningIn(val signInUrl: String) : UiState
    data class SignedIn(val email: String?, val pendingUploads: Int) : UiState
}

class MainViewModel(
    private val sessions: SessionManager,
    private val forkLogin: ForkLogin,
    private val keys: AccountKeys,
    private val drive: DriveClientProvider,
    private val pending: PendingStore,
    private val accessLog: AccessLog,
) : ViewModel() {
    val files: StateFlow<List<AccessEntry>> = accessLog.entries
    private val state = MutableStateFlow<UiState>(UiState.SignedOut)
    val ui: StateFlow<UiState> = state.asStateFlow()
    private val errorState = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = errorState.asStateFlow()
    private var signInJob: Job? = null

    init {
        if (sessions.current() != null) loadAccount()
    }

    fun signIn(open: (String) -> Unit, keepNetwork: (Boolean) -> Unit, returnToApp: () -> Unit) {
        signInJob?.cancel()
        signInJob = viewModelScope.launch {
            report {
                val pending = forkLogin.start()
                state.value = UiState.SigningIn(pending.signInUrl)
                keepNetwork(true)
                try {
                    open(pending.signInUrl)
                    sessions.signedIn(forkLogin.await(pending))
                    android.util.Log.i("Neutrino", "Sign-in complete; returning to the app")
                    returnToApp()
                } finally {
                    keepNetwork(false)
                }
                loadAccount()
            }
            if (state.value is UiState.SigningIn) state.value = UiState.SignedOut
        }
    }

    fun signOut() {
        val waiting = pending.all().size
        if (waiting > 0) {
            errorState.value = "$waiting save(s) are not uploaded yet. Signing out now would lose them; " +
                "connect to the internet and wait for the upload first."
            return
        }
        signInJob?.cancel()
        viewModelScope.launch {
            report {
                drive.wipe()
                sessions.signOut()
                accessLog.clear()
            }
            state.value = UiState.SignedOut
        }
    }

    /**
     * Debug builds only: forces the next request through the token-refresh path. With [viaAccount]
     * the Drive client is rebuilt so the account endpoints hit the 401 first; otherwise the SDK's
     * own requests do.
     */
    fun debugExpireAndReload(viaAccount: Boolean) {
        sessions.debugExpireAccessToken()
        if (viaAccount) viewModelScope.launch { drive.close() }.invokeOnCompletion { loadAccount() } else loadAccount()
    }

    /**
     * Debug builds only: uploads the current content of "neutrino-test.kdbx" in My files as a new
     * revision, as another device would, so the next save from this phone takes the conflict path.
     */
    fun debugSimulateRemoteChange() {
        viewModelScope.launch {
            report {
                val client = drive.get()
                val root = client.getMyFilesFolder()
                val file = client.enumerateNodes(client.enumerateFolderChildrenNodeUids(root.uid).toList()).toList()
                    .filterIsInstance<FileNode>().firstOrNull { it.name.getOrNull() == DEBUG_TEST_FILE }
                    ?: error("No $DEBUG_TEST_FILE in the top level of My files")
                val bytes = java.io.ByteArrayOutputStream().also { out ->
                    client.downloader(FileDownloaderRequest(file.activeRevision.uid)).use {
                        it.downloadToStream(this, java.nio.channels.Channels.newChannel(out)).awaitCompletion()
                    }
                }.toByteArray()
                client.uploader(FileRevisionUploaderRequest(file.activeRevision.uid, java.time.OffsetDateTime.now(), bytes.size.toLong()))
                    .use { it.uploadFromStream(this, java.nio.channels.Channels.newChannel(bytes.inputStream())).awaitCompletion() }
                errorState.value = "Debug: $DEBUG_TEST_FILE changed in Drive (new revision, same content)"
            }
        }
    }

    fun loadAccount() {
        viewModelScope.launch {
            state.value = UiState.SignedIn(keys.primaryEmail, pending.all().size)
            report {
                drive.get()
                state.value = UiState.SignedIn(keys.primaryEmail, pending.all().size)
            }
            if (sessions.current() == null) state.value = UiState.SignedOut
        }
    }

    private companion object {
        const val DEBUG_TEST_FILE = "neutrino-test.kdbx"
    }

    private suspend fun report(block: suspend () -> Unit) {
        errorState.value = null
        runCatching { block() }.onFailure {
            android.util.Log.w("Neutrino", "Action failed: ${it::class.simpleName}: ${it.message?.take(200)}")
            errorState.value = it.message ?: it::class.simpleName
        }
    }
}
