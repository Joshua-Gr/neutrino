package io.github.joshuagr.neutrino

import android.app.Application
import io.github.joshuagr.neutrino.api.ProtonApi
import io.github.joshuagr.neutrino.auth.ForkLogin
import io.github.joshuagr.neutrino.auth.SessionManager
import io.github.joshuagr.neutrino.auth.SessionStore
import io.github.joshuagr.neutrino.drive.AccountKeys
import io.github.joshuagr.neutrino.drive.DriveClientProvider
import io.github.joshuagr.neutrino.drive.SdkTransport
import io.github.joshuagr.neutrino.ui.MainViewModel
import okhttp3.OkHttpClient
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import java.util.concurrent.TimeUnit

class NeutrinoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@NeutrinoApp)
            modules(appModule)
        }
    }
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
    viewModel { MainViewModel(get(), get(), get(), get()) }
}
