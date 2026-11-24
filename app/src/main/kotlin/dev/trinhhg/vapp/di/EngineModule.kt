package dev.trinhhg.vapp.di

import dev.trinhhg.vapp.engine.api.BrowserEngineFactory
import dev.trinhhg.vapp.engine.api.ChallengeSolver
import dev.trinhhg.vapp.engine.api.ExtensionExecutor
import dev.trinhhg.vapp.engine.fetch.ExtensionExecutorImpl
import dev.trinhhg.vapp.engine.js.api.HttpExecutor
import dev.trinhhg.vapp.engine.log.EngineLogger
import dev.trinhhg.vapp.engine.net.CookiePersistence
import dev.trinhhg.vapp.engine.net.OkioJsonCookieStore
import dev.trinhhg.vapp.engine.vbook.ExtensionManager
import dev.trinhhg.vapp.engine.vbook.PluginZipExtractor
import dev.trinhhg.vapp.engine.vbook.VBookRepositoryClient
import dev.trinhhg.vapp.webview.AndroidWebViewBrowserFactory
import dev.trinhhg.vapp.webview.WebViewChallengeSolver
import okio.Path
import okio.Path.Companion.toOkioPath
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val engineModule =
    module {
        single<Path> {
            androidContext().filesDir.resolve("vapp_engine").toOkioPath()
        }

        single {
            val rootDir: Path = get()
            EngineLogger(logFile = rootDir / "logs" / "engine.log")
        }

        single<CookiePersistence> {
            val rootDir: Path = get()
            OkioJsonCookieStore(cookieFile = rootDir / "cookies" / "cookies.json")
        }

        single {
            WebViewChallengeSolver(
                appContext = androidContext(),
                cookieStore = get(),
                log = get(),
            )
        }

        single<ChallengeSolver> { get<WebViewChallengeSolver>() }

        single {
            HttpExecutor(
                cookieStore = get(),
                log = get(),
                challengeSolver = get(),
            )
        }

        single<BrowserEngineFactory> {
            AndroidWebViewBrowserFactory(
                appContext = androidContext(),
                cookieStore = get(),
                log = get(),
            )
        }

        single {
            VBookRepositoryClient(
                httpExecutor = get(),
                log = get(),
            )
        }

        single {
            PluginZipExtractor(
                httpExecutor = get(),
                log = get(),
            )
        }

        single {
            ExtensionManager(
                rootDir = get(),
                repositoryClient = get(),
                zipExtractor = get(),
                log = get(),
            )
        }

        single<ExtensionExecutor> {
            ExtensionExecutorImpl(
                extensionManager = get(),
                httpExecutor = get(),
                cookieStore = get(),
                browserFactory = get(),
                log = get(),
            )
        }
    }
