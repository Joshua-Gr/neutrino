package io.github.joshuagr.neutrino.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.joshuagr.neutrino.access.AccessEntry
import io.github.joshuagr.neutrino.access.AccessLog
import io.github.joshuagr.neutrino.auth.ForkLogin
import io.github.joshuagr.neutrino.auth.SessionManager
import io.github.joshuagr.neutrino.drive.AccountKeys
import io.github.joshuagr.neutrino.drive.DriveClientProvider
import io.github.joshuagr.neutrino.provider.OfflineCache
import io.github.joshuagr.neutrino.upload.PendingStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface UiState {
    data object SignedOut : UiState
    data class SigningIn(val signInUrl: String) : UiState
    data class SignedIn(val email: String?, val pendingUploads: Int) : UiState
}

private const val PENDING_REFRESH_MS = 3_000L

class MainViewModel(
    private val sessions: SessionManager,
    private val forkLogin: ForkLogin,
    private val keys: AccountKeys,
    private val drive: DriveClientProvider,
    private val pending: PendingStore,
    private val accessLog: AccessLog,
    private val offline: OfflineCache,
) : ViewModel() {
    val files: StateFlow<List<AccessEntry>> = accessLog.entries
    private val state = MutableStateFlow<UiState>(UiState.SignedOut)
    val ui: StateFlow<UiState> = state.asStateFlow()
    private val errorState = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = errorState.asStateFlow()
    private var signInJob: Job? = null

    init {
        if (sessions.current() != null) loadAccount()
        // The waiting-uploads count changes from the provider and the upload worker; poll it cheaply.
        viewModelScope.launch {
            while (true) {
                (state.value as? UiState.SignedIn)?.let { state.value = it.copy(pendingUploads = pending.all().size) }
                kotlinx.coroutines.delay(PENDING_REFRESH_MS)
            }
        }
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
                offline.clear()
                pending.clearAll()
            }
            state.value = UiState.SignedOut
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

    private suspend fun report(block: suspend () -> Unit) {
        errorState.value = null
        runCatching { block() }.onFailure {
            android.util.Log.w("Neutrino", "Action failed: ${it::class.simpleName}: ${it.message?.take(200)}")
            errorState.value = it.message ?: it::class.simpleName
        }
    }
}
