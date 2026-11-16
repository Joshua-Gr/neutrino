package io.github.joshuagr.neutrino

import android.app.Application
import android.provider.DocumentsContract
import io.github.joshuagr.neutrino.api.ProtonApi
import io.github.joshuagr.neutrino.auth.ForkLogin
import io.github.joshuagr.neutrino.auth.SessionManager
import io.github.joshuagr.neutrino.auth.SessionStore
import io.github.joshuagr.neutrino.drive.AccountKeys
import io.github.joshuagr.neutrino.drive.DriveClientProvider
import io.github.joshuagr.neutrino.drive.SdkTransport
import io.github.joshuagr.neutrino.provider.DriveDocuments
import io.github.joshuagr.neutrino.provider.NeutrinoDocumentsProvider
import io.github.joshuagr.neutrino.ui.MainViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import java.util.concurrent.TimeUnit

class NeutrinoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val koin = startKoin {
            androidContext(this@NeutrinoApp)
            modules(appModule)
        }.koin
        val sessions = koin.get<SessionManager>()
        val docs = koin.get<DriveDocuments>()
        // The file picker shows the Drive root only while signed in; tell it when that changes.
        appScope.launch {
            sessions.session.map { it != null }.distinctUntilChanged().collect { signedIn ->
                if (!signedIn) docs.forget()
                contentResolver.notifyChange(DocumentsContract.buildRootsUri(NeutrinoDocumentsProvider.authority), null)
            }
        }
    }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}

private const val CONNECT_TIMEOUT_S = 20L
private const val IO_TIMEOUT_S = 120L

val appModule = module {
    single {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(IO_TIMEOUT_S, TimeUnit.SECONDS)
            .writeTimeout(IO_TIMEOUT_S, TimeUnit.SECONDS)
            .build()
    }
    single { ProtonApi(get(), BuildConfig.APP_VERSION_HEADER) }
    single { SessionStore(get()) }
    single { SessionManager(get(), get()) }
    single { ForkLogin(get()) }
    single { AccountKeys(get()) }
    single { SdkTransport(get(), get()) }
    single { DriveClientProvider(get(), get(), get(), get()) }
    single { DriveDocuments(get()) }
    viewModel { MainViewModel(get(), get(), get(), get()) }
}
